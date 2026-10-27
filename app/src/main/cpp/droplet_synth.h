#pragma once
// Ambient "droplet" tones triggered by pixels landing during r/place canvas
// replay. Lock-free, allocation-free: RplaceCanvas::advance() (game/XR
// thread) calls trigger(), AudioProcessor::process() (AAudio callback
// thread) calls render() every buffer. All cross-thread state is a fixed
// array of voices guarded only by std::atomic flags -- no mutex, since
// audio callbacks must never block.
//
// Harmony: every trigger's pitch is quantized into a pentatonic scale table
// (see kScaleSemitones), so no matter how many voices overlap the result
// stays consonant -- there is no arbitrary/dissonant frequency this can
// produce. Octave comes from the pixel's Y (top of canvas = higher octave,
// "rain falling" feel); scale degree comes from the pixel's palette color
// id, so a given color always plays the same note.

#include <atomic>
#include <cstdint>
#include <cstddef>

namespace qrd {

class DropletSynth {
public:
    static constexpr int kMaxVoices = 24;

    void set_sample_rate(int sr);

    // Called from the game/XR thread (RplaceCanvas::advance()'s consumer,
    // see rplace_canvas.cpp). y_norm/color_id pick pitch (see class comment);
    // pan is -1..1 (left..right), atten01 is 0..1 extra gain from distance/
    // off-axis angle (also nudges a light low-pass so far/off-axis droplets
    // sound softer, cheap substitute for full HRTF). Never allocates or
    // blocks -- safe to call at any rate; excess calls beyond kMaxVoices
    // just steal the quietest/oldest active voice.
    void trigger(float y_norm, uint8_t color_id, float pan, float atten01);

    // Called from the AAudio callback (see AudioProcessor::process() in
    // audio_processor.cpp). Mixes all active voices additively into buf
    // (interleaved stereo int16), scaled by gain01. No locks, no allocation.
    void render(int16_t* buf, int frames, float gain01);

private:
    struct Voice {
        std::atomic<bool> active{false};
        float freq = 0.f;
        float phase = 0.f;
        float phase2 = 0.f; // short partial for a soft bell/droplet timbre
        float pan = 0.f;
        float level = 0.f;      // low-pass "distance" gain, applied at trigger time
        float env = 0.f;        // current envelope amplitude
        float env_attack_step = 0.f;
        float env_decay_coef = 0.f;
        bool  in_attack = false;
        uint64_t voice_age = 0;
    };

    Voice m_voices[kMaxVoices];
    std::atomic<uint64_t> m_age_counter{0};
    int m_sr = 44100;
};

} // namespace qrd

extern qrd::DropletSynth g_droplet_synth;
