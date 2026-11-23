// C API of the ROS 2 bag (MCAP) writer and player. Shared with the iOS app (same sources).
#pragma once
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/// Receives one Livox packet (type 1 = point, 2 = IMU).
typedef void (*DemoPacketFn)(void* ctx, int32_t type, const uint8_t* buf, int32_t len);

typedef struct RosbagWriter* RosbagWriterHandle;

/// Creates an MCAP file recording /livox/imu (sensor_msgs/Imu) and /livox/lidar (sensor_msgs/PointCloud2 with
/// per-point timestamps). Returns NULL if the file cannot be created.
RosbagWriterHandle rosbag_writer_open(const char* path);
/// Raw Livox packet (type 1 = point, 2 = IMU). Thread safe.
void rosbag_writer_push(RosbagWriterHandle h, int32_t type, const uint8_t* buf, int32_t len);
int64_t rosbag_writer_bytes(RosbagWriterHandle h);
/// Writes the summary / footer, closes the file and frees h.
void rosbag_writer_close(RosbagWriterHandle h);

typedef struct {
    int32_t state;           // 0 playing, 1 finished, 2 error
    double progress;         // 0..1 (bytes read)
    double elapsed;          // s of bag time played
    double duration;         // s, 0 when the bag has no summary
    int64_t point_msgs, imu_msgs;
    char lidar_topic[128];
    char imu_topic[128];
    char error[256];
} RosbagStatus;

/// Finds the LiDAR (sensor_msgs/PointCloud2 or livox CustomMsg) and IMU topics. Returns 1 if the bag can be played.
int32_t rosbag_probe(const char* path, RosbagStatus* out);

typedef struct RosbagPlayer* RosbagPlayerHandle;

/// Plays the bag in real time (scaled by speed) on a background thread, converted to Livox packets
/// (type 1 = point, 2 = IMU) passed to fn.
RosbagPlayerHandle rosbag_play_start(const char* path, double speed, DemoPacketFn fn, void* ctx);
void rosbag_play_status(RosbagPlayerHandle h, RosbagStatus* out);
/// Stops the thread (no more callbacks after this returns) and frees h.
void rosbag_play_stop(RosbagPlayerHandle h);

#ifdef __cplusplus
}
#endif
