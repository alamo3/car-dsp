// Offline checks for the native DSP chain — no Android needed.
//
//   g++ -O2 -std=c++17 -I app/src/main/cpp tools/dsp-test.cpp -o /tmp/dt && /tmp/dt
//
// Expect: the limiter lands on its threshold without adding discontinuities, it limits on the
// inter-sample peak rather than only on samples, the fade-in reaches full scale in 15 ms, the
// true-peak meter is within ~0.2 dB of the real peak, and the click counter ignores fast high
// frequencies while catching a one-sample spike.
#include <cmath>
#include <cstdio>
#include <vector>
#include <algorithm>
#include "dsp_engine.h"
using cabin::Engine;

static std::vector<float> run(const std::vector<float>& in, float threshold, bool fade, float* metersOut) {
    const int frames = (int)(in.size() / 2), block = 256;
    std::vector<float> out(in.size(), 0.0f);
    Engine e(48000.0f);
    cabin::Params p; p.bandCount = 0; p.preampLin = 1.0f; p.limiterThresholdLin = threshold;
    e.setParams(p);
    if (fade) e.fadeIn(48000 * 15 / 1000);
    for (int f = 0; f < frames;) {
        const int n = std::min(block, frames - f);
        e.process(in.data() + f * 2, n, out.data() + f * 2, n);
        f += n;
    }
    float m[5]; e.readMeters(m);
    if (metersOut) std::copy(m, m + 5, metersOut);
    return out;
}

static double maxJump(const std::vector<float>& y) {
    double m = 0;
    for (size_t i = 1; i * 2 < y.size(); ++i) m = std::max(m, (double)std::fabs(y[2*i] - y[2*i-2]));
    return m;
}
static double peak(const std::vector<float>& y) {
    double m = 0; for (size_t i = 0; i * 2 < y.size(); ++i) m = std::max(m, (double)std::fabs(y[2*i]));
    return m;
}

int main() {
    const int fs = 48000, frames = fs;

    // 1) Limiter: ramped attack, still limits, no new discontinuities.
    std::vector<float> burst(frames * 2);
    for (int i = 0; i < frames; ++i) {
        const bool loud = (i / (fs / 20)) % 2 == 1;
        burst[2*i] = burst[2*i+1] = (float)((loud ? 0.98 : 0.05) * std::sin(2 * M_PI * (loud ? 200.0 : 8000.0) * i / fs));
    }
    float m1[5];
    auto lim = run(burst, 0.891f, false, m1);
    std::printf("limiter: out peak %.4f (threshold 0.8914)  jump in %.5f / out %.5f  min gain %.3f\n",
                peak(lim), maxJump(burst), maxJump(lim), m1[2]);

    // 2) Fade-in: first samples ramp from silence.
    std::vector<float> dc(frames * 2, 0.5f);
    float m2[5];
    auto faded = run(dc, 1.0f, true, m2);
    std::printf("fade-in: first samples %.4f %.4f %.4f ... %d frames later %.4f\n",
                faded[0], faded[2], faded[4], 720, faded[2 * 721]);

    // 3) True peak: fs/4 sine at 45 degrees -> samples peak at 0.707A while the true peak is A.
    std::vector<float> tp(frames * 2);
    for (int i = 0; i < frames; ++i) {
        const float v = (float)(0.5 * std::sin(2 * M_PI * (fs / 4.0) * i / fs + M_PI / 4));
        tp[2*i] = tp[2*i+1] = v;
    }
    float m3[5];
    auto tpo = run(tp, 1.0f, false, m3);
    std::printf("true peak: sample peak %.4f (%.2f dB) meter %.4f (%.2f dB)  <- should be ~+3 dB\n",
                peak(tpo), 20*std::log10(peak(tpo)), (double)m3[3], 20*std::log10(m3[3] + 1e-12));

    // 5) True-peak limiting: the same fs/4 tone at full scale has samples at 0.707 while its true
    //    peak is 1.0. A sample-peak limiter would pass it at 0 dBFS; the gain law must see the
    //    peak between the samples.
    std::vector<float> tpl(frames * 2);
    for (int i = 0; i < frames; ++i) {
        const float v = (float)std::sin(2 * M_PI * (fs / 4.0) * i / fs + M_PI / 4);
        tpl[2*i] = tpl[2*i+1] = v;
    }
    float m6[5];
    auto tplo = run(tpl, 0.7943f, false, m6);
    std::printf("true-peak limit: sample peak %.3f  true peak %.3f  limiter %.2f dB\n",
                peak(tplo), (double)m6[3], 20*std::log10(m6[2] + 1e-9));

    // 4) Click detector: fast HF is not a click; an injected spike is.
    std::vector<float> hf(frames * 2);
    for (int i = 0; i < frames; ++i) {
        const float v = (float)(0.5 * std::sin(2 * M_PI * 19000.0 * i / fs));
        hf[2*i] = hf[2*i+1] = v;
    }
    float m4[5];
    run(hf, 1.0f, false, m4);
    std::printf("clicks on a 19 kHz tone: %.0f (should be 0)\n", m4[4]);

    std::vector<float> spiked(frames * 2);
    for (int i = 0; i < frames; ++i) {
        float v = (float)(0.1 * std::sin(2 * M_PI * 100.0 * i / fs));
        if (i == fs / 2) v += 0.8f;                       // one-sample discontinuity
        spiked[2*i] = spiked[2*i+1] = v;
    }
    float m5[5];
    run(spiked, 1.0f, false, m5);
    std::printf("clicks on an injected spike: %.0f (should be >= 1)\n", m5[4]);

    // Pass/fail, so CI and tools/check.sh can use this as a gate rather than a printout.
    int bad = 0;
    auto check = [&](const char* what, bool ok) {
        std::printf("%-42s %s\n", what, ok ? "ok" : "FAIL");
        if (!ok) ++bad;
    };
    check("limiter lands on its threshold", peak(lim) <= 0.8914f + 2e-3f);
    check("limiter adds no discontinuity", maxJump(lim) <= maxJump(burst) + 1e-4);
    check("fade-in reaches full scale in 15 ms", std::fabs(faded[2*721] - 0.5f) < 1e-3f);
    check("true peak within 0.3 dB of ideal",
          std::fabs(20*std::log10((double)m3[3] / 0.5)) < 0.3);
    check("limiter holds the true peak below full scale", m6[3] < 1.0f);
    check("true-peak limiter engages on a full-scale fs/4 tone", m6[2] < 0.98f);
    check("no clicks on a fast tone", m4[4] == 0);
    check("click detector catches a spike", m5[4] >= 1);
    return bad == 0 ? 0 : 1;
}
