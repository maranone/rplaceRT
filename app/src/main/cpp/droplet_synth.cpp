#include "droplet_synth.h"

#include <algorithm>
#include <cmath>

qrd::DropletSynth g_droplet_synth;

namespace qrd {

namespace {

// Minor pentatonic intervals (semitones from root) -- every pair of degrees
// in this set is consonant, so any number of simultaneously-ringing voices
// still sounds like a chord rather than noise. Root picked in the middle of
// vocal/bell range so octave shifts (see y_norm below) stay pleasant.
constexpr int kScaleSemitones[5] = {0, 3, 5, 7, 10};
constexpr float kRootHz = 220.f; // A3

float note_freq(float y_norm, uint8_t color_id) {
    // y_norm 0 (top of canvas) -> +1 octave, 1 (bottom) -> -1 octave.
    y_norm = std::clamp(y_norm, 0.f, 1.f);
    const int octave_shift = (int)std::round((0.5f - y_norm) * 2.f); // -1..+1
    const int degree = color_id % 5;
    const int semitone = kScaleSemitones[degree] + octave_shift * 12;
    return kRootHz * std::pow(2.f, semitone / 12.f);
}

} // namespace

void DropletSynth::set_sample_rate(int sr) {
    m_sr = sr;
    for (auto& v : m_voices) {
        // ~4ms attack, ~350ms decay -- soft pluck, not a click or a drone.
        v.env_attack_step = 1.f / (0.004f * sr);
        v.env_decay_coef  = std::pow(0.001f, 1.f / (0.35f * sr));
    }
}

void DropletSynth::trigger(float y_norm, uint8_t color_id, float pan, float atten01) {
    const float freq = note_freq(y_norm, color_id);
    const uint64_t age = m_age_counter.fetch_add(1, std::memory_order_relaxed);

    // Find a free voice, or steal the oldest active one (bounded polyphony
    // caps CPU cost and keeps replay at high speed from turning into a wall
    // of overlapping notes -- see rplace_canvas.cpp's probabilistic sampling
    // for the other half of that density control).
    int pick = -1;
    uint64_t oldest_age = ~0ull;
    for (int i = 0; i < kMaxVoices; ++i) {
        if (!m_voices[i].active.load(std::memory_order_acquire)) { pick = i; break; }
        if (m_voices[i].voice_age < oldest_age) { oldest_age = m_voices[i].voice_age; pick = i; }
    }
    if (pick < 0) return;

    Voice& v = m_voices[pick];
    v.freq = freq;
    v.phase = 0.f;
    v.phase2 = 0.f;
    v.pan = std::clamp(pan, -1.f, 1.f);
    v.level = std::clamp(atten01, 0.f, 1.f);
    v.env = 0.f;
    v.in_attack = true;
    v.voice_age = age;
    v.active.store(true, std::memory_order_release);
}

void DropletSynth::render(int16_t* buf, int frames, float gain01) {
    if (gain01 <= 0.f) return;
    constexpr float kTwoPi = 6.28318530718f;
    constexpr float kClamp = 32767.f;

    for (auto& v : m_voices) {
        if (!v.active.load(std::memory_order_acquire)) continue;

        const float gl = 1.f - std::max(0.f,  v.pan);
        const float gr = 1.f - std::max(0.f, -v.pan);
        const float voice_gain = gain01 * v.level * 0.18f; // headroom for overlapping voices

        for (int i = 0; i < frames; ++i) {
            if (v.in_attack) {
                v.env += v.env_attack_step;
                if (v.env >= 1.f) { v.env = 1.f; v.in_attack = false; }
            } else {
                v.env *= v.env_decay_coef;
            }

            v.phase  += kTwoPi * v.freq / m_sr;
            v.phase2 += kTwoPi * (v.freq * 2.01f) / m_sr; // slightly detuned 2nd partial -> bell-ish
            if (v.phase  > kTwoPi) v.phase  -= kTwoPi;
            if (v.phase2 > kTwoPi) v.phase2 -= kTwoPi;

            const float sample = (std::sin(v.phase) * 0.8f + std::sin(v.phase2) * 0.2f) * v.env * voice_gain;

            const float outL = buf[i * 2 + 0] + sample * gl * 32768.f;
            const float outR = buf[i * 2 + 1] + sample * gr * 32768.f;
            buf[i * 2 + 0] = static_cast<int16_t>(std::clamp(outL, -kClamp, kClamp));
            buf[i * 2 + 1] = static_cast<int16_t>(std::clamp(outR, -kClamp, kClamp));

            if (!v.in_attack && v.env < 0.001f) {
                v.active.store(false, std::memory_order_release);
                break;
            }
        }
    }
}

} // namespace qrd
