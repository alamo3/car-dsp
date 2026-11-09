// Offline check for src/main/cpp/resampler.h — no Android needed.
//
//   g++ -O2 -std=c++17 -I app/src/main/cpp tools/resampler-test.cpp -o /tmp/rt && /tmp/rt
//
// Feeds sines through the resampler block by block (like the player does) and reports the
// signal-to-error ratio against an ideal sine, at several frequencies and block sizes.
// Expect ~120 dB in the pass-band; anything near 30 dB means the kernel or the phase
// advance is broken.  tools/resampler-response.cpp does the same for the stop-band.
#include <cmath>
#include <cstdio>
#include <vector>
#include "resampler.h"
using cabin::Resampler;

static double snr(const std::vector<float>& y, int rate, double freq, size_t from, size_t to) {
    const double w = 2 * M_PI * freq / rate;
    double ss=0, sc=0, s1=0, c1=0, yy=0; size_t n=0;
    for (size_t i = from; i < to; ++i) {
        const double s = std::sin(w*i), c = std::cos(w*i);
        ss += s*s; sc += s*c; s1 += s*y[2*i]; c1 += c*y[2*i]; ++n;
    }
    const double det = ss*(double)n - sc*sc;
    const double a = (s1*(double)n - c1*sc)/det, b = (c1*ss - s1*sc)/det;
    for (size_t i = from; i < to; ++i) {
        const double e = y[2*i] - (a*std::sin(w*i) + b*std::cos(w*i));
        yy += e*e;
    }
    const double sig = (a*a + b*b) * (double)n * 0.5;
    return 10*std::log10(sig/(yy + 1e-300));
}

static std::vector<float> run(int inRate, int outRate, const std::vector<float>& in, int block) {
    Resampler rs; rs.configure(inRate, outRate);
    const size_t frames = in.size()/2;
    std::vector<float> out(frames*3*2 + 8192, 0.0f);
    size_t written = 0, f = 0;
    while (f < frames) {
        const int n = (int)std::min<size_t>(block, frames - f);
        const int got = rs.process(in.data() + f*2, n, out.data() + written, 8192);
        written += (size_t)got*2; f += (size_t)n;
    }
    const int tail = rs.flush(out.data() + written, 8192);
    written += (size_t)tail*2;
    out.resize(written);
    return out;
}

int main() {
    for (int block : {1, 37, 512, 1024, 4096}) {
        for (double f : {100.0, 1000.0, 10000.0, 19000.0}) {
            const int inRate = 44100, outRate = 48000, frames = 44100;
            std::vector<float> in(frames*2);
            for (int i = 0; i < frames; ++i) {
                const float v = 0.5f*(float)std::sin(2*M_PI*f*i/inRate);
                in[2*i] = v; in[2*i+1] = v;
            }
            auto out = run(inRate, outRate, in, block);
            const size_t n = out.size()/2;
            std::printf("block %4d  %6.0f Hz  frames %zu (expect %d)  SNR %6.1f dB\n",
                        block, f, n, frames*48000/44100, snr(out, outRate, f, 300, n-300));
        }
    }
    return 0;
}
