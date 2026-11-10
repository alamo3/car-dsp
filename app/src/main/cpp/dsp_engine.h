// CabinEQ native DSP engine.
//
// Signal chain (interleaved stereo float):
//   [resample source rate -> output rate]  (only when they differ; see resampler.h)
//   -> preamp (smoothed) -> N biquads (EQ: shelves + peaking, designed in Kotlin)
//   -> lookahead peak limiter -> hard safety clip at 0 dBFS
//
// Resampling first means the EQ runs at the rate the device actually mixes at, and the
// limiter is still the last stage, so the output peaks are still bounded.
//
// Threading: setParams() is called from the control thread (10 Hz); process() runs on
// the audio thread. Parameters are handed over through a mutex the audio thread only
// ever try_lock()s, so the audio thread never blocks.
#pragma once

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <mutex>
#include <vector>

#include "resampler.h"

namespace cabin {

constexpr int kMaxBands = 32;   // the user's 10 bands plus a cabin correction of up to ~12

struct BiquadCoeffs {
    double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
};

// Transposed direct form II, double precision (float biquads at 31 Hz / 48 kHz are noisy).
struct BiquadState {
    double z1 = 0, z2 = 0;

    inline double process(const BiquadCoeffs& c, double x) {
        const double y = c.b0 * x + z1;
        z1 = c.b1 * x - c.a1 * y + z2;
        z2 = c.b2 * x - c.a2 * y;
        return y;
    }
};

struct Params {
    int bandCount = 0;
    std::array<BiquadCoeffs, kMaxBands> coeffs{};
    float preampLin = 1.0f;
    float limiterThresholdLin = 0.891f;  // -1 dBFS
    // Optional output low-pass (the Bluetooth guard): listeners hearing crackle over A2DP are
    // hearing content the codec cannot reproduce, so removing it before the codec is honest.
    int guardCount = 0;
    std::array<BiquadCoeffs, 2> guard{};
};

// Peak limiter with a short lookahead: gain drops instantly when a peak *enters* the
// delay line, so it is already reduced when that peak *leaves* it; then releases smoothly.
class Limiter {
public:
    void prepare(float sampleRate, float lookaheadMs, float releaseMs) {
        size_ = std::max(1, static_cast<int>(sampleRate * lookaheadMs / 1000.0f));
        bufL_.assign(size_, 0.0f);
        bufR_.assign(size_, 0.0f);
        pos_ = 0;
        gain_ = 1.0f;
        release_ = std::exp(-1.0f / (sampleRate * releaseMs / 1000.0f));
    }

    inline void process(float& l, float& r, float threshold) {
        const float peak = std::max(std::fabs(l), std::fabs(r));
        const float target = peak > threshold ? threshold / peak : 1.0f;

        // A peak entering the delay line leaves it size_ samples later, so there is exactly
        // that long to get the gain down. Reaching it gradually instead of in one sample is
        // the difference between limiting and ticking: the gain curve stays continuous, so
        // nothing in the output jumps.
        if (target < gain_) {
            // Arm once per new requirement. Re-arming on every sample of a sustained loud
            // passage restarts the ramp each time, so it never gets there by the deadline
            // (that showed up as a 0.6 dB overshoot in the test).
            if (left_ == 0 || target < target_) {
                target_ = target;
                left_ = size_;
                step_ = (target - gain_) / static_cast<float>(size_);
            }
        }
        if (left_ > 0) {
            gain_ += step_;
            if (--left_ == 0) gain_ = target_;
        } else {
            gain_ = target + (gain_ - target) * release_;
        }
        minGain_ = std::min(minGain_, gain_);

        const float dl = bufL_[pos_];
        const float dr = bufR_[pos_];
        bufL_[pos_] = l;
        bufR_[pos_] = r;
        if (++pos_ == size_) pos_ = 0;
        l = dl * gain_;
        r = dr * gain_;
    }

    float takeMinGain() {
        const float g = minGain_;
        minGain_ = 1.0f;
        return g;
    }

private:
    std::vector<float> bufL_, bufR_;
    int size_ = 1, pos_ = 0;
    float gain_ = 1.0f, release_ = 0.999f, minGain_ = 1.0f;
    float target_ = 1.0f;   // gain the current attack ramp is heading for
    float step_ = 0.0f;     // per-sample change of that ramp
    int left_ = 0;          // samples left in that ramp (the lookahead gives us this many)
};

class Engine {
public:
    explicit Engine(float sampleRate) : sampleRate_(sampleRate) {
        limiter_.prepare(sampleRate, 1.5f, 80.0f);
    }

    /** Rate of the stream we are fed; sets up (or tears down) the resampler. */
    void setInputRate(int inRate) {
        if (inRate == inputRate_) return;
        inputRate_ = inRate;
        resampler_.configure(inRate, static_cast<int>(std::lround(sampleRate_)));
    }

    int maxOutputFrames(int inFrames) const {
        return resampler_.active() ? resampler_.maxOutputFrames(inFrames) : inFrames;
    }

    /**
     * Resamples (when needed) into [out], then runs the chain in place. Returns how many
     * frames [out] holds — the caller writes exactly that many to the AudioTrack.
     */
    int process(const float* in, int inFrames, float* out, int outCap) {
        if (resampler_.active()) {
            const int n = resampler_.process(in, inFrames, out, outCap);
            processCore(out, n);
            return n;
        }
        const int n = std::min(inFrames, outCap);
        if (out != in) std::memcpy(out, in, static_cast<size_t>(n) * 2 * sizeof(float));
        processCore(out, n);
        return n;
    }

    /** Empties the resampler's tail at the end of a track. Returns frames written. */
    int flush(float* out, int outCap) {
        if (!resampler_.active()) return 0;
        const int n = resampler_.flush(out, outCap);
        processCore(out, n);
        return n;
    }

    void setParams(const Params& p) {
        std::lock_guard<std::mutex> lock(mutex_);
        pending_ = p;
        dirty_.store(true, std::memory_order_release);
    }

    /**
     * Ramps the output up from silence over `frames`. Called whenever playback (re)starts —
     * a track, a seek, a rebuilt sink — because starting mid-waveform at full amplitude is a
     * step, and a step is a click. 15 ms is inaudible on music and long enough to hide it.
     */
    void fadeIn(int frames) {
        fadeLeft_ = std::max(1, frames);
        fadeTotal_ = fadeLeft_;
        fadeGain_ = 0.0f;
    }

    void processCore(float* interleaved, int frames) {
        if (frames <= 0) return;
        enableFlushToZero();
        pickUpParams();

        const float target = active_.preampLin;
        const float step = frames > 0 ? (target - preamp_) / static_cast<float>(frames) : 0.0f;
        const int bands = active_.bandCount;
        const int guardCount = active_.guardCount;
        const float threshold = active_.limiterThresholdLin;
        float inPeak = 0.0f, outPeak = 0.0f;

        for (int i = 0; i < frames; ++i) {
            float l = interleaved[2 * i];
            float r = interleaved[2 * i + 1];
            if (!std::isfinite(l)) l = 0.0f;
            if (!std::isfinite(r)) r = 0.0f;
            inPeak = std::max(inPeak, std::max(std::fabs(l), std::fabs(r)));

            preamp_ += step;
            double dl = static_cast<double>(l) * preamp_;
            double dr = static_cast<double>(r) * preamp_;
            for (int b = 0; b < bands; ++b) {
                dl = stateL_[b].process(active_.coeffs[b], dl);
                dr = stateR_[b].process(active_.coeffs[b], dr);
            }
            // Self-heal: a non-finite value (bad input or coefficients) would otherwise stay
            // in the filter state forever and turn everything after it into silent NaN.
            if (!std::isfinite(dl) || !std::isfinite(dr)) {
                for (int b = 0; b < bands; ++b) {
                    stateL_[b] = BiquadState{};
                    stateR_[b] = BiquadState{};
                }
                dl = 0.0;
                dr = 0.0;
                nonFinite_.fetch_add(1, std::memory_order_relaxed);
            }
            l = static_cast<float>(dl);
            r = static_cast<float>(dr);

            limiter_.process(l, r, threshold);

            if (guardCount > 0) {
                double gl = l, gr = r;
                for (int g = 0; g < guardCount; ++g) {
                    gl = guardL_[g].process(active_.guard[g], gl);
                    gr = guardR_[g].process(active_.guard[g], gr);
                }
                l = static_cast<float>(gl);
                r = static_cast<float>(gr);
            }

            l = std::clamp(l, -1.0f, 1.0f);
            r = std::clamp(r, -1.0f, 1.0f);

            if (fadeLeft_ > 0) {
                // Linear ramp 0 -> 1 over the requested window.
                fadeGain_ += 1.0f / static_cast<float>(fadeTotal_);
                if (--fadeLeft_ == 0) fadeGain_ = 1.0f;
                l *= fadeGain_;
                r *= fadeGain_;
            }

            outPeak = std::max(outPeak, std::max(std::fabs(l), std::fabs(r)));

            interleaved[2 * i] = l;
            interleaved[2 * i + 1] = r;
        }
        preamp_ = target;

        meterBlock(interleaved, frames);

        // Peak-hold meters until the UI reads them. The gain refresh keeps the min-gain
        // display honest even when the limiter never engages.
        atomicMax(inPeak_, inPeak);
        atomicMax(outPeak_, outPeak);
        atomicMin(minGain_, limiter_.takeMinGain());
    }

    // out[0] = input peak, out[1] = output peak, out[2] = min limiter gain (linear).
    /** out = [in peak, out peak, min limiter gain, true peak, click count]. */
    void readMeters(float* out) {
        out[0] = inPeak_.exchange(0.0f);
        out[1] = outPeak_.exchange(0.0f);
        out[2] = minGain_.exchange(1.0f);
        out[3] = truePeak_.exchange(0.0f);
        out[4] = static_cast<float>(clicks_.exchange(0));
    }

    float sampleRate() const { return sampleRate_; }

private:
    /** 6-tap Hann-windowed sinc at the three quarter-sample points: ~0.1 dB true-peak meter. */
    struct TruePeakKernel {
        float c[3][6]{};
        TruePeakKernel() {
            for (int j = 0; j < 3; ++j) {
                const double t = 0.25 * (j + 1);
                double sum = 0.0;
                for (int k = 0; k < 6; ++k) {
                    const double x = (k - 2) - t;
                    const double s = std::fabs(x) < 1e-9 ? 1.0 : std::sin(M_PI * x) / (M_PI * x);
                    const double w = 0.5 + 0.5 * std::cos(M_PI * x / 3.0);
                    c[j][k] = static_cast<float>(s * w);
                    sum += c[j][k];
                }
                for (int k = 0; k < 6; ++k) c[j][k] = static_cast<float>(c[j][k] / sum);
            }
        }
    };

    /**
     * Two things the peak meters cannot show: the true peak (what the DAC's reconstruction
     * filter sees between samples) and clicks. A click is a jump that is large *relative to
     * the recent signal* — a genuinely fast high-frequency signal has big jumps everywhere,
     * so an absolute threshold would flag every cymbal.
     */
    void meterBlock(const float* y, int frames) {
        if (frames <= 0) return;
        static const TruePeakKernel kernel;
        float prev = meterPrev_;
        float d1 = meterD1_;
        float env = clickEnv_;
        float tp = 0.0f;
        int clicks = 0;
        for (int i = 0; i < frames; ++i) {
            const float cur = y[2 * i];
            const float d = std::fabs(cur - prev);
            // Envelope of recent |delta|, ~5 ms decay: a click towers over it, a tone does not.
            if (meterSeen_ > 480 && d > 0.1f && d > 4.0f * env) ++clicks;
            env = std::max(d, env * 0.9958f);
            ++meterSeen_;

            if (i + 3 < frames && i >= 2) {
                const float p0 = y[2 * (i - 2)], p1 = y[2 * (i - 1)];
                const float p2 = cur, p3 = y[2 * (i + 1)];
                const float p4 = y[2 * (i + 2)], p5 = y[2 * (i + 3)];
                const float t[6] = {p0, p1, p2, p3, p4, p5};
                float m = std::max(std::fabs(p1), std::fabs(p2));
                for (int j = 0; j < 3; ++j) {
                    float acc = 0.0f;
                    for (int k = 0; k < 6; ++k) acc += kernel.c[j][k] * t[k];
                    m = std::max(m, std::fabs(acc));
                }
                tp = std::max(tp, m);
            }
            d1 = d;
            prev = cur;
        }
        meterPrev_ = prev;
        meterD1_ = d1;
        clickEnv_ = env;
        atomicMax(truePeak_, tp);
        if (clicks > 0) clicks_.fetch_add(clicks, std::memory_order_relaxed);
    }

    void pickUpParams() {
        if (!dirty_.load(std::memory_order_acquire)) return;
        if (!mutex_.try_lock()) return;  // control thread is writing; take it next block
        const int oldBands = active_.bandCount;
        const int oldGuardCount = active_.guardCount;
        active_ = pending_;
        dirty_.store(false, std::memory_order_relaxed);
        mutex_.unlock();
        // Bands that were just switched on start from a clean state.
        for (int b = oldBands; b < active_.bandCount; ++b) {
            stateL_[b] = BiquadState{};
            stateR_[b] = BiquadState{};
        }
        // Same for the output guard: re-enabling it must not ring with old samples.
        if (active_.guardCount != oldGuardCount) {
            for (int g = 0; g < 2; ++g) {
                guardL_[g] = BiquadState{};
                guardR_[g] = BiquadState{};
            }
        }
    }

    static void enableFlushToZero() {
#if defined(__aarch64__)
        uint64_t fpcr;
        asm volatile("mrs %0, fpcr" : "=r"(fpcr));
        if (!(fpcr & (1ULL << 24))) {
            fpcr |= (1ULL << 24);
            asm volatile("msr fpcr, %0" : : "r"(fpcr));
        }
#endif
    }

    static void atomicMax(std::atomic<float>& a, float v) {
        float cur = a.load(std::memory_order_relaxed);
        while (v > cur && !a.compare_exchange_weak(cur, v, std::memory_order_relaxed)) {}
    }

    static void atomicMin(std::atomic<float>& a, float v) {
        float cur = a.load(std::memory_order_relaxed);
        while (v < cur && !a.compare_exchange_weak(cur, v, std::memory_order_relaxed)) {}
    }

    const float sampleRate_;   // the rate we process (and output) at
    int inputRate_ = 0;        // the rate the decoder hands us
    Resampler resampler_;
    std::mutex mutex_;
    std::atomic<bool> dirty_{false};
    Params pending_{};
    Params active_{};

    std::array<BiquadState, kMaxBands> stateL_{}, stateR_{};
    std::array<BiquadState, 2> guardL_{}, guardR_{};
    float preamp_ = 1.0f;
    Limiter limiter_;

    std::atomic<float> inPeak_{0.0f}, outPeak_{0.0f}, minGain_{1.0f}, truePeak_{0.0f};
    std::atomic<int> clicks_{0};
    float meterPrev_ = 0.0f, meterD1_ = 0.0f, clickEnv_ = 0.0f;
    long meterSeen_ = 0;
    int fadeLeft_ = 0, fadeTotal_ = 1;
    float fadeGain_ = 1.0f;
    std::atomic<uint32_t> nonFinite_{0};  // count of self-heals, for debugging
};

}  // namespace cabin
