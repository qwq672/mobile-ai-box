plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.easychat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.easychat.aibox.module"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // 模块元数据通过 manifest meta-data 暴露给宿主 PackageManager 扫描。
        // 宿主扫描安装了 action "com.aibox.action.CHAT_MODULE" 的 Service 时
        // 会一并读取这些 meta-data,无需绑定 Binder 即可知道模块身份。
        manifestPlaceholders["aiboxModuleClass"] = "com.easychat.EasyChatModuleDescriptor"
        manifestPlaceholders["aiboxModuleVersion"] = "1.0.0"
        manifestPlaceholders["aiboxModuleName"] = "easy-chat"
        manifestPlaceholders["aiboxModuleDisplayName"] = "Easy Chat"
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
    // 注意:本工程只声明 IChatModuleInterface 这一个接口,不实现宿主侧的
    // IPluginInterface 等接口(那是宿主与推理框架插件之间的契约)。
    // 宿主通过 ServiceConnection.onServiceConnected 拿到 IBinder 后调
    // IChatModuleInterface.Stub.asInterface() 即可。
    buildFeatures {
        aidl = true
    }
}

dependencies {
    // AIDL 接口 IChatModuleInterface 在本工程内定义。
    // 宿主工程后续会把本工程的 AIDL 文件复制一份(或发布成 AAR)用编译时引用,
    // 类似 com.aibox.plugin.IPluginInterface 的分发方式。
    //
    // 本工程没有运行时依赖,模型文件 + module.json + 一个 Service 就是全部交付物。
}
