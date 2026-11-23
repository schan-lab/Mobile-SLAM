# Third-party notices

The code in this repository is MIT licensed (see `LICENSE`), except for the
third-party components below, which keep their own licenses.

## Bundled in this repository

### Eigen 3.4.0 (`third_party/eigen`)

- License: Mozilla Public License 2.0 (`third_party/eigen/COPYING.MPL2`).
  A few headers carry BSD-style notices (`COPYING.BSD`, `COPYING.README`).
- Source: https://gitlab.com/libeigen/eigen (tag 3.4.0), unmodified.
- Only the `Eigen/` headers are kept here. The app uses `Eigen/Core` and
  `Eigen/Dense` and is built with `-DEIGEN_MPL2_ONLY`, so no LGPL code is compiled in.

### Zstandard 1.5.7, decompressor only (`third_party/zstd`)

- License: BSD (`third_party/zstd/LICENSE`; zstd is dual BSD / GPLv2, used here under BSD).
- Source: https://github.com/facebook/zstd (release v1.5.7), `lib/common` and `lib/decompress`, unmodified.
  Compiled as one unit by `app/src/main/cpp/rosbag/zstd_decoder.c` (same settings as upstream `zstddeclib-in.c`).

### LZ4 1.10.0 (`third_party/lz4`)

- License: BSD 2-Clause (`third_party/lz4/LICENSE`).
- Source: https://github.com/lz4/lz4 (tag v1.10.0), `lib/` files, unmodified.

### Gradle Wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`)

- License: Apache License 2.0 — https://www.apache.org/licenses/LICENSE-2.0
- Source: https://github.com/gradle/gradle

## Included in the built APK (not stored in this repository)

### Kotlin standard library

- License: Apache License 2.0 — https://www.apache.org/licenses/LICENSE-2.0
- Source: https://github.com/JetBrains/kotlin

### JSch 2.28.8 (`com.github.mwiede:jsch`, SSH / SFTP client)

- License: BSD-style (JSch, JZlib) and ISC (jBCrypt) — https://github.com/mwiede/jsch
- Used for SFTP upload.

### Bouncy Castle 1.87 (`org.bouncycastle:bcprov-jdk18on`)

- License: MIT — https://www.bouncycastle.org
- Provides Ed25519 for the app's SSH key pair and JSch.

### LLVM libc++ (Android NDK C++ runtime)

- License: Apache License 2.0 with LLVM Exceptions — https://llvm.org/LICENSE.txt
- Source: https://github.com/llvm/llvm-project

The license texts of zstd, lz4, JSch and Bouncy Castle are bundled in the app
(`app/src/main/res/raw/third_party_licenses.txt`, shown in Settings › About & Licenses).

## Referenced, no code included

- FAST-LIO (W. Xu, F. Zhang, RA-L 2021): the LiDAR-inertial odometry in `core/`
  follows the formulation of the paper. It is an independent implementation and
  contains no code from the FAST-LIO repository.
- Livox Mid-360: the UDP protocol handling is an independent implementation;
  no Livox SDK code is included.
