// Exact-rational polyphase resampler: Kaiser-windowed sinc, 128 taps per phase.
//
// The phase count is outRate/gcd(in, out), so every output sample lands on an exact phase
// index — no interpolated phases (timing jitter), no drifting double accumulator. The usual
// ratios are small: 44.1 -> 48 is L=160, 44.1 -> 96 is L=320, 88.2 -> 48 is L=160.
//
// Quality with kTaps = 128 and beta = 10: about 96 dB stop-band, pass-band flat to roughly
// 0.45 * fs_in (21 kHz for a 44.1 kHz source), transition band around 2 kHz. Cost is
// kTaps multiply-adds per output sample per channel — ~12 M MAC/s for stereo at 48 kHz.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

namespace cabin {

class Resampler {
public:
    static constexpr int kTaps = 128;
    /** Half the filter's transition width, in cycles per input sample (kTaps = 128). */
    static constexpr double kTransition = 0.024;

    bool active() const { return active_; }

    /**
     * Called from the player thread when a stream starts (or the output rate changes).
     * A matching rate — or a ratio with an unreasonable phase count — leaves it inactive,
     * and the engine then passes samples through untouched.
     */
    void configure(int inRate, int outRate) {
        active_ = false;
        reset();
        inRate_ = outRate_ = L_ = M_ = 0;
        if (inRate <= 0 || outRate <= 0 || inRate == outRate) return;
        const int g = gcd(inRate, outRate);
        L_ = outRate / g;
        M_ = inRate / g;
        if (L_ > 4096) {   // not a real audio ratio; leave the stream alone
            L_ = M_ = 0;
            return;
        }
        inRate_ = inRate;
        outRate_ = outRate;
        buildTable();
        prefill(kTaps / 2);
        active_ = true;
    }

    /** Upper bound of frames [process] can produce for `inFrames` input frames. */
    int maxOutputFrames(int inFrames) const {
        if (!active_) return inFrames;
        const double ratio = static_cast<double>(L_) / static_cast<double>(M_);
        return static_cast<int>(std::ceil(inFrames * ratio)) + 4;
    }

    /** Resamples a block. Returns the number of output frames written to [out]. */
    int process(const float* in, int inFrames, float* out, int outCap) {
        if (!active_) return 0;
        if (inFrames > 0) append(in, inFrames);
        int produced = 0;
        while (produced < outCap) {
            // The kernel needs kTaps/2 samples of look-ahead; without them, wait.
            if (base_ + kTaps / 2 >= start_ + frames_) break;
            const float* coeff = &table_[static_cast<size_t>(phase_) * kTaps];
            const int64_t first = base_ - kTaps / 2 + 1;
            const float* s = &buf_[static_cast<size_t>(first - start_) * 2];
            double l = 0.0, r = 0.0;
            for (int k = 0; k < kTaps; ++k) {
                const float c = coeff[k];
                l += c * s[2 * k];
                r += c * s[2 * k + 1];
            }
            out[2 * produced] = static_cast<float>(l);
            out[2 * produced + 1] = static_cast<float>(r);
            ++produced;
            // Advance by M/L input samples, exactly: integer part and phase index.
            phase_ += M_;
            base_ += phase_ / L_;
            phase_ %= L_;
        }
        compact();
        return produced;
    }

    /** Pushes zeros through the kernel so the tail of the stream is not truncated. */
    int flush(float* out, int outCap) {
        if (!active_) return 0;
        const std::vector<float> zeros(static_cast<size_t>(kTaps) * 2, 0.0f);
        const int n = process(zeros.data(), kTaps, out, outCap);
        reset();
        return n;
    }

    void reset() {
        buf_.clear();
        frames_ = 0;
        start_ = 0;
        base_ = 0;
        phase_ = 0;
    }

private:
    static int gcd(int a, int b) {
        while (b != 0) {
            const int t = a % b;
            a = b;
            b = t;
        }
        return a < 0 ? -a : a;
    }

    static double besselI0(double x) {
        // Series expansion; accurate enough for a Kaiser window and cheap to evaluate.
        double sum = 1.0, term = 1.0;
        const double q = x * x / 4.0;
        for (int k = 1; k < 32; ++k) {
            term *= q / (static_cast<double>(k) * k);
            sum += term;
            if (term < 1e-16 * sum) break;
        }
        return sum;
    }

    void buildTable() {
        table_.assign(static_cast<size_t>(L_) * kTaps, 0.0f);
        const double ratio = static_cast<double>(outRate_) / inRate_;
        // Cutoff in cycles per input sample, placed so the *stop-band* starts exactly at the
        // lower of the two Nyquists: a Kaiser window of kTaps taps at ~96 dB needs about
        // kTransition cycles of transition, so half of it comes off the cutoff. Anything
        // between the cutoff and the Nyquist is what would otherwise alias.
        const double fc = std::max(0.02, 0.5 * std::min(1.0, ratio) - kTransition);
        const double beta = 10.0;
        const double i0b = besselI0(beta);
        std::vector<double> row(kTaps);
        for (int p = 0; p < L_; ++p) {
            const double frac = static_cast<double>(p) / static_cast<double>(L_);
            double sum = 0.0;
            for (int k = 0; k < kTaps; ++k) {
                const double t = (k - kTaps / 2 + 1) - frac;
                const double x = 2.0 * fc * t;
                const double sinc = std::fabs(x) < 1e-12 ? 1.0 : std::sin(M_PI * x) / (M_PI * x);
                const double u = t / (kTaps / 2.0);
                const double w = std::fabs(u) >= 1.0
                                     ? 0.0
                                     : besselI0(beta * std::sqrt(1.0 - u * u)) / i0b;
                row[k] = sinc * w;
                sum += row[k];
            }
            if (std::fabs(sum) < 1e-12) sum = 1.0;
            float* dst = &table_[static_cast<size_t>(p) * kTaps];
            for (int k = 0; k < kTaps; ++k) dst[k] = static_cast<float>(row[k] / sum);
        }
    }

    /** Kernels look backwards, so start with silence in front of the first real sample. */
    void prefill(int frames) {
        buf_.assign(static_cast<size_t>(frames) * 2, 0.0f);
        frames_ = frames;
        start_ = -frames;
        base_ = 0;
        phase_ = 0;
    }

    void append(const float* in, int n) {
        const size_t need = static_cast<size_t>(frames_ + n) * 2;
        if (buf_.size() < need) buf_.resize(need);
        std::memcpy(buf_.data() + static_cast<size_t>(frames_) * 2, in,
                    static_cast<size_t>(n) * 2 * sizeof(float));
        frames_ += n;
    }

    void compact() {
        const int64_t firstNeeded = base_ - kTaps / 2 + 1;
        const int64_t drop = firstNeeded - start_;
        if (drop <= 0 || drop >= frames_) return;
        std::memmove(buf_.data(), buf_.data() + static_cast<size_t>(drop) * 2,
                     static_cast<size_t>(frames_ - drop) * 2 * sizeof(float));
        frames_ -= static_cast<int>(drop);
        start_ += drop;
    }

    bool active_ = false;
    int inRate_ = 0, outRate_ = 0;
    int L_ = 0, M_ = 0;          // output phases per M input samples
    std::vector<float> table_;   // L_ * kTaps
    std::vector<float> buf_;     // interleaved stereo input history + pending
    int frames_ = 0;             // valid frames in buf_
    int64_t start_ = 0;          // absolute input index of buf_[0]
    int64_t base_ = 0;           // absolute input index of the next output sample
    int phase_ = 0;              // fractional position of that sample, in 1/L_ steps
};

}  // namespace cabin
