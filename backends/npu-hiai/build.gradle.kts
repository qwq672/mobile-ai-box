plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.aibox.backends.npu.hiai"
    compileSdk = 34
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":core:common"))
    api(project(":core:inference-api"))
    api(project(":core:logger"))
    api(libs.coroutines.core)
    // 厂商 SDK 通过 fileTree 引入（路径见各模块 README）
    // fileTree(dir: "libs", include: ["*.jar", "*.aar"])
}
