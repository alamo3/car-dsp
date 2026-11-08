// CabinEQ native DSP engine.
//
// Signal chain (interleaved stereo float, in place):
//   preamp (smoothed) -> N biquads (EQ: shelves + peaking, designed in Kotlin)
//   -> lookahead peak limiter -> hard safety clip at 0 dBFS
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

namespace cabin {

constexpr int kMaxBands = 16;

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
        gain_ = target < gain_ ? target : target + (gain_ - target) * release_;
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
};

class Engine {
public:
    explicit Engine(float sampleRate) : sampleRate_(sampleRate) {
        limiter_.prepare(sampleRate, 1.5f, 80.0f);
    }

    void setParams(const Params& p) {
        std::lock_guard<std::mutex> lock(mutex_);
        pending_ = p;
        dirty_.store(true, std::memory_order_release);
    }

    void process(float* interleaved, int frames) {
        enableFlushToZero();
        pickUpParams();

        const float target = active_.preampLin;
        const float step = frames > 0 ? (target - preamp_) / static_cast<float>(frames) : 0.0f;
        const int bands = active_.bandCount;
        const float threshold = active_.limiterThresholdLin;
        float inPeak = 0.0f, outPeak = 0.0f;

        for (int i = 0; i < frames; ++i) {
            float l = interleaved[2 * i];
            float r = interleaved[2 * i + 1];
            inPeak = std::max(inPeak, std::max(std::fabs(l), std::fabs(r)));

            preamp_ += step;
            double dl = static_cast<double>(l) * preamp_;
            double dr = static_cast<double>(r) * preamp_;
            for (int b = 0; b < bands; ++b) {
                dl = stateL_[b].process(active_.coeffs[b], dl);
                dr = stateR_[b].process(active_.coeffs[b], dr);
            }
            l = static_cast<float>(dl);
            r = static_cast<float>(dr);

            limiter_.process(l, r, threshold);
            l = std::clamp(l, -1.0f, 1.0f);
            r = std::clamp(r, -1.0f, 1.0f);
            outPeak = std::max(outPeak, std::max(std::fabs(l), std::fabs(r)));

            interleaved[2 * i] = l;
            interleaved[2 * i + 1] = r;
        }
        preamp_ = target;

        // Peak-hold meters until the UI reads them.
        atomicMax(inPeak_, inPeak);
        atomicMax(outPeak_, outPeak);
        atomicMin(minGain_, limiter_.takeMinGain());
    }

    // out[0] = input peak, out[1] = output peak, out[2] = min limiter gain (linear).
    void readMeters(float* out) {
        out[0] = inPeak_.exchange(0.0f);
        out[1] = outPeak_.exchange(0.0f);
        out[2] = minGain_.exchange(1.0f);
    }

    float sampleRate() const { return sampleRate_; }

private:
    void pickUpParams() {
        if (!dirty_.load(std::memory_order_acquire)) return;
        if (!mutex_.try_lock()) return;  // control thread is writing; take it next block
        const int oldBands = active_.bandCount;
        active_ = pending_;
        dirty_.store(false, std::memory_order_relaxed);
        mutex_.unlock();
        // Bands that were just switched on start from a clean state.
        for (int b = oldBands; b < active_.bandCount; ++b) {
            stateL_[b] = BiquadState{};
            stateR_[b] = BiquadState{};
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

    const float sampleRate_;
    std::mutex mutex_;
    std::atomic<bool> dirty_{false};
    Params pending_{};
    Params active_{};

    std::array<BiquadState, kMaxBands> stateL_{}, stateR_{};
    float preamp_ = 1.0f;
    Limiter limiter_;

    std::atomic<float> inPeak_{0.0f}, outPeak_{0.0f}, minGain_{1.0f};
};

}  // namespace cabin
