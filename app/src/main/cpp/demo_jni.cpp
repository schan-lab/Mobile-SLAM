// In-app demo: synthetic Mid-360 packets (Mid360Synth.hpp). The pacing thread lives in DemoSource.kt.
#include <jni.h>

#include <algorithm>
#include <atomic>

#include "Mid360Synth.hpp"

namespace {
struct Demo {
    mndt::Mid360Synth synth;
    std::atomic<bool> restart{false};
};
Demo* D(jlong h) { return reinterpret_cast<Demo*>(h); }
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_example_mobilendt_DemoSource_nativeCreate(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new Demo());
}

JNIEXPORT void JNICALL Java_com_example_mobilendt_DemoSource_nativeDestroy(JNIEnv*, jclass, jlong h) { delete D(h); }

JNIEXPORT void JNICALL Java_com_example_mobilendt_DemoSource_nativeRestartMotion(JNIEnv*, jclass, jlong h) {
    D(h)->restart = true;
}

/// Writes the next packet (point or IMU, whichever is due first) into buf and returns its sensor time (s).
/// The packet type is buf[10] (0 = IMU, 1 = points) and its length is bytes 1-2, as in a real packet.
JNIEXPORT jdouble JNICALL Java_com_example_mobilendt_DemoSource_nativeNext(JNIEnv* env, jclass, jlong h,
                                                                          jbyteArray buf) {
    Demo* d = D(h);
    if (env->GetArrayLength(buf) < mndt::Mid360Synth::kPointPacketSize) return -1;
    auto& s = d->synth;
    const double t = std::min(s.nextPointTime(), s.nextImuTime());
    if (d->restart.exchange(false)) s.restartMotion(t);
    jbyte* p = env->GetByteArrayElements(buf, nullptr);
    if (s.nextImuTime() <= s.nextPointTime()) s.imuPacket(reinterpret_cast<uint8_t*>(p));
    else s.pointPacket(reinterpret_cast<uint8_t*>(p));
    env->ReleaseByteArrayElements(buf, p, 0);
    return t;
}

}  // extern "C"
