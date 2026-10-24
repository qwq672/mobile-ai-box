import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.aibox"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aibox"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "v261018-1"

        ndk {
            // 手机几乎都是 arm64；缩小体积只打这一个
            abiFilters += listOf("arm64-v8a")
        }

        // 固定 NDK 版本,与 ci.yml 替换 Vulkan headers 的版本一致
        // AGP 8.7 默认可能用 NDK 27,但 ci.yml 只替换了 NDK 26.1 的 headers
        ndkVersion = "26.1.10909125"

        externalNativeBuild {
            cmake {
                // 让 CMake 决定大部分 flag，这里只传必要的
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-28",  // API 28+ 有 Vulkan 1.1 (vkGetPhysicalDeviceFeatures2)
                    "-DCMAKE_BUILD_TYPE=Release"
                )
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
            version = "3.22.1"
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
    buildTypes {
        lint { abortOnError = false; checkReleaseBuilds = false }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            // 显式指定 debug 签名,确保 CI 环境也生成签名文件
            signingConfig = signingConfigs.getByName("debug")
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
        // jniLibs: useLegacyPackaging=true 让 .so 在安装时解压到设备
        // useLegacyPackaging=false 要求 .so 在 zip 里 page-aligned(4KB)
        // 但 AGP 在 CI 环境可能不对齐 .so → 安装报"解析包时出现问题"
        // true 是最安全的选项(体积略大但兼容性最好)
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
    // extended 提供 Send/Stop/SmartToy/Bolt 等图标；BOM 已自动收敛版本
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.1")

    // DataStore + Room
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Networking（下载源）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    // moshi-kotlin 反射需要与 Kotlin 编译器同版本的 kotlin-reflect，否则运行时 TypeUtils NoSuchMethodError
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.0.20")

    // Jsoup（B4 UrlReaderTool 抓取并解析网页 HTML）
    // 注：B3 WebSearchTool 的 DuckDuckGo HTML 兜底解析也复用此依赖
    implementation("org.jsoup:jsoup:1.18.1")

    // WorkManager（断点续传、后台下载）
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Splash
    implementation("androidx.core:core-splashscreen:1.0.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // ===================================================
    // B2-B5：基础工具单元测试
    // 环境无 Android SDK，无法跑 :app:assembleDebug 验证编译，但写测试代码本身有意义
    // 测试只覆盖纯 Kotlin 逻辑（DateTime / Calculator / ExpressionParser），
    // 涉及 Android 类（Build / Context / DataStore）的工具靠人工 review
    // ===================================================
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
