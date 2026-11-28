import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

/**
 * Hexagon（ggml-hexagon）实验构建开关。
 *
 * 默认关闭：只需要 Hexagon SDK（HEXAGON_SDK_ROOT / HEXAGON_TOOLS_ROOT），
 * 不需要 QNN SDK。Android 产出的 HTP skel 未签名，零售机能否加载取决于
 * 设备的 FastRPC unsigned PD 策略（HEXAGON_HTP_CERT 只对 Windows 构建生效）。
 * 开启方式：`./gradlew assembleRelease -Paibox.npu=true`。
 */
val aiboxNpu = providers.gradleProperty("aibox.npu")
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(false)

/**
 * 离线 ONNX ASR（sherpa-onnx）构建开关。
 *
 * 默认关闭：不打 sherpa-onnx AAR（~47MB），默认 CI / 出包零风险、体积不变。
 * 开启方式：`./gradlew assembleRelease -Paibox.asr.sherpa=true`。
 * OnnxAsrProvider 用反射访问，无编译期依赖，故默认关闭也能编译通过；
 * 仅 isAvailable() 在类不存在时返回 false，自动回退 system provider。
 */
val aiboxAsrSherpa = providers.gradleProperty("aibox.asr.sherpa")
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(false)

android {
    namespace = "com.aibox"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aibox"
        minSdk = 26
        targetSdk = 34
        // 版本规范：测试版 vYYMMDD-N（当日第 N 个测试版），正式版 vYYMMDD。
        // versionCode 用 YYMMDDNN，保证随每次交付递增，避免覆盖安装仍显示旧版本。
        versionCode = 26112802
        versionName = "v261128-2"

        // 离线 ONNX ASR 开关传给代码（用于设置页展示能力状态）
        buildConfigField("boolean", "AIBOX_ASR_SHERPA", aiboxAsrSherpa.toString())

        ndk {
            // 手机几乎都是 arm64；缩小体积只打这一个
            abiFilters += listOf("arm64-v8a")
        }

        // 注: 实验 A 完全模仿 pocket-llm 风格, 不显式声明 ndkVersion
        // (pocket-llm 没有这一行, 用 AGP 默认 NDK; aibox 之前显式 = 26.1.10909125)
        // 但 ci.yml 仍然 install ndk;26.1.10909125, AGP 会自动找到

        externalNativeBuild {
            cmake {
                // 让 CMake 决定大部分 flag，这里只传必要的
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-28",  // API 28+ 有 Vulkan 1.1 (vkGetPhysicalDeviceFeatures2)
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DAIBOX_NPU=${if (aiboxNpu) "ON" else "OFF"}"
                )
                if (aiboxNpu) {
                    // ggml-hexagon 读的是 CMake 变量，不是环境变量
                    val hexSdk = System.getenv("HEXAGON_SDK_ROOT")
                    if (hexSdk.isNullOrBlank()) {
                        throw GradleException("-Paibox.npu=true 需要先设置 HEXAGON_SDK_ROOT")
                    }
                    arguments += "-DHEXAGON_SDK_ROOT=$hexSdk"
                    System.getenv("HEXAGON_TOOLS_ROOT")?.takeIf { it.isNotBlank() }?.let {
                        arguments += "-DHEXAGON_TOOLS_ROOT=$it"
                    }
                    // ggml-hexagon 的 hexagon_fun.cmake 要求该变量非空（用于 FastRPC 头路径）
                    arguments += "-DPREBUILT_LIB_DIR=android_aarch64"
                    // 某些环境的 python3 shim 不可用，显式指定解释器
                    val py = System.getenv("AIBOX_PYTHON3") ?: "/usr/bin/python3"
                    if (file(py).exists()) {
                        arguments += "-DPython3_EXECUTABLE=$py"
                    }
                }
                // 注意：这些 flags 会同时作用于 add_subdirectory 的 llama.cpp。
                // llama.cpp 0.4.x 的 ggml-backend-reg 等用到 try/RTTI，不能全局关异常/RTTI。
                // 针对本 app 的优化（隐藏符号、gc-sections）在 CMakeLists.txt 里对 aibox target 单独设置。
                cFlags += "-O3"
                cppFlags += "-std=c++17"
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true  // 启用 AIDL(生成 com.aibox.plugin.* parcelable / interface 类)
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // ggml-hexagon 的 htp 子工程要求 CMake >= 3.22.2
            version = if (aiboxNpu) "3.31.6" else "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }

    // 资源压缩 + 代码混淆，缩小体积
    // 固定测试签名：CI 默认 debug keystore 每次重新生成，前后版本签名不一致，
    // 覆盖安装会被系统拒绝（表现就是"装了新包但打开还是旧版"）。
    signingConfigs {
        create("stable") {
            storeFile = file("keystore/aibox-stable.jks")
            storePassword = "aibox123456"
            keyAlias = "aibox"
            keyPassword = "aibox123456"
        }
    }
    buildTypes {
        lint { abortOnError = false; checkReleaseBuilds = false }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("stable")
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    // 只打包需要的语言
    bundle {
        language { enableSplit = true }
        density  { enableSplit = true }
        abi      { enableSplit = true }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/**.md",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "kotlin/**.kotlin_builtins",
                "**/kotlin-tooling-metadata.json",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.**",
                "META-INF/versions/**",
                "DebugProbesKt.bin"
            )
        }
        // ggml-hexagon / OpenCL 需要真实的 .so 文件路径（ggml_backend_load 会做 access 检查），
        // 所以必须让安装时把 jni 库解压到 nativeLibraryDir。
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")

    implementation("androidx.navigation:navigation-compose:2.8.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.0.20")

    implementation("org.jsoup:jsoup:1.18.1")

    // 离线 ONNX ASR（可选，默认关闭；-Paibox.asr.sherpa=true 开启）
    if (aiboxAsrSherpa) {
        implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.5")
    }

    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("androidx.core:core-splashscreen:1.0.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

