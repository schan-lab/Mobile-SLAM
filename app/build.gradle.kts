plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.mobilendt"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.example.mobilendt"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.2"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    buildTypes {
        // keep native code optimised even in debug builds (SLAM is CPU heavy)
        getByName("debug") { externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" } } }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    androidResources {
        // per-app language (Android 13+): English (default) and Japanese, from res/values*
        generateLocaleConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // SFTP upload (SSH client); Bouncy Castle provides Ed25519 for keys and signatures
    implementation("com.github.mwiede:jsch:2.28.8")
    implementation("org.bouncycastle:bcprov-jdk18on:1.87")
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
