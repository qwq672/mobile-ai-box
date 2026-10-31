plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aibox.litert"
    compileSdk = 35

    defaultConfig {
        // applicationId 后缀 .aibox.backend 与宿主 com.aibox 区分,
        // 与 Easy Chat 模块 com.easychat.aibox.module 区分,
        // 防止同设备多 APK 安装时签名 / 包名冲突。
        applicationId = "com.aibox.litert.backend"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Kotlin 2.4+ 弃用 kotlinOptions DSL,改用 compilerOptions
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-Xskip-prerelease-check",
                "-Xsuppress-warning:OPT_IN_USAGE",
                "-Xsuppress-warning:UNSUPPORTED_FEATURE"
            )
        }
    }

    buildFeatures {
        // 让 AIDL 文件参与构建。
        // 本工程实现宿主的 IPluginInterface / IPluginDescriptor(双接口),
        // 通过 buildFeatures.aidl = true 让 AGP 自动为 src/main/aidl/**.aidl 生成 Stub 基类。
        aidl = true
    }

    // LiteRT-LM 自带 native .so,自动从 AAR 提取,不需要 externalNativeBuild
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // ============================================================
    // LiteRT-LM 官方 Android SDK (Google Maven)
    // ============================================================
    // 一行依赖,自动拉取:
    //   - com.google.ai.edge.litertlm:litertlm-android:0.18.0 (含 native .so)
    //   - com.google.ai.edge.litert:litert-runtime (LiteRT core)
    //   - 必要的 Kotlin 协程 / kotlinx-serialization 等
    //
    // 支持的后端:
    //   - Backend.CPU():所有设备,默认
    //   - Backend.GPU():OpenCL 加速,需要 manifest 声明 libOpenCL.so
    //   - Backend.NPU(nativeLibraryDir):Hexagon NPU (Pixel / 骁龙 8 Gen3+)
    //
    // 不再需要 jniLibs/ 手动注入 .so 或 cpp/ externalNativeBuild
    // ============================================================
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")

    // 协程(用于 Flow / suspend 调用)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
