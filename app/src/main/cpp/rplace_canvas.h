#pragma once
#include <cstdint>
#include <string>
#include <vector>
#include <deque>
#include <unordered_map>
#include <fstream>
#include <mutex>
#include <atomic>
#include <chrono>
#include <random>
#include <thread>
#include "emulator_backend.h"  // qrd::FrameOutput, qrd::EmulatorInputState

// Streaming playback of the r/place canvas history as a single full-frame
// "layer" fed through the existing emulator FrameProvider pipeline.
//
// load(path) opens a ".strm" file already in the streamable format (see
// kStreamMagic below) -- gameplay always reads from it in small forward-only
// chunks, so RAM use stays bounded by kWindowRefillTarget regardless of how
// many total events the year has (2022's 160M events used to mean ~3GB
// resident just for RplaceCanvas's arrays -- combined with everything else
// OpenXR/GL needs concurrently during actual playback, that's what caused
// the "nearing the maximum memory limit" crash. Streaming keeps steady-state
// playback memory to a few tens of MB regardless of year size).
//
// A .strm file is produced ahead of time by begin_convert()/
// feed_convert_bytes()/end_convert() (see below) from the app's bundled
// brotli-compressed "v8" columnar assets (see docs/FORMAT_AND_DIRECTOR.md)
// -- driven by Kotlin streaming brotli-decompressed bytes in from the
// bundled asset via JNI, so no full decompressed file is ever written to
// disk first. This conversion pays an O(n) full-decode cost ONCE per year
// (the first time it's played); every subsequent replay opens the
// already-converted .strm file directly via load(), which is why replays
// got faster as a side effect.
class RplaceCanvas {
public:
    // Opens an already-converted ".strm" file (kStreamMagic) for streaming
    // playback. Returns false (via error_out) if the file isn't in that
    // format -- callers must run it through begin_convert()/
    // feed_convert_bytes()/end_convert() first (see class comment).
    bool load(const std::string& path, std::string& error_out);

    // One-time conversion of a bundled v8-format asset (see
    // docs/FORMAT_AND_DIRECTOR.md §3: header + color table + specials + 4
    // columnar sections ts_col/cid_col/x_col/y_col, NO per-event user id)
    // into the streamable .strm format load() expects, driven by
    // Kotlin-side streaming brotli decompression rather than a fully
    // decompressed on-disk intermediate file:
    //   begin_convert(dst_path, err)         -- once, before any feed calls
    //   feed_convert_bytes(data, len)        -- repeatedly, in arrival order,
    //                                            for every decompressed chunk
    //   end_convert(err)                     -- once, after the last chunk;
    //                                            parses + sorts + writes
    //                                            dst_path, returns success
    // Decompressed bytes are accumulated into m_convert_buf as they arrive
    // (still one full O(n) buffer, same memory profile the old
    // convert_legacy_format() had -- inherent to needing every column fully
    // in hand before records can be interleaved chronologically) but this
    // avoids ever writing that decompressed data to disk first, unlike the
    // old download-and-decompress-to-a-temp-.bin-file path it replaces.
    // Not thread-safe with itself -- call from a single background thread,
    // same as load().
    bool begin_convert(const std::string& dst_path, std::string& error_out);
    void feed_convert_bytes(const uint8_t* data, size_t len);
    bool end_convert(std::string& error_out);

    // Advances virtual playback time by real_dt_ms * speed, applying any newly
    // "arrived" pixel events to the canvas. Call once per rendered frame.
    void advance(double real_dt_ms);

    void set_speed(float speed) { m_speed.store(speed, std::memory_order_relaxed); }
    float speed() const { return m_speed.load(std::memory_order_relaxed); }
    void set_playing(bool playing) { m_playing.store(playing, std::memory_order_relaxed); }
    bool playing() const { return m_playing.load(std::memory_order_relaxed); }

    // Jump to an absolute virtual time (ms since first event). Forward-only
    // playback still applies from here on; seeking backward re-simulates from
    // the start (slow for late timestamps -- acceptable for MVP, see plan) by
    // re-reading the stream file from its records-start offset.
    void seek_ms(double target_ms);

    double virtual_time_ms() const { return m_virtual_time_ms; }
    double duration_ms() const { return m_duration_ms; }
    // Live pixels/sec, smoothed via an EMA (see advance()) so it's stable
    // enough to display without being stale -- unlike the diagnostic 5s
    // rolling accumulator in advance(), this updates every call.
    float pixels_per_second() const { return m_pixels_per_second.load(std::memory_order_relaxed); }
    int width() const { return m_width; }
    int height() const { return m_height; }
    // Canvas-space = raw_event_xy + offset (baked in at conversion time so
    // canvas-space always starts at (0,0) -- see m_offset_x/y below). Atlas
    // data (external, in raw event-space) needs these to translate into
    // canvas-space before comparing against on-canvas coordinates.
    int offset_x() const { return m_offset_x; }
    int offset_y() const { return m_offset_y; }

    // Snapshot of the most recently painted pixel event (updated at the end
    // of each paint_event() call in advance()) -- lets the HUD show the raw
    // "ground truth" record (x, y, color, its own timestamp) alongside the
    // rendered canvas so it can be eyeballed against what's actually drawn
    // there, independent of the director's camera position. x/y are
    // canvas-space (already offset-adjusted, same space as get_frame()'s
    // pixel grid). color_bgra is the resolved palette entry (see
    // m_palette_bgra), 0 if no event has painted yet this session.
    int64_t last_event_abs_ms() const { return m_last_event_abs_ms.load(std::memory_order_relaxed); }
    int last_event_x() const { return m_last_event_x.load(std::memory_order_relaxed); }
    int last_event_y() const { return m_last_event_y.load(std::memory_order_relaxed); }
    uint32_t last_event_color_bgra() const { return m_last_event_color_bgra.load(std::memory_order_relaxed); }

    bool ready() const { return m_ready.load(std::memory_order_acquire); }
    // Coarse (checkpoint-based, not per-event) progress through load(), 0-100.
    // Meant for a "loading NN%" label while the caller waits -- not a precise
    // ETA. Safe to read from any thread while a load is in flight.
    int load_progress_percent() const { return m_load_progress.load(std::memory_order_relaxed); }

    // One painted pixel's data, as needed to trigger a "droplet" audio note
    // (see droplet_synth.h) -- deliberately smaller than StreamEvent so the
    // ring buffer below stays cheap regardless of how bursty painting gets.
    struct PaintTrigger {
        int16_t x, y;
        uint8_t cid;
    };

    // Drains up to max_out newly-painted-pixel triggers (queued by advance(),
    // see paint_event()) into out, for the caller to turn into audio notes.
    // Called once per frame from outside m_canvas_mutex (e.g. alongside
    // get_frame()) -- NOT from the audio callback itself; audio-thread work
    // must stay lock-free, so this hands the actual synth::trigger() calls to
    // whichever thread drains this (see questretrodepth_main.cpp/openxr_shell.cpp
    // call site). Thread-safe: takes m_canvas_mutex briefly, same lock advance()
    // uses to push, held only for the vector swap/copy.
    size_t drain_paint_triggers(PaintTrigger* out, size_t max_out);

    // FrameProvider-compatible callback. out_frame.rgba8888 is filled in BGRA
    // byte order (matches what LayerProcessor::to_rgba expects on input) plus
    // a per-pixel depth_map derived from recency-of-change, for the
    // fade-in/settle depth effect (see gles_renderer's existing Y-depth path).
    bool get_frame(qrd::FrameOutput& out_frame, qrd::EmulatorInputState& input, uint64_t& frame_seq);

    // ---- auto-director: Place Atlas-driven "highlight reel" ----
    // No native precompute/scan anymore -- Kotlin already has the full,
    // real Place Atlas data (names, descriptions, and REAL time ranges for
    // 2022/2023, see QuestVrActivity.kt's AtlasCenter/parseAtlasTimeRange)
    // loaded for whatever year is playing. It picks a random ~50-entry
    // subset (distinct start times, sorted ascending -- see
    // buildRplaceDirectorReel()) and hands it down via
    // set_director_waypoints(); this class just travels between them in
    // that order. "Get there when it starts, leave when the next one's due
    // (or this one's own window closes, whichever's first)" -- see
    // update_director_waypoints() for the full state machine. Falls back to
    // the original live-window hot-cell scoring (update_director_live())
    // whenever no waypoints have been set yet (e.g. atlas failed to load,
    // or 2017 -- see the Kotlin doc comment on why 2017 has no real atlas
    // timing and gets synthetic evenly-spaced waypoints instead).
    void set_director_enabled(bool enabled);
    bool director_enabled() const { return m_director_enabled.load(std::memory_order_relaxed); }
    // Replaces the reel with this list and restarts travel toward index 0.
    // MUST already be time-sorted ascending by start_ms with no two entries
    // sharing a start_ms (Kotlin's job, not this class's -- see
    // buildRplaceDirectorReel()'s dedup pass). All times are virtual-ms
    // (same domain as virtual_time_ms(), i.e. 0 = this year's first event),
    // not epoch ms. Safe to call from any thread (takes m_canvas_mutex).
    struct Waypoint { int32_t x = 0, y = 0; double start_ms = 0.0, end_ms = 0.0; };
    void set_director_waypoints(std::vector<Waypoint> waypoints);
    // Canvas-pixel-space target the director currently wants the camera
    // centered on, and a desired "zoom" expressed as canvas-pixel viewport
    // width (smaller = more zoomed in) -- caller (openxr_shell) is
    // responsible for converting these into its own metre-space camera
    // uniforms and for smoothing/lerping toward them; RplaceCanvas only
    // decides WHERE, not how the camera gets there.
    int director_target_x() const { return m_director_target_x.load(std::memory_order_relaxed); }
    int director_target_y() const { return m_director_target_y.load(std::memory_order_relaxed); }
    int director_target_viewport_px() const { return m_director_target_viewport_px.load(std::memory_order_relaxed); }

private:
    // ---- streamable (.strm) file format ----
    // u32 magic (kStreamMagic); u64 n_events; i32 width,height,offset_x,offset_y;
    // f64 duration_ms; u32 n_colors; n_colors * u32 palette_bgra (pre-resolved,
    // so runtime never re-parses hex/2017-numeric color tokens);
    // then n_events records, back to back, in chronological order:
    //   varint delta_ts_ms (unsigned -- always >=0, chronological order)
    //   u16    gap_capped_ms (fixed -- see StreamEvent::gap_ms)
    //   u8     cid
    //   varint x (unsigned, canvas-space, already offset-adjusted so >=0)
    //   varint y (unsigned)
    static constexpr uint32_t kStreamMagic = 0x324C5052u; // "RPL2"

    // One decoded-but-not-yet-applied event, held in m_window.
    struct StreamEvent {
        int64_t  abs_ms;
        uint16_t gap_ms; // ms until the next chronological event at this exact
                          // pixel, capped -- 0xFFFF = no upcoming overwrite.
                          // See paint_event()/kDefaultTotalDescentMs.
        uint8_t  cid;
        int16_t  x, y;   // canvas-space
    };

    // Accumulates feed_convert_bytes() input between begin_convert() and
    // end_convert() -- see begin_convert()'s doc comment above. Cleared
    // (and shrink_to_fit'd) as soon as end_convert() is done with it.
    std::vector<uint8_t> m_convert_buf;
    std::string m_convert_dst_path;

    // Reads a .strm file's header, positions m_file at the records section,
    // and resets playback state to the beginning. Does NOT read any records
    // yet -- refill_window() does that on demand.
    bool open_stream(const std::string& path, std::string& error_out);
    // Reads more records from m_file into m_window (up to kWindowRefillTarget
    // total buffered), advancing m_running_abs_ms/m_events_read as it goes.
    // Returns false only on a real read error; reaching EOF with fewer than
    // the target buffered is not an error (just means we're near the end).
    bool refill_window();

    std::ifstream m_file;
    int64_t m_records_start_offset = 0; // byte offset in m_file where records begin
    int64_t m_running_abs_ms = 0;       // cumulative ts as records are decoded from disk
    uint64_t m_events_total = 0;
    uint64_t m_events_read = 0;         // how many records have been decoded from disk so far

    // Read-ahead byte buffer backing refill_window()'s varint decode -- MUST
    // be member state (not function-local) so leftover buffered-but-not-yet-
    // consumed bytes survive between refill_window() calls; m_file's own get
    // pointer has already moved past them by the time we'd otherwise notice.
    std::vector<uint8_t> m_read_buf;
    size_t m_read_buf_pos = 0, m_read_buf_len = 0;
    bool read_stream_u8(uint8_t& out);
    bool read_stream_u16(uint16_t& out);
    bool read_stream_varint(uint64_t& out);

    // FIFO of decoded-but-not-yet-due events, refilled from disk as playback
    // consumes it. Bounded size regardless of year length -- this (plus
    // m_in_flight below) is what keeps steady-state memory small.
    std::deque<StreamEvent> m_window;
    static constexpr size_t kWindowRefillTarget    = 500000; // ~7.5MB worth of StreamEvent
    static constexpr size_t kWindowRefillThreshold = 100000; // refill once it drops below this

    std::vector<uint32_t> m_palette_bgra; // color_table index -> packed BGRA (pre-resolved)

    int m_width = 0;
    int m_height = 0;
    int m_offset_x = 0; // canvas-space x = event.x + m_offset_x (baked in at conversion time)
    int m_offset_y = 0;

    std::vector<uint32_t> m_canvas_bgra;  // width*height, current composited canvas
    // width*height, constant 128 (=settled/canvas-plane depth) everywhere --
    // per-pixel depth animation was removed (see paint_event()) since the
    // bookkeeping (m_in_flight tracking on every event) was a suspected
    // contributor to slowdown/crash during high-volume years like 2022.
    // Kept only because get_frame()'s FrameOutput::depth_map still expects
    // an array of this shape.
    std::vector<uint8_t>  m_canvas_age;

    double m_virtual_time_ms = 0.0;
    double m_duration_ms = 0.0;

    std::atomic<float> m_speed{1.0f};
    std::atomic<bool>  m_playing{true};
    std::atomic<bool>  m_ready{false};
    std::atomic<int>   m_load_progress{0};
    std::atomic<float> m_pixels_per_second{0.0f}; // EMA, updated in advance()
    // Last-painted-event snapshot for the HUD (see last_event_*() above) --
    // written once per paint_event() call inside advance()'s catch-up loop,
    // so it always reflects the newest applied pixel even during a
    // high-speed multi-event-per-frame catch-up burst.
    std::atomic<int64_t>  m_last_event_abs_ms{0};
    std::atomic<int>      m_last_event_x{0};
    std::atomic<int>      m_last_event_y{0};
    std::atomic<uint32_t> m_last_event_color_bgra{0};

    std::mutex m_canvas_mutex;   // guards m_canvas_bgra/m_canvas_age/m_window/m_file/etc.
    uint64_t m_frame_seq = 0;
    bool m_dirty_since_last_frame = true;
    // Throttles how often the full-canvas texture actually gets re-uploaded
    // to the GPU (see gles_renderer.cpp's update_layer -- it's a full
    // glTexSubImage2D of every pixel, not just the ones that changed).
    // During active playback advance() applies new events almost every
    // render call, which used to mark the frame dirty ~72 times/sec on
    // Quest -- a real head-tracking judder contributor since the upload
    // competes with the render thread for GPU time every single frame.
    // Sparse pixel writes aren't perceptible at that rate anyway, so cap it.
    std::chrono::steady_clock::time_point m_last_dirty_mark{};
    static constexpr double kDirtyThrottleMs = 11.11; // ~90Hz cap on texture re-upload (matches Quest's max refresh)
    // Beyond 2x speed there's no reason to keep re-uploading at a fixed real-
    // time cadence: a huge number of pixels can land within a single texture
    // upload's worth of real time, so uploading more often than that just
    // burns GPU bandwidth on intermediate states nobody perceives at that
    // speed anyway. Scales the throttle interval up roughly proportionally
    // to speed past 2x (see dirty_throttle_ms_for_speed()), capped so it
    // never goes fully unresponsive even at the max speed multiplier.
    static double dirty_throttle_ms_for_speed(float speed);

    // Bounded FIFO of pending droplet-audio triggers, filled by advance()'s
    // paint loop and drained by drain_paint_triggers(). Bounded size (not
    // proportional to event volume) so a huge catch-up burst at high replay
    // speed can't grow this unbounded -- advance() drops/samples instead of
    // growing it past kMaxPendingTriggers (see advance()'s density control).
    static constexpr size_t kMaxPendingTriggers = 512;
    std::deque<PaintTrigger> m_pending_triggers; // guarded by m_canvas_mutex

    // Unlocked seek body, callable while m_canvas_mutex is already held --
    // see seek_ms()'s doc comment.
    void seek_ms_locked(double target_ms);

    void paint_event(const StreamEvent& ev);
    // Same as paint_event now that per-pixel depth animation is gone -- kept
    // as a separate call so seek_ms()'s full-history replay site doesn't need
    // to change.
    void paint_color_only(const StreamEvent& ev);

    // Dispatches every advance() call: waypoint travel
    // (update_director_waypoints()) once Kotlin has handed down a reel (see
    // set_director_waypoints()), else falls back to the original live
    // hot-cell chasing (update_director_live()) so the director isn't idle
    // before that happens.
    void update_director();
    // Original MVP scorer: scans only m_window (already-decoded, already-
    // buffered near-term events). Fallback only -- see update_director().
    void update_director_live();
    // Atlas-driven director: travels toward m_director_waypoints[pos] so as
    // to arrive (both spatially AND in virtual time) right as its start_ms
    // begins, holds there, then departs for the next one as soon as EITHER
    // the next waypoint's start_ms is due OR this one's own end_ms is
    // reached (whichever comes first) -- never lingers on a spot past what
    // the atlas says was actually happening there. During the hold, playback
    // speed is capped each tick so virtual time can't jump past that
    // boundary in one frame (see the hard_cap computation in
    // rplace_canvas.cpp). Same zoom-out-to-full-canvas-then-zoom-in travel
    // beat as before. See rplace_canvas.cpp for the full state machine.
    void update_director_waypoints();

    std::atomic<bool> m_director_enabled{false};
    std::atomic<int>  m_director_target_x{0};
    std::atomic<int>  m_director_target_y{0};
    std::atomic<int>  m_director_target_viewport_px{0};
    std::chrono::steady_clock::time_point m_director_next_retarget_wall{};
    // Small bounded history of recently-targeted cells (grid-cell index, not
    // pixel coords) so update_director_live() doesn't immediately re-pick
    // the same hot spot every retarget -- see its novelty logic.
    static constexpr size_t kDirectorRecentCells = 8;
    std::deque<int> m_director_recent_cells;

    // ---- atlas-driven reel (see update_director_waypoints()) ----
    // Guarded by m_canvas_mutex (both set_director_waypoints(), called from
    // whatever thread the JNI call lands on, and update_director_waypoints(),
    // called from inside advance()'s existing lock, take/hold it) -- no
    // separate mutex needed.
    std::vector<Waypoint> m_director_waypoints; // time-sorted ascending by start_ms, Kotlin's job
    enum class DirectorReelState { Traveling, Holding };
    DirectorReelState m_director_reel_state = DirectorReelState::Traveling;
    size_t m_director_reel_pos = 0;
    int32_t m_director_reel_prev_x = 0, m_director_reel_prev_y = 0; // "from" point for the current transition's distance calc
    std::chrono::steady_clock::time_point m_director_transition_start_wall{};
    double m_director_transition_budget_ms = 0.0; // real-time budget for the CURRENT transition
    // Cruise speed for the CURRENT transition, computed ONCE when the
    // transition starts (not recomputed every frame from a shrinking
    // time-gap -- that collapses back to ~1.0x within a second or two of any
    // big speed spike, since a large gap closes almost instantly at
    // thousands-of-x). See update_director_waypoints()'s speed envelope.
    float m_director_transition_target_speed = 1.0f;
    static constexpr double kDirectorMinTravelMs = 6000.0;  // floor: still worth a brief zoom-out/in even for a close waypoint
    static constexpr double kDirectorMaxTravelMs = 24000.0; // ceiling: never drags out a huge time-gap forever
    static constexpr double kDirectorZoomOutFrac = 0.45; // fraction of the transition spent zoomed out at the full canvas
    static constexpr float  kDirectorMaxSpeed = 3000.0f; // playback-speed ramp ceiling during travel (below the manual-control 50000x cap -- stays visually parseable)
    // Hold-phase target: real-time budget the hold AIMS to close the gap to
    // its boundary (next waypoint's start_ms, or this one's own end_ms,
    // whichever's first) within -- same role a fixed dwell used to play,
    // just now driving a speed target instead of a fixed timer, since the
    // actual departure is boundary-triggered (see update_director_waypoints()),
    // not time-triggered.
    static constexpr double kDirectorHoldAimMs = 30000.0;
    // Computed once on entering Holding (avoids the same per-frame-collapse
    // problem as the travel cruise speed).
    float m_director_hold_target_speed = 1.0f;
    static constexpr float kDirectorMinHoldSpeed = 1.0f;
    static constexpr float kDirectorMaxHoldSpeed = 600.0f; // capped well below travel's ceiling -- still watchable while static on one spot
    // Real-time safety cap on the hold's speed each tick so virtual time
    // can't blow past the hold's boundary in a single frame -- sized off the
    // real-dt clamp ceiling upstream (see questretrodepth_main.cpp's
    // rplace_frame_provider(), dt_ms clamped to 250ms), so even a single
    // worst-case frame gap can't overshoot by more than that.
    static constexpr double kDirectorHoldSafetyFrameMs = 250.0;
};
