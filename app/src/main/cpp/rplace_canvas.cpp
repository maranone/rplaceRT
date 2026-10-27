#include "rplace_canvas.h"
#include <fstream>
#include <cstdio>
#include <cstring>
#include <algorithm>
#include <cmath>
#include <chrono>
#include <cstdlib>
#include <thread>
#include <android/log.h>

#define RPLACE_LOG(...) __android_log_print(ANDROID_LOG_INFO, "RplaceCanvas", __VA_ARGS__)

namespace {

inline uint64_t read_varint(const uint8_t* buf, size_t len, size_t& pos) {
    uint64_t result = 0;
    int shift = 0;
    while (pos < len) {
        uint8_t b = buf[pos++];
        result |= (uint64_t)(b & 0x7F) << shift;
        if (!(b & 0x80)) break;
        shift += 7;
    }
    return result;
}

inline void write_varint_u(uint64_t v, std::vector<uint8_t>& out) {
    while (true) {
        uint8_t b = v & 0x7F;
        v >>= 7;
        if (v) { out.push_back(b | 0x80); }
        else   { out.push_back(b); break; }
    }
}

inline int64_t unzigzag(uint64_t v) {
    return (int64_t)(v >> 1) ^ -(int64_t)(v & 1);
}

template <typename T>
inline T read_le(const uint8_t* buf, size_t pos) {
    T v;
    std::memcpy(&v, buf + pos, sizeof(T));
    return v;
}

// "#RRGGBB" -> packed BGRA (matches the [B,G,R,A] byte order LayerProcessor
// expects on its input frame, per layer_processor.h's documented format).
uint32_t hex_rgb_to_bgra(int r, int g, int b) {
    return (uint32_t)0xFF000000u | ((uint32_t)r << 16) | ((uint32_t)g << 8) | (uint32_t)b;
}

uint32_t parse_hex_color_to_bgra(const std::string& hex) {
    if (hex.size() < 7 || hex[0] != '#') return 0xFF000000u; // opaque black fallback
    auto hexval = [](char c) -> int {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        return 0;
    };
    int r = (hexval(hex[1]) << 4) | hexval(hex[2]);
    int g = (hexval(hex[3]) << 4) | hexval(hex[4]);
    int b = (hexval(hex[5]) << 4) | hexval(hex[6]);
    return hex_rgb_to_bgra(r, g, b);
}

// r/place's original April 2017 event used a fixed 16-color palette referenced
// by small numeric index in the raw CSV (e.g. color=13), NOT a "#RRGGBB"
// string -- unlike 2022/2023. The 2017 legacy file's color table therefore
// has plain digit strings ("0".."15"), which parse_hex_color_to_bgra can't
// handle on its own.
constexpr const char* kRplace2017Palette[16] = {
    "#FFFFFF", "#E4E4E4", "#888888", "#222222",
    "#FFA7D1", "#E50000", "#E59500", "#A06A42",
    "#E5D900", "#94E044", "#02BE01", "#00D3DD",
    "#0083C7", "#0000EA", "#CF6EE4", "#820080",
};

uint32_t parse_color_token_to_bgra(const std::string& tok) {
    if (!tok.empty() && tok[0] == '#') return parse_hex_color_to_bgra(tok);
    bool all_digits = !tok.empty();
    for (char c : tok) { if (c < '0' || c > '9') { all_digits = false; break; } }
    if (all_digits) {
        int idx = std::atoi(tok.c_str());
        if (idx >= 0 && idx < 16) return parse_hex_color_to_bgra(kRplace2017Palette[idx]);
    }
    return 0xFF000000u;
}

} // namespace

// bump_progress: end_convert()'s decode pass and its sort thread report
// progress concurrently and don't run in a fixed interleaving relative to
// each other, so a plain store() could make the displayed percentage jump
// backward. CAS-loop to enforce monotonicity.
static void bump_progress(std::atomic<int>& progress, int v) {
    int cur = progress.load(std::memory_order_relaxed);
    while (v > cur && !progress.compare_exchange_weak(cur, v, std::memory_order_relaxed)) {}
}

bool RplaceCanvas::load(const std::string& path, std::string& error_out) {
    m_ready.store(false, std::memory_order_release);
    m_load_progress.store(0, std::memory_order_relaxed);
    // RplaceCanvas is a long-lived singleton reused across years, so a speed
    // set via trigger presses in a previous session otherwise carries over
    // silently into this one (asked to always start at 1x).
    m_speed.store(1.0f, std::memory_order_relaxed);

    uint32_t magic = 0;
    {
        std::ifstream probe(path, std::ios::binary);
        if (!probe) { error_out = "failed to open file: " + path; return false; }
        probe.read(reinterpret_cast<char*>(&magic), 4);
    }
    if (magic != kStreamMagic) {
        // Every file load() sees is expected to already be produced by
        // begin_convert()/feed_convert_bytes()/end_convert() (see class
        // comment in rplace_canvas.h) -- there's no more on-the-fly legacy
        // conversion here, so a mismatched magic means the caller skipped
        // that step (or the file is corrupt/truncated).
        error_out = "not a converted .strm file (bad magic): " + path;
        return false;
    }
    return open_stream(path, error_out);
}

bool RplaceCanvas::begin_convert(const std::string& dst_path, std::string& error_out) {
    m_convert_dst_path = dst_path;
    m_convert_buf.clear();
    m_load_progress.store(0, std::memory_order_relaxed);
    (void)error_out;
    return true;
}

void RplaceCanvas::feed_convert_bytes(const uint8_t* data, size_t len) {
    m_convert_buf.insert(m_convert_buf.end(), data, data + len);
}

bool RplaceCanvas::end_convert(std::string& error_out) {
    // Parses the v8 columnar format accumulated in m_convert_buf by
    // feed_convert_bytes() (see docs/FORMAT_AND_DIRECTOR.md §3):
    //   u64 n_events; u32 n_colors; u32 bucket_ms; u32 color_table_len;
    //   [color_table]; u32 n_specials; [specials...];
    //   ts_col (varint zigzag delta) / cid_col (raw u8, NO uid_col) /
    //   x_col (varint zigzag delta) / y_col (varint zigzag delta)
    // then sorts chronologically, computes per-pixel gap_ms, and writes the
    // result to m_convert_dst_path in the streamable .strm format -- same
    // algorithm the old convert_legacy_format() used, adapted to the v8
    // header/column layout (no per-event user id) and to reading from an
    // in-memory buffer that arrived incrementally rather than a whole file
    // read back off disk.
    auto fail = [&](const char* msg) {
        error_out = msg;
        m_convert_buf.clear();
        m_convert_buf.shrink_to_fit();
        return false;
    };

    const uint8_t* data = m_convert_buf.data();
    const size_t len = m_convert_buf.size();
    size_t pos = 0;

    if (len < 20) return fail("file too small");

    uint64_t n_events    = read_le<uint64_t>(data, pos); pos += 8;
    uint32_t n_colors    = read_le<uint32_t>(data, pos); pos += 4;
    uint32_t bucket_ms   = read_le<uint32_t>(data, pos); pos += 4; (void)n_colors; (void)bucket_ms;

    uint32_t color_table_len = read_le<uint32_t>(data, pos); pos += 4;
    if (pos + color_table_len > len) return fail("truncated color table");
    std::string color_table(reinterpret_cast<const char*>(data + pos), color_table_len);
    pos += color_table_len;

    std::vector<uint32_t> palette_bgra;
    palette_bgra.reserve(n_colors);
    {
        size_t start = 0;
        while (start <= color_table.size()) {
            size_t nl = color_table.find('\n', start);
            std::string tok = (nl == std::string::npos)
                ? color_table.substr(start)
                : color_table.substr(start, nl - start);
            if (!tok.empty()) palette_bgra.push_back(parse_color_token_to_bgra(tok));
            if (nl == std::string::npos) break;
            start = nl + 1;
        }
    }
    if (palette_bgra.empty()) return fail("empty color table");

    uint32_t n_specials = read_le<uint32_t>(data, pos); pos += 4;
    // Special (rect/circle moderator) events are rare (<0.002% of rows) and
    // not needed for MVP playback -- skip past them, still parsing enough to
    // know their byte length so the columnar sections below start correctly.
    for (uint32_t i = 0; i < n_specials; ++i) {
        pos += 8; // idx (u64)
        if (pos >= len) return fail("truncated special event");
        uint8_t kind_flag = data[pos]; pos += 1;
        int n_coords = (kind_flag == 1) ? 4 : 3;
        for (int c = 0; c < n_coords; ++c) read_varint(data, len, pos);
        pos += 8; // t_ms (i64)
        uint16_t user_len = read_le<uint16_t>(data, pos); pos += 2;
        pos += user_len;
        uint16_t color_len = read_le<uint16_t>(data, pos); pos += 2;
        pos += color_len;
    }

    bump_progress(m_load_progress, 10);
    std::vector<int64_t> abs_ms(n_events);
    std::vector<uint8_t> color_idx(n_events);

    // ts_col: zigzag-varint delta-ms, cumulative sum -> absolute ms. v8's
    // rows are already time-bucketed and mostly chronological, so this
    // running sum stays close to a true absolute timeline; the chronological
    // sort below (over the whole array) makes the exact per-file base value
    // irrelevant either way -- see the sort_thread comment.
    {
        int64_t running = 0;
        for (uint64_t i = 0; i < n_events; ++i) {
            int64_t dt = unzigzag(read_varint(data, len, pos));
            running += dt;
            abs_ms[i] = running;
        }
    }
    bump_progress(m_load_progress, 30); // ts_col was the biggest single pass

    // The chronological sort only needs abs_ms, which is already complete --
    // kick it off now on a background thread so it runs concurrently with
    // the remaining column parsing below (join point is right before order
    // is used, near the end of this function).
    std::vector<uint32_t> order(n_events);
    std::thread sort_thread([&order, &abs_ms, n_events]() {
        bool already_sorted = true;
        for (uint64_t i = 0; i < n_events; ++i) {
            order[i] = (uint32_t)i;
            if (i > 0 && abs_ms[i] < abs_ms[i - 1]) already_sorted = false;
        }
        // v8's rows are pre-sorted chronologically on disk (per-bucket, and
        // buckets are time-ordered), so this almost always skips the
        // O(n log n) sort entirely.
        if (!already_sorted) {
            std::sort(order.begin(), order.end(), [&abs_ms](uint32_t a, uint32_t b) {
                return abs_ms[a] < abs_ms[b];
            });
        }
    });

    // cid_col: 1 byte each (no uid_col in v8 -- straight from ts_col to
    // cid_col, unlike the old legacy format).
    for (uint64_t i = 0; i < n_events; ++i) {
        if (pos >= len) {
            sort_thread.join(); // must join before returning, or ~thread() calls std::terminate()
            return fail("truncated cid_col");
        }
        color_idx[i] = data[pos++];
    }
    bump_progress(m_load_progress, 50);
    // x_col / y_col: zigzag-varint DELTA against the previous event's x/y
    // (prev starts at 0 for the first event in the file) -- unlike the old
    // legacy format's x_col/y_col, which were absolute values. See
    // docs/FORMAT_AND_DIRECTOR.md §3.
    int min_x = INT32_MAX, max_x = INT32_MIN, min_y = INT32_MAX, max_y = INT32_MIN;
    std::vector<int32_t> raw_x(n_events), raw_y(n_events);
    {
        int32_t running_x = 0;
        for (uint64_t i = 0; i < n_events; ++i) {
            running_x += (int32_t)unzigzag(read_varint(data, len, pos));
            raw_x[i] = running_x;
            min_x = std::min(min_x, running_x);
            max_x = std::max(max_x, running_x);
        }
    }
    bump_progress(m_load_progress, 65);
    {
        int32_t running_y = 0;
        for (uint64_t i = 0; i < n_events; ++i) {
            running_y += (int32_t)unzigzag(read_varint(data, len, pos));
            raw_y[i] = running_y;
            min_y = std::min(min_y, running_y);
            max_y = std::max(max_y, running_y);
        }
    }
    bump_progress(m_load_progress, 75);

    int offset_x = -min_x;
    int offset_y = -min_y;
    int width  = max_x - min_x + 1;
    int height = max_y - min_y + 1;
    std::vector<int16_t> ex(n_events), ey(n_events);
    for (uint64_t i = 0; i < n_events; ++i) {
        ex[i] = (int16_t)(raw_x[i] + offset_x);
        ey[i] = (int16_t)(raw_y[i] + offset_y);
    }
    raw_x.clear(); raw_x.shrink_to_fit();
    raw_y.clear(); raw_y.shrink_to_fit();
    bump_progress(m_load_progress, 80);

    sort_thread.join();

    // Normalize to 0 = true earliest event now that we know which one that
    // is (order.front(), since order is sorted ascending).
    if (n_events) {
        int64_t base = abs_ms[order.front()];
        for (uint64_t i = 0; i < n_events; ++i) abs_ms[i] -= base;
    }
    bump_progress(m_load_progress, 85);

    // For every event, find the ms gap until the NEXT chronological event at
    // the same (x,y) -- see StreamEvent::gap_ms. One forward pass in
    // chronological order with a per-pixel "last writer" lookup (scratch,
    // freed at scope exit -- not part of the streamed output format).
    std::vector<uint16_t> next_gap_ms_capped(n_events, 0xFFFF);
    {
        std::vector<int32_t> last_writer((size_t)width * (size_t)height, -1);
        for (uint64_t step = 0; step < n_events; ++step) {
            uint32_t idx = order[step];
            int x = ex[idx], y = ey[idx];
            if (x < 0 || x >= width || y < 0 || y >= height) continue;
            size_t pixel = (size_t)y * (size_t)width + (size_t)x;
            int32_t prev = last_writer[pixel];
            if (prev >= 0) {
                int64_t gap = abs_ms[idx] - abs_ms[(uint32_t)prev];
                next_gap_ms_capped[(uint32_t)prev] = (gap >= 0xFFFF) ? (uint16_t)0xFFFF : (uint16_t)gap;
            }
            last_writer[pixel] = (int32_t)idx;
        }
    }
    bump_progress(m_load_progress, 92);

    double duration_ms = n_events ? (double)abs_ms[order.back()] : 0.0;

    // ---- write the streamable file ----
    std::ofstream out(m_convert_dst_path, std::ios::binary | std::ios::trunc);
    if (!out) return fail(("failed to create converted file: " + m_convert_dst_path).c_str());
    auto write_raw = [&out](const void* p, size_t n) {
        out.write(reinterpret_cast<const char*>(p), (std::streamsize)n);
    };
    uint32_t magic = kStreamMagic;
    write_raw(&magic, 4);
    write_raw(&n_events, 8);
    int32_t w32 = width, h32 = height, ox32 = offset_x, oy32 = offset_y;
    write_raw(&w32, 4); write_raw(&h32, 4); write_raw(&ox32, 4); write_raw(&oy32, 4);
    write_raw(&duration_ms, 8);
    uint32_t n_colors_out = (uint32_t)palette_bgra.size();
    write_raw(&n_colors_out, 4);
    write_raw(palette_bgra.data(), palette_bgra.size() * sizeof(uint32_t));

    std::vector<uint8_t> chunk;
    chunk.reserve(1 << 20);
    auto flush_chunk = [&]() {
        if (!chunk.empty()) { write_raw(chunk.data(), chunk.size()); chunk.clear(); }
    };
    int64_t prev_ms = 0;
    for (uint64_t step = 0; step < n_events; ++step) {
        uint32_t idx = order[step];
        int64_t dt = abs_ms[idx] - prev_ms; // >=0: order is sorted ascending by abs_ms
        prev_ms = abs_ms[idx];
        write_varint_u((uint64_t)dt, chunk);
        uint16_t gap = next_gap_ms_capped[idx];
        chunk.push_back((uint8_t)(gap & 0xFF));
        chunk.push_back((uint8_t)(gap >> 8));
        chunk.push_back(color_idx[idx]);
        write_varint_u((uint64_t)(uint16_t)ex[idx], chunk);
        write_varint_u((uint64_t)(uint16_t)ey[idx], chunk);
        if (chunk.size() > (1 << 20)) flush_chunk();
        if (n_events && (step & 0xFFFFFu) == 0) {
            bump_progress(m_load_progress, 92 + (int)(7 * step / n_events));
        }
    }
    flush_chunk();
    out.close();
    if (!out) return fail(("failed writing converted file: " + m_convert_dst_path).c_str());
    bump_progress(m_load_progress, 99);

    RPLACE_LOG("converted %llu events, canvas %dx%d, duration %.0fms -> %s",
               (unsigned long long)n_events, width, height, duration_ms, m_convert_dst_path.c_str());

    m_convert_buf.clear();
    m_convert_buf.shrink_to_fit();
    m_load_progress.store(100, std::memory_order_relaxed);
    return true;
}

bool RplaceCanvas::read_stream_u8(uint8_t& out) {
    if (m_read_buf_pos >= m_read_buf_len) {
        if (m_read_buf.size() != (1 << 16)) m_read_buf.resize(1 << 16);
        m_file.read(reinterpret_cast<char*>(m_read_buf.data()), (std::streamsize)m_read_buf.size());
        std::streamsize got = m_file.gcount();
        if (got <= 0) return false;
        m_read_buf_len = (size_t)got;
        m_read_buf_pos = 0;
    }
    out = m_read_buf[m_read_buf_pos++];
    return true;
}

bool RplaceCanvas::read_stream_u16(uint16_t& out) {
    uint8_t lo, hi;
    if (!read_stream_u8(lo) || !read_stream_u8(hi)) return false;
    out = (uint16_t)(lo | (hi << 8));
    return true;
}

bool RplaceCanvas::read_stream_varint(uint64_t& out) {
    uint64_t result = 0;
    int shift = 0;
    while (true) {
        uint8_t b;
        if (!read_stream_u8(b)) return false;
        result |= (uint64_t)(b & 0x7F) << shift;
        if (!(b & 0x80)) break;
        shift += 7;
    }
    out = result;
    return true;
}

bool RplaceCanvas::open_stream(const std::string& path, std::string& error_out) {
    m_file.close();
    m_file.clear();
    m_file.open(path, std::ios::binary);
    if (!m_file) { error_out = "failed to open stream file: " + path; return false; }

    // New year -- clear any waypoints from whatever was previously loaded;
    // Kotlin re-sends a fresh reel (see set_director_waypoints()) once its
    // atlas load for the new year finishes.
    m_director_waypoints.clear();
    m_director_reel_pos = 0;

    uint32_t magic = 0;
    m_file.read(reinterpret_cast<char*>(&magic), 4);
    if (magic != kStreamMagic) { error_out = "bad stream file magic"; return false; }
    uint64_t n_events = 0;
    m_file.read(reinterpret_cast<char*>(&n_events), 8);
    int32_t w = 0, h = 0, ox = 0, oy = 0;
    m_file.read(reinterpret_cast<char*>(&w), 4);
    m_file.read(reinterpret_cast<char*>(&h), 4);
    m_file.read(reinterpret_cast<char*>(&ox), 4);
    m_file.read(reinterpret_cast<char*>(&oy), 4);
    double duration = 0.0;
    m_file.read(reinterpret_cast<char*>(&duration), 8);
    uint32_t n_colors = 0;
    m_file.read(reinterpret_cast<char*>(&n_colors), 4);
    m_palette_bgra.assign(n_colors, 0);
    if (n_colors) {
        m_file.read(reinterpret_cast<char*>(m_palette_bgra.data()),
                     (std::streamsize)(n_colors * sizeof(uint32_t)));
    }
    if (!m_file) { error_out = "truncated stream header"; return false; }

    m_records_start_offset = (int64_t)m_file.tellg();
    m_width = w; m_height = h; m_offset_x = ox; m_offset_y = oy;
    m_duration_ms = duration;
    m_events_total = n_events;
    m_events_read = 0;
    m_running_abs_ms = 0;
    m_window.clear();
    m_read_buf_pos = 0;
    m_read_buf_len = 0;

    // Unpainted = fully transparent (alpha=0), not opaque dark grey, so that
    // enabling passthrough in settings shows the real room through untouched
    // canvas area instead of a solid panel.
    m_canvas_bgra.assign((size_t)m_width * (size_t)m_height, 0x00000000u);
    m_canvas_age.assign((size_t)m_width * (size_t)m_height, 128);

    m_virtual_time_ms = 0.0;
    m_dirty_since_last_frame = true;
    m_load_progress.store(100, std::memory_order_relaxed);
    m_ready.store(true, std::memory_order_release);

    RPLACE_LOG("opened stream: %llu events, canvas %dx%d, duration %.0fms",
               (unsigned long long)n_events, m_width, m_height, m_duration_ms);
    return true;
}

bool RplaceCanvas::refill_window() {
    while (m_window.size() < kWindowRefillTarget && m_events_read < m_events_total) {
        uint64_t dt = 0;
        uint16_t gap = 0;
        uint8_t cid = 0;
        uint64_t x = 0, y = 0;
        if (!read_stream_varint(dt) || !read_stream_u16(gap) || !read_stream_u8(cid) ||
            !read_stream_varint(x) || !read_stream_varint(y)) {
            return false; // truncated/corrupt stream -- treat as EOF, caller just gets what it has
        }
        m_running_abs_ms += (int64_t)dt;
        m_window.push_back(StreamEvent{m_running_abs_ms, gap, cid, (int16_t)x, (int16_t)y});
        ++m_events_read;
    }
    return true;
}

void RplaceCanvas::paint_event(const StreamEvent& ev) {
    paint_color_only(ev);
}

void RplaceCanvas::paint_color_only(const StreamEvent& ev) {
    int x = ev.x, y = ev.y;
    if (x < 0 || x >= m_width || y < 0 || y >= m_height) return;
    if (ev.cid >= m_palette_bgra.size()) return;
    size_t pixel = (size_t)y * (size_t)m_width + (size_t)x;
    m_canvas_bgra[pixel] = m_palette_bgra[ev.cid];
    // m_canvas_age stays constant 128 (settled) everywhere -- see its
    // declaration in rplace_canvas.h for why per-pixel depth animation was
    // removed.
}

double RplaceCanvas::dirty_throttle_ms_for_speed(float /*speed*/) {
    // Flat cap at Quest's max refresh rate (~90Hz) regardless of playback
    // speed -- no point re-uploading the canvas texture faster than the
    // display can show it, but no need to throttle further than that either
    // now that the real perf issue (the plain single-quad render path) is
    // fixed via the always-curved pipeline.
    return kDirtyThrottleMs;
}

void RplaceCanvas::advance(double real_dt_ms) {
    if (!ready()) return;
    using Clock = std::chrono::steady_clock;
    const auto advance_start = Clock::now();
    std::lock_guard<std::mutex> lock(m_canvas_mutex);
    double virtual_dt_ms = 0.0;
    if (m_playing.load(std::memory_order_relaxed)) {
        virtual_dt_ms = real_dt_ms * (double)m_speed.load(std::memory_order_relaxed);
        m_virtual_time_ms += virtual_dt_ms;
    }
    bool any = false;
    uint64_t events_painted_this_call = 0;
    double pre_refill_ms = 0.0;
    double mid_refill_ms = 0.0;
    if (m_window.size() < kWindowRefillThreshold && m_events_read < m_events_total) {
        const auto t0 = Clock::now();
        refill_window();
        pre_refill_ms = std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
    }
    if (m_director_enabled.load(std::memory_order_relaxed)) update_director();
    // Time the whole catch-up loop, then subtract any mid-loop refill time
    // from it below to get pure paint_event() time -- avoids a second copy
    // of this loop just to time the two phases separately.
    const auto loop_t0 = Clock::now();
    // Density control for droplet-audio triggers (see PaintTrigger/
    // drain_paint_triggers() in the header): at higher playback speeds many
    // more pixels land per advance() call than could ever sound like
    // individual "droplets" without becoming a wall of noise, so only every
    // Nth painted pixel is queued -- stride grows with speed, deterministic
    // (not random) so it stays cheap and gives an even spread rather than
    // clumping. The voice pool in droplet_synth.h caps concurrent notes on
    // top of this, independent of event rate.
    const float speed_now = m_speed.load(std::memory_order_relaxed);
    const int trigger_stride = std::max(1, (int)(speed_now / 2.0f));
    while (!m_window.empty() && (double)m_window.front().abs_ms <= m_virtual_time_ms) {
        const auto& front_ev = m_window.front();
        paint_event(front_ev);
        if ((events_painted_this_call % (uint64_t)trigger_stride) == 0 &&
            m_pending_triggers.size() < kMaxPendingTriggers) {
            m_pending_triggers.push_back(PaintTrigger{front_ev.x, front_ev.y, front_ev.cid});
        }
        m_last_event_abs_ms.store(front_ev.abs_ms, std::memory_order_relaxed);
        m_last_event_x.store(front_ev.x, std::memory_order_relaxed);
        m_last_event_y.store(front_ev.y, std::memory_order_relaxed);
        m_last_event_color_bgra.store(
            front_ev.cid < m_palette_bgra.size() ? m_palette_bgra[front_ev.cid] : 0u,
            std::memory_order_relaxed);
        m_window.pop_front();
        any = true;
        ++events_painted_this_call;
        // Window ran dry mid-catch-up (can happen at very high speed
        // multipliers where a single frame consumes more than one refill's
        // worth) -- top it back up immediately rather than waiting for next
        // frame's threshold check, so a burst of due events doesn't stall.
        if (m_window.empty() && m_events_read < m_events_total) {
            const auto rt0 = Clock::now();
            refill_window();
            mid_refill_ms += std::chrono::duration<double, std::milli>(Clock::now() - rt0).count();
        }
    }
    const double loop_total_ms = std::chrono::duration<double, std::milli>(Clock::now() - loop_t0).count();
    const double refill_ms_this_call = pre_refill_ms + mid_refill_ms;
    const double paint_ms_this_call = std::max(0.0, loop_total_ms - mid_refill_ms);

    // Live pixels/sec EMA for HUD display (see pixels_per_second()) -- 90/10
    // blend keeps it stable frame-to-frame without lagging so much it feels
    // stale. Naturally decays toward 0 while paused (events_painted_this_call
    // stays 0 every call), which is the desired display in that state.
    if (real_dt_ms > 0.0) {
        float inst_rate = (float)((double)events_painted_this_call * (1000.0 / real_dt_ms));
        float prev_ema = m_pixels_per_second.load(std::memory_order_relaxed);
        m_pixels_per_second.store(0.9f * prev_ema + 0.1f * inst_rate, std::memory_order_relaxed);
    }

    // Rolling 5s perf accounting -- see the log block below for the summary.
    static double s_perf_refill_ms = 0.0, s_perf_paint_ms = 0.0, s_perf_advance_ms = 0.0;
    static uint64_t s_perf_events = 0, s_perf_calls = 0;
    s_perf_refill_ms += refill_ms_this_call;
    s_perf_paint_ms += paint_ms_this_call;
    s_perf_events += events_painted_this_call;
    ++s_perf_calls;

    if (any) {
        double throttle_ms = dirty_throttle_ms_for_speed(m_speed.load(std::memory_order_relaxed));
        auto now = std::chrono::steady_clock::now();
        double since_ms = m_last_dirty_mark.time_since_epoch().count() == 0
            ? throttle_ms // first event ever -- don't wait, show it immediately
            : std::chrono::duration<double, std::milli>(now - m_last_dirty_mark).count();
        if (since_ms >= throttle_ms) {
            m_dirty_since_last_frame = true;
            m_last_dirty_mark = now;
        }
    }
    const double advance_ms_this_call =
        std::chrono::duration<double, std::milli>(Clock::now() - advance_start).count();
    s_perf_advance_ms += advance_ms_this_call;

    // Perf summary -- logged every 5s so `adb logcat -s RplaceCanvas` gives a
    // running picture of where advance() time actually goes: refill_ms is
    // disk-read + varint-decode time (refill_window), paint_ms is per-event
    // canvas-write time (paint_event, one call per pixel that landed), and
    // advance_ms is the whole function including the mutex-locked section --
    // if advance_ms is much bigger than refill_ms+paint_ms, the bottleneck is
    // elsewhere (mutex contention with the render thread, most likely, since
    // get_frame() holds the same m_canvas_mutex). events/s reflects actual
    // playback throughput -- compare against speed()*duration to sanity check
    // whether the window is keeping up or perpetually catching up late.
    static double s_last_perf_log_ms = 0.0;
    static auto s_perf_wall_start = std::chrono::steady_clock::now();
    double perf_wall_ms = std::chrono::duration<double, std::milli>(
        std::chrono::steady_clock::now() - s_perf_wall_start).count();
    if (perf_wall_ms - s_last_perf_log_ms > 5000.0) {
        double window_s = (perf_wall_ms - s_last_perf_log_ms) / 1000.0;
        s_last_perf_log_ms = perf_wall_ms;
        RPLACE_LOG("perf/5s: calls=%llu avg_advance=%.3fms avg_refill=%.3fms avg_paint=%.3fms "
                   "events=%llu (%.0f/s) speed=%.1fx read=%llu/%llu window=%zu",
                   (unsigned long long)s_perf_calls,
                   s_perf_advance_ms / std::max<uint64_t>(1, s_perf_calls),
                   s_perf_refill_ms / std::max<uint64_t>(1, s_perf_calls),
                   s_perf_paint_ms / std::max<uint64_t>(1, s_perf_calls),
                   (unsigned long long)s_perf_events, (double)s_perf_events / std::max(0.001, window_s),
                   (double)m_speed.load(), (unsigned long long)m_events_read,
                   (unsigned long long)m_events_total, m_window.size());
        s_perf_refill_ms = s_perf_paint_ms = s_perf_advance_ms = 0.0;
        s_perf_events = s_perf_calls = 0;
    }
}

void RplaceCanvas::set_director_enabled(bool enabled) {
    m_director_enabled.store(enabled, std::memory_order_relaxed);
}

// Called from any thread (typically the JNI thread handling Kotlin's atlas-
// based reel build, see QuestVrActivity.kt's buildRplaceDirectorReel()).
// Replaces the reel outright and restarts travel toward index 0 -- takes
// m_canvas_mutex since update_director_waypoints() (the reader) runs inside
// advance()'s existing lock on a different thread.
void RplaceCanvas::set_director_waypoints(std::vector<Waypoint> waypoints) {
    std::lock_guard<std::mutex> lock(m_canvas_mutex);
    m_director_waypoints = std::move(waypoints);
    m_director_reel_pos = 0;
    m_director_reel_state = DirectorReelState::Traveling;
    m_director_reel_prev_x = m_width / 2;
    m_director_reel_prev_y = m_height / 2;
    m_director_transition_start_wall = std::chrono::steady_clock::now();
    if (m_director_waypoints.empty()) return;
    const Waypoint& first = m_director_waypoints[0];
    double gap0_ms = first.start_ms - m_virtual_time_ms;
    m_director_transition_budget_ms = kDirectorMinTravelMs;
    m_director_transition_target_speed = gap0_ms > 0.0
        ? std::clamp((float)(gap0_ms / m_director_transition_budget_ms), 1.0f, kDirectorMaxSpeed)
        : 1.0f;
}

void RplaceCanvas::update_director() {
    if (!m_director_waypoints.empty()) {
        update_director_waypoints();
    } else {
        update_director_live();
    }
}

void RplaceCanvas::update_director_waypoints() {
    using Clock = std::chrono::steady_clock;
    const auto now = Clock::now();
    if (m_width <= 0 || m_height <= 0 || m_director_waypoints.empty()) return;

    const Waypoint& target = m_director_waypoints[m_director_reel_pos];
    const int zoom_viewport_px = std::max(64, m_width / 8);
    const bool has_next = (m_director_reel_pos + 1) < m_director_waypoints.size();

    if (m_director_reel_state == DirectorReelState::Traveling) {
        double elapsed_ms = std::chrono::duration<double, std::milli>(now - m_director_transition_start_wall).count();
        double frac = std::clamp(elapsed_ms / std::max(1.0, m_director_transition_budget_ms), 0.0, 1.0);

        // Two-phase camera per transition: zoomed out on the whole canvas
        // first (so the viewer can see where the next waypoint sits relative
        // to everything), then zoom in onto the actual target for the back
        // half -- avoids disorienting blind pans straight to a spot.
        if (frac < kDirectorZoomOutFrac) {
            m_director_target_x.store(m_width / 2, std::memory_order_relaxed);
            m_director_target_y.store(m_height / 2, std::memory_order_relaxed);
            m_director_target_viewport_px.store(m_width, std::memory_order_relaxed);
        } else {
            m_director_target_x.store(target.x, std::memory_order_relaxed);
            m_director_target_y.store(target.y, std::memory_order_relaxed);
            m_director_target_viewport_px.store(zoom_viewport_px, std::memory_order_relaxed);
        }

        // Playback-speed envelope: ramp UP toward the transition's fixed
        // cruise speed (computed ONCE when the transition started -- see
        // m_director_transition_target_speed), don't start ramping back down
        // until virtual time has actually caught up to the waypoint's
        // start_ms -- recomputing this every frame straight from the
        // shrinking time-gap collapses back to ~1.0x within a second or two
        // of any large speed spike (a multi-hour gap closes almost instantly
        // at thousands-of-x), which is what made the HUD read "1.0x" for the
        // vast majority of every transition before this was fixed.
        double time_gap_ms = target.start_ms - m_virtual_time_ms;
        bool time_arrived = time_gap_ms <= 0.0;
        float desired_speed = (frac < 0.5 || !time_arrived)
            ? m_director_transition_target_speed
            : 1.0f;
        float cur_speed = m_speed.load(std::memory_order_relaxed);
        m_speed.store(cur_speed + (desired_speed - cur_speed) * 0.08f, std::memory_order_relaxed);

        bool camera_phase_done = frac >= 1.0;
        if (time_arrived && camera_phase_done) {
            m_director_reel_state = DirectorReelState::Holding;
            m_director_target_x.store(target.x, std::memory_order_relaxed);
            m_director_target_y.store(target.y, std::memory_order_relaxed);
            m_director_target_viewport_px.store(zoom_viewport_px, std::memory_order_relaxed);
            // Hold's speed target: aim to close the gap to this waypoint's
            // departure boundary (next waypoint's start, or this one's own
            // end, whichever's first) within roughly kDirectorHoldAimMs of
            // real time. Computed once on entry, same reasoning as the
            // travel cruise speed above.
            double boundary_ms = has_next
                ? std::min(m_director_waypoints[m_director_reel_pos + 1].start_ms, target.end_ms)
                : target.end_ms;
            double remaining_ms = boundary_ms - m_virtual_time_ms;
            m_director_hold_target_speed = remaining_ms > 0.0
                ? std::clamp((float)(remaining_ms / kDirectorHoldAimMs), kDirectorMinHoldSpeed, kDirectorMaxHoldSpeed)
                : kDirectorMinHoldSpeed;
        }
    } else { // Holding
        m_director_target_x.store(target.x, std::memory_order_relaxed);
        m_director_target_y.store(target.y, std::memory_order_relaxed);
        m_director_target_viewport_px.store(zoom_viewport_px, std::memory_order_relaxed);

        // Departure boundary: leave as soon as EITHER the next waypoint's
        // start is due OR this one's own end has been reached, whichever's
        // first -- never lingers on a spot past what the atlas says was
        // actually happening there, even if the next waypoint isn't due yet.
        double boundary_ms = has_next
            ? std::min(m_director_waypoints[m_director_reel_pos + 1].start_ms, target.end_ms)
            : target.end_ms;
        double remaining_ms = boundary_ms - m_virtual_time_ms;

        if (remaining_ms <= 0.0) {
            m_director_reel_prev_x = target.x;
            m_director_reel_prev_y = target.y;
            ++m_director_reel_pos;
            if (m_director_reel_pos >= m_director_waypoints.size()) {
                // Ran out of waypoints -- loop back to the start of the same
                // reel for another pass rather than idling. seek_ms_locked()
                // is safe here: this function runs from advance(), which
                // already holds m_canvas_mutex, so the normal seek_ms()
                // (which takes that same lock) would deadlock.
                seek_ms_locked(0.0);
                m_director_reel_pos = 0;
                m_director_reel_state = DirectorReelState::Traveling;
                m_director_transition_start_wall = now;
                m_director_transition_budget_ms = kDirectorMaxTravelMs; // a real time-jump -- give it the full beat, not a snap
                const Waypoint& first = m_director_waypoints[0];
                double gap0_ms = first.start_ms - m_virtual_time_ms;
                m_director_transition_target_speed = gap0_ms > 0.0
                    ? std::clamp((float)(gap0_ms / m_director_transition_budget_ms), 1.0f, kDirectorMaxSpeed)
                    : 1.0f;
                return;
            }
            const Waypoint& next = m_director_waypoints[m_director_reel_pos];
            double dx = (double)(next.x - m_director_reel_prev_x) / std::max(1, m_width);
            double dy = (double)(next.y - m_director_reel_prev_y) / std::max(1, m_height);
            double dist_frac = std::min(1.4, std::sqrt(dx * dx + dy * dy));
            // Travel budget scales with CANVAS DISTANCE only (close spot ->
            // short ~6s beat, far spot -> up to ~24s) -- keeps a spatially-
            // close waypoint from being forced into a padded-out pan. The
            // (separate) speed-ramp clamp above is what keeps a huge TIME
            // gap from demanding an absurd multiplier.
            double budget = kDirectorMinTravelMs + (kDirectorMaxTravelMs - kDirectorMinTravelMs) * dist_frac;
            m_director_transition_budget_ms = budget;
            m_director_transition_start_wall = now;
            m_director_reel_state = DirectorReelState::Traveling;
            double next_gap_ms = next.start_ms - m_virtual_time_ms;
            m_director_transition_target_speed = next_gap_ms > 0.0
                ? std::clamp((float)(next_gap_ms / budget), 1.0f, kDirectorMaxSpeed)
                : 1.0f;
            return;
        }

        // Ramp toward the hold's speed target, but never past a hard
        // per-tick safety cap sized so virtual time can't jump past the
        // departure boundary in a single (worst-case ~250ms real) frame --
        // this cap naturally shrinks as remaining_ms shrinks, so it also
        // self-decelerates approaching the boundary without needing a
        // separate ramp-down phase.
        float hard_cap = (float)std::clamp(remaining_ms / kDirectorHoldSafetyFrameMs,
                                            (double)kDirectorMinHoldSpeed, (double)kDirectorMaxHoldSpeed);
        float desired_speed = std::min(m_director_hold_target_speed, hard_cap);
        float cur_speed = m_speed.load(std::memory_order_relaxed);
        m_speed.store(cur_speed + (desired_speed - cur_speed) * 0.08f, std::memory_order_relaxed);
    }
}

void RplaceCanvas::update_director_live() {
    // Called every advance() while enabled, but only does real work once the
    // current dwell period has elapsed -- see m_director_next_retarget_wall.
    using Clock = std::chrono::steady_clock;
    const auto now = Clock::now();
    if (m_director_next_retarget_wall.time_since_epoch().count() != 0 && now < m_director_next_retarget_wall) {
        return;
    }
    if (m_width <= 0 || m_height <= 0) return;

    // Coarse grid over the canvas -- 32x32 cells is plenty granular for
    // "which neighborhood is busy right now" without being expensive to
    // score every retarget.
    constexpr int kGridDim = 32;
    const int cell_w = std::max(1, m_width / kGridDim);
    const int cell_h = std::max(1, m_height / kGridDim);
    const int grid_w = (m_width + cell_w - 1) / cell_w;
    const int grid_h = (m_height + cell_h - 1) / cell_h;
    std::vector<float> scores((size_t)grid_w * (size_t)grid_h, 0.0f);

    // Score = event count in m_window (already-decoded, already-buffered
    // upcoming events -- no extra I/O), weighted toward events due sooner so
    // the camera tends to arrive a little ahead of the action rather than
    // reacting after the fact.
    for (const auto& ev : m_window) {
        if (ev.x < 0 || ev.x >= m_width || ev.y < 0 || ev.y >= m_height) continue;
        int gx = ev.x / cell_w, gy = ev.y / cell_h;
        if (gx >= grid_w) gx = grid_w - 1;
        if (gy >= grid_h) gy = grid_h - 1;
        double dt_ms = (double)ev.abs_ms - m_virtual_time_ms;
        float weight = (dt_ms <= 0.0) ? 1.0f : (float)(1.0 / (1.0 + dt_ms / 5000.0));
        scores[(size_t)gy * (size_t)grid_w + (size_t)gx] += weight;
    }

    int best_cell = -1;
    float best_score = 0.0f;
    for (int i = 0; i < grid_w * grid_h; ++i) {
        if (scores[(size_t)i] <= best_score) continue;
        if (std::find(m_director_recent_cells.begin(), m_director_recent_cells.end(), i) !=
            m_director_recent_cells.end()) {
            continue; // novelty: skip cells visited recently
        }
        best_score = scores[(size_t)i];
        best_cell = i;
    }
    if (best_cell < 0) {
        // Quiet stretch (empty window, or everything's on cooldown) -- keep
        // the current target, just push the next check out a bit rather than
        // spinning on every advance() call.
        m_director_next_retarget_wall = now + std::chrono::seconds(2);
        return;
    }

    const int gx = best_cell % grid_w, gy = best_cell / grid_w;
    m_director_target_x.store(gx * cell_w + cell_w / 2, std::memory_order_relaxed);
    m_director_target_y.store(gy * cell_h + cell_h / 2, std::memory_order_relaxed);
    // Fixed framing for v1 (~12% of canvas width in view) -- content-aware
    // zoom sizing (tighter for small skirmishes, wider for big murals) is a
    // documented future refinement, not required for a working MVP.
    m_director_target_viewport_px.store(std::max(64, m_width / 8), std::memory_order_relaxed);

    m_director_recent_cells.push_back(best_cell);
    while (m_director_recent_cells.size() > kDirectorRecentCells) m_director_recent_cells.pop_front();

    static thread_local std::mt19937 rng{std::random_device{}()};
    std::uniform_real_distribution<double> dwell_dist(4.0, 10.0);
    m_director_next_retarget_wall = now + std::chrono::duration_cast<Clock::duration>(
        std::chrono::duration<double>(dwell_dist(rng)));
}

size_t RplaceCanvas::drain_paint_triggers(PaintTrigger* out, size_t max_out) {
    std::lock_guard<std::mutex> lock(m_canvas_mutex);
    size_t n = std::min(max_out, m_pending_triggers.size());
    for (size_t i = 0; i < n; ++i) {
        out[i] = m_pending_triggers.front();
        m_pending_triggers.pop_front();
    }
    return n;
}

void RplaceCanvas::seek_ms(double target_ms) {
    std::lock_guard<std::mutex> lock(m_canvas_mutex);
    seek_ms_locked(target_ms);
}

// Actual seek logic, callable from anywhere already holding m_canvas_mutex
// (specifically update_director_reel(), called from advance() which already
// holds it) -- seek_ms() itself can't be called re-entrantly from there
// since std::mutex isn't recursive.
void RplaceCanvas::seek_ms_locked(double target_ms) {
    target_ms = std::max(0.0, std::min(target_ms, m_duration_ms));
    // Forward-only playback engine -- seeking backward means re-simulating
    // from the start (re-reading the stream file from its records offset).
    // Documented as a known MVP limitation in the plan.
    // Unpainted = fully transparent (alpha=0), matching load()'s initial
    // fill -- NOT opaque dark grey, which briefly showed as a wrong "flash"
    // to gray on every jump/rewind before this matched load()'s convention.
    std::fill(m_canvas_bgra.begin(), m_canvas_bgra.end(), 0x00000000u);
    std::fill(m_canvas_age.begin(), m_canvas_age.end(), (uint8_t)128);

    m_file.clear();
    m_file.seekg(m_records_start_offset);
    m_running_abs_ms = 0;
    m_events_read = 0;
    m_window.clear();
    m_pending_triggers.clear(); // don't let a big jump replay as a burst of droplet notes
    m_read_buf_pos = 0;
    m_read_buf_len = 0;

    m_virtual_time_ms = target_ms;
    refill_window();
    while (!m_window.empty() && (double)m_window.front().abs_ms <= m_virtual_time_ms) {
        paint_color_only(m_window.front());
        m_window.pop_front();
        if (m_window.empty() && m_events_read < m_events_total) refill_window();
    }
    m_dirty_since_last_frame = true;
}

bool RplaceCanvas::get_frame(qrd::FrameOutput& out_frame, qrd::EmulatorInputState&, uint64_t& frame_seq) {
    if (!ready()) return false;
    std::lock_guard<std::mutex> lock(m_canvas_mutex);
    if (m_dirty_since_last_frame) {
        ++m_frame_seq;
        m_dirty_since_last_frame = false;
    }
    if (frame_seq != m_frame_seq) {
        out_frame.width  = (uint32_t)m_width;
        out_frame.height = (uint32_t)m_height;
        out_frame.rgba8888 = m_canvas_bgra;     // BGRA order, see parse_hex_color_to_bgra
        out_frame.depth_map = m_canvas_age;     // 128=settled (canvas depth), 255=just placed (near)
        out_frame.zbuffer.clear();
        out_frame.layers.clear();
        out_frame.visible_source_id.clear();
        frame_seq = m_frame_seq;
    }
    return out_frame.width > 0;
}
