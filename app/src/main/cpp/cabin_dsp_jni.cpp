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
                                           jint bandCount, jfloat preampLin, jfloat limiterThresholdLin) {
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
    engine(h)->setParams(p);
}

// buffer: interleaved stereo float, processed in place.
JNIEXPORT void JNICALL
Java_dev_alamo3_cabineq_NativeDsp_process(JNIEnv* env, jobject, jlong h, jfloatArray buffer, jint frames) {
    auto* data = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(buffer, nullptr));
    if (data == nullptr) return;
    engine(h)->process(data, frames);
    env->ReleasePrimitiveArrayCritical(buffer, data, 0);
}

JNIEXPORT void JNICALL
Java_dev_alamo3_cabineq_NativeDsp_readMeters(JNIEnv* env, jobject, jlong h, jfloatArray out) {
    float m[3];
    engine(h)->readMeters(m);
    env->SetFloatArrayRegion(out, 0, 3, m);
}

}  // extern "C"
