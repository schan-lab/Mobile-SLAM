// JNI bridge for the ROS 2 bag (MCAP) writer and player (rosbag/).
#include <jni.h>

#include <cstring>

#include "rosbag/rosbag.h"

namespace {

JavaVM* g_vm = nullptr;

/// Detaches the player thread from the VM when it exits.
struct ThreadGuard {
    bool attached = false;
    ~ThreadGuard() {
        if (attached && g_vm) g_vm->DetachCurrentThread();
    }
};
thread_local ThreadGuard t_guard;

JNIEnv* envForThread() {
    JNIEnv* env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    t_guard.attached = true;
    return env;
}

/// Calls RosbagPlayer.onPacket(type, buf, len) on the Kotlin object for each converted packet.
struct PlayerCtx {
    jobject target = nullptr;    // global ref
    jmethodID onPacket = nullptr;
    jbyteArray buf = nullptr;    // global ref, reused for every packet
    jint cap = 0;
    RosbagPlayerHandle player = nullptr;
};

void onPacket(void* ctx, int32_t type, const uint8_t* data, int32_t len) {
    auto* c = static_cast<PlayerCtx*>(ctx);
    JNIEnv* env = envForThread();
    if (!env) return;
    if (len > c->cap) {
        if (c->buf) env->DeleteGlobalRef(c->buf);
        jbyteArray local = env->NewByteArray(len);
        c->buf = static_cast<jbyteArray>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);
        c->cap = len;
    }
    env->SetByteArrayRegion(c->buf, 0, len, reinterpret_cast<const jbyte*>(data));
    env->CallVoidMethod(c->target, c->onPacket, type, c->buf, len);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

jstring str(JNIEnv* env, const char* s) { return env->NewStringUTF(s); }

/// BagStatus(state, progress, elapsed, duration, pointMsgs, imuMsgs, lidarTopic, imuTopic, error)
jobject toStatus(JNIEnv* env, const RosbagStatus& s) {
    jclass cls = env->FindClass("com/example/mobilendt/BagStatus");
    if (!cls) return nullptr;
    jmethodID ctor = env->GetMethodID(cls, "<init>",
        "(IDDDJJLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    jobject o = env->NewObject(cls, ctor, s.state, s.progress, s.elapsed, s.duration, jlong(s.point_msgs),
                               jlong(s.imu_msgs), str(env, s.lidar_topic), str(env, s.imu_topic), str(env, s.error));
    env->DeleteLocalRef(cls);
    return o;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

// ------------------------------------------------------------------ writer

JNIEXPORT jlong JNICALL Java_com_example_mobilendt_RosbagRecorder_nativeOpen(JNIEnv* env, jclass, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    RosbagWriterHandle h = rosbag_writer_open(p);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL Java_com_example_mobilendt_RosbagRecorder_nativePush(JNIEnv* env, jclass, jlong h, jint type,
                                                                           jbyteArray arr, jint len) {
    jbyte* p = env->GetByteArrayElements(arr, nullptr);
    rosbag_writer_push(reinterpret_cast<RosbagWriterHandle>(h), type, reinterpret_cast<const uint8_t*>(p), len);
    env->ReleaseByteArrayElements(arr, p, JNI_ABORT);
}

JNIEXPORT jlong JNICALL Java_com_example_mobilendt_RosbagRecorder_nativeBytes(JNIEnv*, jclass, jlong h) {
    return rosbag_writer_bytes(reinterpret_cast<RosbagWriterHandle>(h));
}

JNIEXPORT void JNICALL Java_com_example_mobilendt_RosbagRecorder_nativeClose(JNIEnv*, jclass, jlong h) {
    rosbag_writer_close(reinterpret_cast<RosbagWriterHandle>(h));
}

// ------------------------------------------------------------------ player

JNIEXPORT jobject JNICALL Java_com_example_mobilendt_RosbagPlayer_nativeProbe(JNIEnv* env, jclass, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    RosbagStatus s;
    rosbag_probe(p, &s);
    env->ReleaseStringUTFChars(path, p);
    return toStatus(env, s);
}

JNIEXPORT jlong JNICALL Java_com_example_mobilendt_RosbagPlayer_nativeStart(JNIEnv* env, jobject self, jstring path,
                                                                           jdouble speed) {
    auto* c = new PlayerCtx();
    c->target = env->NewGlobalRef(self);
    jclass cls = env->GetObjectClass(self);
    c->onPacket = env->GetMethodID(cls, "onPacket", "(I[BI)V");
    env->DeleteLocalRef(cls);
    const char* p = env->GetStringUTFChars(path, nullptr);
    c->player = rosbag_play_start(p, speed, onPacket, c);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(c);
}

JNIEXPORT jobject JNICALL Java_com_example_mobilendt_RosbagPlayer_nativeStatus(JNIEnv* env, jclass, jlong h) {
    RosbagStatus s;
    rosbag_play_status(reinterpret_cast<PlayerCtx*>(h)->player, &s);
    return toStatus(env, s);
}

/// Joins the player thread (no callbacks after this returns) and frees everything.
JNIEXPORT void JNICALL Java_com_example_mobilendt_RosbagPlayer_nativeStop(JNIEnv* env, jclass, jlong h) {
    auto* c = reinterpret_cast<PlayerCtx*>(h);
    rosbag_play_stop(c->player);
    env->DeleteGlobalRef(c->target);
    if (c->buf) env->DeleteGlobalRef(c->buf);
    delete c;
}

}  // extern "C"
