plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.aibox.core.plugin_host"
    compileSdk = 34
    defaultConfig {
        minSdk = 29
        // AIDL 暴露给插件 APK，必须固定包名
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // 启用 AIDL 编译
    buildFeatures { aidl = true }
}

dependencies {
    api(project(":core:common"))
    api(project(":core:inference-api"))
    api(project(":core:logger"))
    api(libs.coroutines.core)
    api(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
}
