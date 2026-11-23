cmake_minimum_required(VERSION 3.22.1)
project(mobilendt C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../..)

# zstd decompressor and lz4 (BSD): read compressed MCAP chunks
add_library(bagcodec STATIC
    rosbag/zstd_decoder.c
    ${ROOT}/third_party/lz4/lz4.c
    ${ROOT}/third_party/lz4/lz4frame.c
    ${ROOT}/third_party/lz4/lz4hc.c
    ${ROOT}/third_party/lz4/xxhash.c)
target_include_directories(bagcodec PUBLIC ${ROOT}/third_party/zstd/lib ${ROOT}/third_party/lz4)
target_compile_options(bagcodec PRIVATE -O2)
set_target_properties(bagcodec PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(mobilendt SHARED
    slam_jni.cpp
    demo_jni.cpp
    rosbag_jni.cpp
    rosbag/RosbagWriter.cpp
    rosbag/RosbagPlayer.cpp
    ${ROOT}/core/lio.cpp
    ${ROOT}/core/ndt.cpp)

target_include_directories(mobilendt PRIVATE ${ROOT}/core ${ROOT}/third_party/eigen rosbag)
target_compile_options(mobilendt PRIVATE -O3 -ffast-math -fno-finite-math-only -DEIGEN_NO_DEBUG -DEIGEN_MPL2_ONLY)
target_link_libraries(mobilendt bagcodec log)
