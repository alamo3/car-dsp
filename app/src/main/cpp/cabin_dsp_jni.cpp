// JNI bridge for dev.alamo3.cabineq.NativeDsp
#include <jni.h>

#include "dsp_engine.h"

using cabin::Engine;

static inline Engine* engine(jlong h) { return reinterpret_cast<Engine*>(h); }

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_alamo3_cabineq_NativeDsp_create(JNIEnv*, jobject, jint sampleRate) {
    return reinterpret_cast<jlong>(new Engine(static_cast<float>(sampleRate)));
}

JNIEXPORT void JNICALL
Java_dev_alamo3_cabineq_NativeDsp_destroy(JNIEnv*, jobject, jlong h) {
    delete engine(h);
}

// coeffs: bandCount * 5 doubles, each band (b0, b1, b2, a1, a2), already normalised by a0.
JNIEXPORT void JNICALL
Java_dev_alamo3_cabineq_NativeDsp_setParams(JNIEnv* env, jobject, jlong h, jdoubleArray coeffs,
                                           jint bandCount, jfloat preampLin, jfloat limiterThresholdLin,
                                           jdoubleArray guardCoeffs, jint guardCount) {
    cabin::Params p;
    p.bandCount = std::clamp(static_cast<int>(bandCount), 0, cabin::kMaxBands);
    p.preampLin = preampLin;
    p.limiterThresholdLin = limiterThresholdLin;

    const jsize len = env->GetArrayLength(coeffs);
    if (len < p.bandCount * 5) p.bandCount = len / 5;
    jdouble* c = env->GetDoubleArrayElements(coeffs, nullptr);
    for (int b = 0; b < p.bandCount; ++b) {
        p.coeffs[b] = cabin::BiquadCoeffs{c[5 * b], c[5 * b + 1], c[5 * b + 2], c[5 * b + 3], c[5 * b + 4]};
    }
    env->ReleaseDoubleArrayElements(coeffs, c, JNI_ABORT);

    if (guardCoeffs != nullptr) {
        const jsize glen = env->GetArrayLength(guardCoeffs);
        p.guardCount = std::clamp(static_cast<int>(guardCount), 0, 2);
        if (glen < p.guardCount * 5) p.guardCount = glen / 5;
        if (p.guardCount > 0) {
            jdouble* g = env->GetDoubleArrayElements(guardCoeffs, nullptr);
            for (int i = 0; i < p.guardCount; ++i) {
                p.guard[i] = cabin::BiquadCoeffs{g[5 * i], g[5 * i + 1], g[5 * i + 2], g[5 * i + 3], g[5 * i + 4]};
            }
            env->ReleaseDoubleArrayElements(guardCoeffs, g, JNI_ABORT);
        }
    }

    engine(h)->setParams(p);
}

// Rate of the incoming stream. Equal to the engine's rate means no resampling at all.
JNIEXPORT void JNICALL
Java_dev_alamo3_cabineq_NativeDsp_setInputRate(JNIEnv*, jobject, jlong h, jint inRate) {
    engine(h)->setInputRate(static_cast<int>(inRate));
}

// Upper bound on the frames process() can return for `inFrames` input frames.
JNIEXPORT jint JNICALL
Java_dev_alamo3_cabineq_NativeDsp_maxFrames(JNIEnv*, jobject, jlong h, jint inFrames) {
    return static_cast<jint>(engine(h)->maxOutputFrames(static_cast<int>(inFrames)));
}

// in: interleaved stereo float from the decoder. out: processed (and resampled) stereo float.
// Returns the frames written to `out`, which the caller writes to the AudioTrack.
JNIEXPORT jint JNICALL
Java_dev_alamo3_cabineq_NativeDsp_process(JNIEnv* env, jobject, jlong h, jfloatArray in,
                                          jint inFrames, jfloatArray out, jint outFrames) {
    auto* src = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(in, nullptr));
    if (src == nullptr) return 0;
    auto* dst = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(out, nullptr));
    if (dst == nullptr) {
        env->ReleasePrimitiveArrayCritical(in, src, JNI_ABORT);
        return 0;
    }
    const int n = engine(h)->process(src, static_cast<int>(inFrames), dst, static_cast<int>(outFrames));
    env->ReleasePrimitiveArrayCritical(out, dst, 0);
    env->ReleasePrimitiveArrayCritical(in, src, JNI_ABORT);
    return n;
}

// End of track: push the resampler's look-ahead through the chain.
JNIEXPORT jint JNICALL
Java_dev_alamo3_cabineq_NativeDsp_flush(JNIEnv* env, jobject, jlong h, jfloatArray out, jint outFrames) {
    auto* dst = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(out, nullptr));
    if (dst == nullptr) return 0;
    const int n = engine(h)->flush(dst, static_cast<int>(outFrames));
    env->ReleasePrimitiveArrayCritical(out, dst, 0);
    return n;
}

// Ramps the output up from silence over `frames` (track start, seek, rebuilt sink).
JNIEXPORT void JNICALL
Java_dev_alamo3_cabineq_NativeDsp_fadeIn(JNIEnv*, jobject, jlong h, jint frames) {
    engine(h)->fadeIn(static_cast<int>(frames));
}

JNIEXPORT void JNICALL
Java_dev_alamo3_cabineq_NativeDsp_readMeters(JNIEnv* env, jobject, jlong h, jfloatArray out) {
    float m[5];
    engine(h)->readMeters(m);
    env->SetFloatArrayRegion(out, 0, 5, m);
}

}  // extern "C"
