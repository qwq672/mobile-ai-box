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

        // 注: 实验 A 完全模仿 pocket-llm 风格, 不显式声明 ndkVersion
        // (pocket-llm 没有这一行, 用 AGP 默认 NDK; aibox 之前显式 = 26.1.10909125)
        // 但 ci.yml 仍然 install ndk;26.1.10909125, AGP 会自动找到

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
            signingConfig = signingConfigs.getByName("debug") // 简化首发，正式版请换正式签名
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
        // 避免重复 jni 库 (pocket-llm 风格: useLegacyPackaging = false)
        jniLibs {
            useLegacyPackaging = false
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

    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("androidx.core:core-splashscreen:1.0.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
