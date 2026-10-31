plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.aibox.plugins.sample_echo"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.aibox.plugins.sample_echo"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { aidl = true }
}

// 独立 APK：插件不依赖宿主模块编译期，只共享 AIDL 接口
// AIDL 文件原样从 :core:plugin-host 复制
dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.androidx.core.ktx)
}
