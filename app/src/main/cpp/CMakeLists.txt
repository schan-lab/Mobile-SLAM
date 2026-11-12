cmake_minimum_required(VERSION 3.22.1)
project(mobilendt CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../..)

add_library(mobilendt SHARED
    slam_jni.cpp
    ${ROOT}/core/lio.cpp
    ${ROOT}/core/ndt.cpp)

target_include_directories(mobilendt PRIVATE ${ROOT}/core ${ROOT}/third_party/eigen)
target_compile_options(mobilendt PRIVATE -O3 -ffast-math -fno-finite-math-only -DEIGEN_NO_DEBUG)
target_link_libraries(mobilendt log)
