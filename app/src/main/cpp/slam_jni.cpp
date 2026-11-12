// JNI bridge: packet queue + processing thread around mndt::Lio.
#include <android/log.h>
#include <jni.h>

#include <atomic>
#include <condition_variable>
#include <deque>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#include "lio.hpp"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "Mid360Slam", __VA_ARGS__)

namespace {

struct Packet { int type; std::vector<uint8_t> data; };

class Engine {
public:
    Engine() : worker_([this] { run(); }) {}
    ~Engine() {
        {
            std::lock_guard<std::mutex> l(mtx_);
            quit_ = true;
        }
        cv_.notify_all();
        worker_.join();
    }

    void start(int mode) {
        std::lock_guard<std::mutex> l(mtx_);
        queue_.clear();
        mode_ = mode;
        reset_ = true;
        running_ = true;
        dropped_pkts_ = 0;
    }
    void stop() {
        std::lock_guard<std::mutex> l(mtx_);
        running_ = false;
        queue_.clear();
    }
    bool running() const { return running_; }

    void push(int type, const uint8_t* buf, int len) {
        if (!running_) return;
        {
            std::lock_guard<std::mutex> l(mtx_);
            // If processing falls behind (~1 s backlog) drop point packets, but always keep IMU.
            if (type == 1 && queue_.size() > 2500) { ++dropped_pkts_; return; }
            queue_.push_back({type, std::vector<uint8_t>(buf, buf + len)});
        }
        cv_.notify_one();
    }

    void setPriorMap(const std::vector<Eigen::Vector3f>& pts, const std::vector<float>& inten) {
        std::lock_guard<std::mutex> ll(lio_mtx_);
        lio.setPriorMap(pts, inten);
    }

    mndt::Lio lio;
    std::mutex lio_mtx_;   // held while the worker mutates lio (processing / reset / prior map)
    std::atomic<int> mode_{0};
    std::atomic<long> dropped_pkts_{0};
    std::atomic<int> queue_len_{0};

private:
    void run() {
        std::vector<Packet> batch;
        while (true) {
            {
                std::unique_lock<std::mutex> l(mtx_);
                cv_.wait(l, [this] { return quit_ || !queue_.empty() || reset_; });
                if (quit_) return;
                if (reset_) {
                    std::lock_guard<std::mutex> ll(lio_mtx_);
                    lio.setMode(mode_ == 1 ? mndt::LioMode::Localization : mndt::LioMode::Mapping);
                    lio.reset();
                    reset_ = false;
                }
                batch.clear();
                while (!queue_.empty() && batch.size() < 256) {
                    batch.push_back(std::move(queue_.front()));
                    queue_.pop_front();
                }
                queue_len_ = static_cast<int>(queue_.size());
            }
            std::lock_guard<std::mutex> ll(lio_mtx_);
            for (auto& p : batch) {
                lio.addPacket(p.type, p.data.data(), static_cast<int>(p.data.size()));
                while (lio.step()) {}
            }
        }
    }

    std::mutex mtx_;
    std::condition_variable cv_;
    std::deque<Packet> queue_;
    bool quit_ = false, reset_ = false;
    std::atomic<bool> running_{false};
    std::thread worker_;
};

Engine* E(jlong h) { return reinterpret_cast<Engine*>(h); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_example_mobilendt_SlamNative_create(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new Engine());
}

JNIEXPORT void JNICALL Java_com_example_mobilendt_SlamNative_destroy(JNIEnv*, jclass, jlong h) { delete E(h); }

JNIEXPORT void JNICALL Java_com_example_mobilendt_SlamNative_start(JNIEnv*, jclass, jlong h, jint mode) {
    LOGI("start mode=%d", mode);
    E(h)->start(mode);
}

/// Switches mode while stopped (clears state; in localisation the prior map becomes the display map).
JNIEXPORT void JNICALL Java_com_example_mobilendt_SlamNative_prepare(JNIEnv*, jclass, jlong h, jint mode) {
    Engine* e = E(h);
    e->mode_ = mode;
    std::lock_guard<std::mutex> ll(e->lio_mtx_);
    e->lio.setMode(mode == 1 ? mndt::LioMode::Localization : mndt::LioMode::Mapping);
    e->lio.reset();
}

/// Initial guess for localisation. If running, triggers a new search from it.
JNIEXPORT void JNICALL Java_com_example_mobilendt_SlamNative_setInitialPose(JNIEnv*, jclass, jlong h, jfloat x,
                                                                           jfloat y, jfloat z, jfloat yaw,
                                                                           jboolean hasYaw) {
    Engine* e = E(h);
    std::lock_guard<std::mutex> ll(e->lio_mtx_);
    e->lio.setInitialGuess(mndt::Vec3(x, y, z), yaw, !hasYaw);
    e->lio.requestRelocalize();
    LOGI("initial pose %.2f %.2f %.2f yaw %.1f deg (search %d)", x, y, z, yaw * 57.2958, !hasYaw);
}

JNIEXPORT jboolean JNICALL Java_com_example_mobilendt_SlamNative_setPriorMap(JNIEnv* env, jclass, jlong h,
                                                                            jfloatArray xyz, jfloatArray inten,
                                                                            jint n) {
    std::vector<Eigen::Vector3f> pts(n);
    env->GetFloatArrayRegion(xyz, 0, n * 3, reinterpret_cast<float*>(pts.data()));
    std::vector<float> in;
    if (inten != nullptr) {
        in.resize(n);
        env->GetFloatArrayRegion(inten, 0, n, in.data());
    }
    E(h)->setPriorMap(pts, in);
    LOGI("prior map: %d points", n);
    return E(h)->lio.hasPriorMap();
}

/// Points closer than this (m) are ignored by SLAM / localisation. Applies from the next scan.
JNIEXPORT void JNICALL Java_com_example_mobilendt_SlamNative_setBlind(JNIEnv*, jclass, jlong h, jfloat m) {
    Engine* e = E(h);
    std::lock_guard<std::mutex> ll(e->lio_mtx_);
    e->lio.setBlind(m);
    LOGI("blind %.2f m", m);
}

JNIEXPORT void JNICALL Java_com_example_mobilendt_SlamNative_stop(JNIEnv*, jclass, jlong h) {
    LOGI("stop");
    E(h)->stop();
}

JNIEXPORT void JNICALL Java_com_example_mobilendt_SlamNative_push(JNIEnv* env, jclass, jlong h, jint type,
                                                                  jbyteArray arr, jint len) {
    Engine* e = E(h);
    if (!e->running()) return;
    jbyte* p = env->GetByteArrayElements(arr, nullptr);
    e->push(type, reinterpret_cast<const uint8_t*>(p), len);
    env->ReleaseByteArrayElements(arr, p, JNI_ABORT);
}

/// out: [status, scans, dropped_scans, dropped_pkts, queue, last_ms, avg_ms, effective, ds_points,
///       map_size, vis_size, px, py, pz, yaw, degenerate, running,
///       mode, loc_state, match, ndt_ms, has_prior]
JNIEXPORT void JNICALL Java_com_example_mobilendt_SlamNative_status(JNIEnv* env, jclass, jlong h,
                                                                    jdoubleArray out) {
    Engine* e = E(h);
    auto st = e->lio.stats();
    mndt::Mat3 R;
    mndt::Vec3 p;
    e->lio.pose(R, p);
    double yaw = std::atan2(R(1, 0), R(0, 0));
    double v[22] = {double(static_cast<int>(e->lio.status())), double(st.scans), double(st.dropped_scans),
                    double(e->dropped_pkts_.load()), double(e->queue_len_.load()), st.last_ms, st.avg_ms,
                    double(st.effective), double(st.ds_points), double(st.map_size), double(st.vis_size),
                    p.x(), p.y(), p.z(), yaw, double(st.degenerate), e->running() ? 1.0 : 0.0,
                    double(e->mode_.load()), double(st.loc_state), st.match, st.ndt_ms,
                    e->lio.hasPriorMap() ? 1.0 : 0.0};
    env->SetDoubleArrayRegion(out, 0, 22, v);
}

JNIEXPORT jint JNICALL Java_com_example_mobilendt_SlamNative_map(JNIEnv* env, jclass, jlong h, jfloatArray out,
                                                                 jint maxPts) {
    std::vector<float> tmp(size_t(maxPts) * 4);   // x, y, z, intensity
    int n = E(h)->lio.mapSnapshot(tmp.data(), maxPts);
    env->SetFloatArrayRegion(out, 0, n * 4, tmp.data());
    return n;
}

JNIEXPORT jint JNICALL Java_com_example_mobilendt_SlamNative_trajectory(JNIEnv* env, jclass, jlong h,
                                                                        jfloatArray out, jint maxPts) {
    auto tr = E(h)->lio.trajectory();
    size_t n = tr.size();
    size_t start = n > size_t(maxPts) ? n - maxPts : 0;
    std::vector<float> tmp;
    tmp.reserve((n - start) * 3);
    for (size_t i = start; i < n; ++i) { tmp.push_back(tr[i].x()); tmp.push_back(tr[i].y()); tmp.push_back(tr[i].z()); }
    env->SetFloatArrayRegion(out, 0, static_cast<jsize>(tmp.size()), tmp.data());
    return static_cast<jint>(n - start);
}

JNIEXPORT jint JNICALL Java_com_example_mobilendt_SlamNative_scan(JNIEnv* env, jclass, jlong h, jfloatArray out,
                                                                  jint maxPts) {
    auto sc = E(h)->lio.lastScan();
    int n = std::min<int>(static_cast<int>(sc.size()), maxPts);
    env->SetFloatArrayRegion(out, 0, n * 3, reinterpret_cast<const float*>(sc.data()));
    return n;
}

JNIEXPORT jboolean JNICALL Java_com_example_mobilendt_SlamNative_savePcd(JNIEnv* env, jclass, jlong h,
                                                                         jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    bool ok = E(h)->lio.savePcd(p);
    env->ReleaseStringUTFChars(path, p);
    return ok;
}

}  // extern "C"
