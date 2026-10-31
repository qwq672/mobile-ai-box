plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aibox.translate"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aibox.translate.module"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // 模块元数据通过 manifest meta-data 暴露给宿主 PackageManager 扫描。
        // 宿主扫描安装了 action "com.aibox.action.TRANSLATE_MODULE" 的 Service 时
        // 会一并读取这些 meta-data,无需绑定 Binder 即可知道模块身份。
        manifestPlaceholders["aiboxModuleClass"] = "com.aibox.translate.TranslateModuleDescriptor"
        manifestPlaceholders["aiboxModuleVersion"] = "1.0.0"
        manifestPlaceholders["aiboxModuleName"] = "translate"
        manifestPlaceholders["aiboxModuleDisplayName"] = "翻译"
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
    kotlinOptions {
        jvmTarget = "17"
    }

    // 让 AIDL 文件参与构建。
    // 注意:本工程只声明 ITranslateModuleInterface + ITranslateCallback 两个接口,
    // 不实现宿主侧的 IPluginInterface / IChatModuleInterface 等接口。
    // 宿主通过 ServiceConnection.onServiceConnected 拿到 IBinder 后调
    // ITranslateModuleInterface.Stub.asInterface() 即可。
    buildFeatures {
        aidl = true
    }
}

dependencies {
    // 协程:TranslationOrchestrator 用 suspend 函数 + CoroutineScope 协调三步工作流。
    // 版本与宿主 AIBox 主工程一致(kotlinx-coroutines 1.9.0),编译期需要 kotlinx-coroutines-core。
    // 运行时 kotlinx-coroutines-android 提供 Dispatchers.Main 等 Android 专用调度器。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // AIDL 接口 ITranslateModuleInterface / ITranslateCallback 在本工程内定义。
    // 宿主工程后续会把本工程的 AIDL 文件复制一份(或发布成 AAR)用编译时引用,
    // 类似 com.aibox.plugin.IPluginInterface 的分发方式。
    //
    // 本工程不打包 ASR / TTS / MT 实现,也不打包任何模型;
    // module.json + 一个 Service + 协调器逻辑就是全部交付物。
}
