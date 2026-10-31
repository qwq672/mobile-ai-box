# AIBox 插件开发指南

本指南面向想要为 AIBox 开发**第三方插件**的工程师。读完本指南你将能：

- 区分 AIBox 的三类插件（推理框架后端 / 聊天模块 / 工具插件）
- 实现完整的 AIDL 接口并通过宿主 PackageManager 自动发现
- 用 SettingField schema 驱动宿主 UI 动态生成设置面板
- 把日志回传宿主 LogSystem，与宿主崩溃日志统一收集
- 在 GitHub Releases 上发布符合签名与兼容性要求的插件 APK

本指南的所有代码片段都来自项目内已实现的两个示例工程：

- [`easy-chat-module/`](../easy-chat-module/)：聊天模块插件，打包 GGUF 模型，仅暴露 3 个只读方法
- [`litert-plugin/`](../litert-plugin/)：推理框架后端插件，打包 LiteRT-LM SDK，暴露 11 个 AIDL 方法

---

## 1. 插件类型

AIBox 把"插件"按职责拆成三类，对应三个独立的 Intent Action 与三套 AIDL：

| 类型 | Intent Action | AIDL 接口 | 是否打包模型 | 是否打包推理框架 | 典型用例 |
| --- | --- | --- | --- | --- | --- |
| **推理框架后端** | `com.aibox.action.BACKEND_PLUGIN` | `IPluginInterface` + `IPluginDescriptor` + `IStreamCallback` + `IPluginLogCallback` | 否（用户自备模型） | 是（如 LiteRT-LM / MLC / MNN 的 .so） | LiteRT-LM Backend、MLC-LLM Backend |
| **聊天模块** | `com.aibox.action.CHAT_MODULE` | `IChatModuleInterface` | 是（GGUF / TFLite） | 否（复用宿主或已装后端） | Easy Chat (Qwen2.5-0.5B)、SmolLM Chat |
| **工具插件** | `com.aibox.action.TOOL_PLUGIN` | （暂未定义） | 否 | 否 | 模型转换器、Tokenizer 工具、Prompt 模板包 |

> **设计要点**：聊天模块负责"装好就能用"，把模型当资产分发；推理框架后端负责"加速能力扩展"，把推理 .so 当资产分发；两者通过宿主解耦。用户可以"先装 LiteRT-LM 后端 + 再装 Gemma Module"组合出 NPU 加速聊天。

下面三节先讲三类插件**共用**的工程骨架与 AIDL 接口，再分别展开各自差异。

---

## 2. 独立 Gradle 工程搭建

插件必须是**独立的 Gradle 工程**，不能是宿主工程的 `:app` 子模块。原因有三：

1. 插件 APK 与宿主 APK 分开发布、独立签名、不同 `applicationId`
2. 宿主用户群不一定都装所有插件，宿主 build 不应依赖插件
3. 插件签名独立于宿主，方便第三方开发者用自己的 keystore 发布

### 2.1 顶层 settings.gradle.kts

参考 [`litert-plugin/settings.gradle.kts`](../litert-plugin/settings.gradle.kts)：

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "LiteRTLMPlugin"
include(":app")
```

### 2.2 顶层 build.gradle.kts（版本对齐宿主）

```kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
}
```

> **版本对齐**：插件 AGP / Kotlin 版本应与宿主一致或更高。低版本 Kotlin 编译出的字节码高版本宿主能加载，但反之不行。

### 2.3 app/build.gradle.kts 关键片段

参考 [`litert-plugin/app/build.gradle.kts`](../litert-plugin/app/build.gradle.kts)：

```kotlin
android {
    namespace = "com.aibox.litert"                    // Kotlin 包名根
    applicationId = "com.aibox.litert.backend"        // 必须与宿主 com.aibox 区分

    minSdk = 28                                       // 与宿主一致（API 28+ 有 Vulkan 1.1）
    targetSdk = 35
    compileSdk = 35
    versionCode = 1
    versionName = "1.0.0"

    ndk {
        abiFilters += listOf("arm64-v8a")              // 真实手机 ABI，缩小体积
    }

    buildFeatures {
        aidl = true                                    // 让 AIDL 文件参与构建生成 Stub
    }

    externalNativeBuild {                             // 仅推理框架后端需要
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
```

> **buildFeatures.aidl = true 必加**。否则即使把 `.aidl` 文件放进 `src/main/aidl/`，AGP 也不会生成 Stub 类，`IPluginInterface.Stub` 编译报错。

### 2.4 ProGuard 规则

参考 [`litert-plugin/app/proguard-rules.pro`](../litert-plugin/app/proguard-rules.pro)。最小集合：

```proguard
# AIDL Stub 类不被混淆（Binder 跨进程靠反射）
-keep class com.aibox.plugin.IPluginInterface { *; }
-keep class com.aibox.plugin.IPluginInterface$* { *; }
-keep class com.aibox.plugin.IPluginDescriptor { *; }
-keep class com.aibox.plugin.IStreamCallback { *; }
-keep class com.aibox.plugin.IPluginLogCallback { *; }

# parcelable 类不被混淆（AIDL onTransact 用反射构造）
-keep class com.aibox.plugin.PluginInfo { *; }
-keep class com.aibox.plugin.SettingFieldEntry { *; }
-keep class com.aibox.plugin.BackendCapabilitiesParcel { *; }
-keep class com.aibox.plugin.LoadModelRequest { *; }
-keep class com.aibox.plugin.LoadModelResult { *; }
-keep class com.aibox.plugin.InferRequest { *; }
-keep class com.aibox.plugin.InferResult { *; }

# 插件自己的 Service / Descriptor / Binder 类
-keep public class com.aibox.litert.** { public *; }

# 保留注解 / 内部类 / 签名（AIDL Proxy 需要）
-keepattributes RuntimeVisibleAnnotations,Signature,Deprecated,EnclosingMethod,InnerClasses
```

---

## 3. AIDL 接口说明

所有 AIDL 文件放在 `app/src/main/aidl/com/aibox/plugin/`。包名固定为 `com.aibox.plugin`（与宿主对齐），便于后续合并发布 `aibox-plugin-api` AAR。

### 3.1 推理框架后端：4 个 interface + 7 个 parcelable

完整文件清单见 [`app/src/main/aidl/com/aibox/plugin/`](../app/src/main/aidl/com/aibox/plugin/)，下面描述职责：

| 文件 | 类型 | 职责 |
| --- | --- | --- |
| `IPluginInterface.aidl` | interface | 运行时接口：`loadModel` / `unload` / `infer` / `streamInfer` / `applyOptimizations` / `setLogCallback` |
| `IPluginDescriptor.aidl` | interface | 元信息接口：`getPluginInfo` / `getName` / `getDisplayName` / `getVersion` / `describeCapabilities` / `getSettingsSchema` / `getDefaultSettings` |
| `IStreamCallback.aidl` | interface | 流式推理回调：`onChunk(partialJson, done)` / `onError(reason)` |
| `IPluginLogCallback.aidl` | interface | 日志回传：`onLog(level, tag, msg)` |
| `PluginInfo.aidl` | parcelable | 插件身份（包名 / descriptor 类 / 版本 / 显示名 / 大小） |
| `SettingFieldEntry.aidl` | parcelable | 设置字段元数据（11 个字段；type 用 int 表达 FieldType 序数） |
| `BackendCapabilitiesParcel.aidl` | parcelable | 能力声明（task / modality / features 用 bitmask；format / quantization 用 String List） |
| `LoadModelRequest.aidl` | parcelable | 加载请求（modelPath + configJson） |
| `LoadModelResult.aidl` | parcelable | 加载结果（success + modelHandle + errorMessage） |
| `InferRequest.aidl` | parcelable | 推理请求（taskId / taskType / modelId / inputs JSON / config / stream / sessionId） |
| `InferResult.aidl` | parcelable | 推理结果（success + resultJson + errorMessage） |

### 3.2 IPluginInterface 方法详解

```aidl
interface IPluginInterface {
    PluginInfo getPluginInfo();
    LoadModelResult loadModel(in LoadModelRequest request);
    void unload(String modelHandle);
    InferResult infer(in InferRequest request, in String modelHandle);
    void streamInfer(in InferRequest request, in String modelHandle, in IStreamCallback callback);
    void applyOptimizations(in Map<String, Boolean> flags);
    void setLogCallback(in IPluginLogCallback callback);
}
```

**调用顺序**（由宿主 [`PluginManager`](../app/src/main/kotlin/com/aibox/infra/plugin/PluginManager.kt) 编排）：

1. `setLogCallback` —— 注入宿主日志回调（在 `enablePlugin` 时立即调）
2. `applyOptimizations` —— 应用优化开关（可选；在 loadModel 之前调）
3. `loadModel` —— 加载模型，返回 `modelHandle`（字符串）
4. `infer` 或 `streamInfer` —— 推理；流式版本通过 `IStreamCallback.onChunk` 推送
5. `unload` —— 释放模型；调用后 handle 不可再用

**零异常约定**：所有方法**不抛 `RemoteException` 给业务层**。失败路径走 `LoadModelResult{success=false, errorMessage="..."}` 或 `InferResult{success=false, ...}`，或 `IStreamCallback.onError(reason)`。

### 3.3 聊天模块：IChatModuleInterface（仅 3 方法）

参考 [`easy-chat-module/app/src/main/aidl/com/aibox/plugin/IChatModuleInterface.aidl`](../easy-chat-module/app/src/main/aidl/com/aibox/plugin/IChatModuleInterface.aidl)：

```aidl
interface IChatModuleInterface {
    String getModuleMetadata();                  // 返回 module.json 全文
    long getModelFileSize();                     // 模型文件字节数（-1 = 未注入）
    ParcelFileDescriptor openModelFileDescriptor(); // 零拷贝传模型文件
}
```

**为什么用 `ParcelFileDescriptor` 而不是 `byte[]`**：AIDL `byte[]` 走 binder 1MB 缓冲，485MB 模型直接爆缓冲。`ParcelFileDescriptor` 走内核管道零拷贝，是 Android 跨进程传大文件的官方姿势（与 `ContentProvider.openFile` 同套机制）。

---

## 4. AndroidManifest 配置

### 4.1 推理框架后端示例

参考 [`litert-plugin/app/src/main/AndroidManifest.xml`](../litert-plugin/app/src/main/AndroidManifest.xml)：

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:label="LiteRT-LM Backend"
        android:allowBackup="false"
        android:hasCode="true">

        <service
            android:name=".LiteRTPluginService"
            android:exported="true"
            android:enabled="true">

            <intent-filter>
                <action android:name="com.aibox.action.BACKEND_PLUGIN" />
            </intent-filter>

            <!-- 宿主 Class.forName 反射加载 Descriptor 实例 -->
            <meta-data
                android:name="aibox.descriptor_class"
                android:value="com.aibox.litert.LiteRTDescriptor" />

            <!-- 与 PluginInfo.version 对齐，便于宿主双路校验 -->
            <meta-data
                android:name="aibox.version"
                android:value="1.0.0" />
        </service>
    </application>
</manifest>
```

### 4.2 聊天模块示例

参考 [`easy-chat-module/app/src/main/AndroidManifest.xml`](../easy-chat-module/app/src/main/AndroidManifest.xml)：

```xml
<service
    android:name=".EasyChatModuleService"
    android:exported="true"
    android:enabled="true">

    <intent-filter>
        <action android:name="com.aibox.action.CHAT_MODULE" />
    </intent-filter>

    <meta-data android:name="aibox.module.class"        android:value="com.easychat.EasyChatModuleDescriptor" />
    <meta-data android:name="aibox.module.name"         android:value="easy-chat" />
    <meta-data android:name="aibox.module.displayName"  android:value="Easy Chat" />
    <meta-data android:name="aibox.module.version"      android:value="1.0.0" />

    <!-- 模型核心元数据：宿主可据此预判 RAM / 后端兼容性，无需 bind -->
    <meta-data android:name="aibox.module.model.fileName"          android:value="qwen2.5-0.5b-instruct-q4_k_m.gguf" />
    <meta-data android:name="aibox.module.model.sizeMb"            android:value="485" />
    <meta-data android:name="aibox.module.model.architecture"      android:value="qwen2" />
    <meta-data android:name="aibox.module.model.recommendedBackend" android:value="vulkan,cpu" />
    <meta-data android:name="aibox.module.model.avoidBackend"      android:value="npu" />
</service>
```

### 4.3 关键属性说明

- `android:exported="true"` —— 让宿主进程（不同 UID）能 `bindService`
- `android:enabled="true"` —— 默认开启，让宿主 `queryIntentServices` 能扫到
- `<intent-filter>` —— **必须**有 `com.aibox.action.BACKEND_PLUGIN` 或 `CHAT_MODULE`，否则宿主 PackageManager 发现不了
- `<meta-data>` —— 宿主扫描时无需 bind 即可读，用于快速过滤（包大小、SoC 兼容性等）

### 4.4 宿主侧 queries 配置（不在插件工程内）

宿主侧必须加 `<queries>` 块（[宿主 AndroidManifest](../app/src/main/AndroidManifest.xml) 已配）：

```xml
<queries>
    <intent>
        <action android:name="com.aibox.action.BACKEND_PLUGIN" />
    </intent>
    <intent>
        <action android:name="com.aibox.action.CHAT_MODULE" />
    </intent>
</queries>
```

否则 Android 11+（API 30）的包可见性限制会让 `queryIntentServices` 返回空。

---

## 5. 实现 BackendDescriptor

[`BackendDescriptor`](../app/src/main/kotlin/com/aibox/domain/inference/BackendDescriptor.kt) 是宿主侧的 Kotlin 接口，插件侧实现其 AIDL 对应 `IPluginDescriptor.Stub`。下面看完整实现：[`litert-plugin/app/src/main/kotlin/com/aibox/litert/LiteRTDescriptor.kt`](../litert-plugin/app/src/main/kotlin/com/aibox/litert/LiteRTDescriptor.kt)。

### 5.1 骨架

```kotlin
class LiteRTDescriptor : IPluginDescriptor.Stub() {

    override fun getPluginInfo(): PluginInfo {
        return PluginInfo().apply {
            packageName = "com.aibox.litert.backend"
            descriptorClass = "com.aibox.litert.LiteRTDescriptor"
            version = "1.0.0"
            displayName = "LiteRT-LM"
            sizeBytes = 5L * 1024 * 1024
        }
    }

    override fun getName(): String = "litert"
    override fun getDisplayName(): String = "LiteRT-LM (Google)"
    override fun getVersion(): String = "1.0.0"

    override fun describeCapabilities(): BackendCapabilitiesParcel {
        return BackendCapabilitiesParcel().apply {
            supportedTasksBitmask = BitPositions.supportedTasksBitmask
            supportedInputModalitiesBitmask = BitPositions.supportedInputModalitiesBitmask
            supportedOutputModalitiesBitmask = BitPositions.supportedOutputModalitiesBitmask
            maxContextLength = 8192
            maxImageResolution = 2048
            supportedModelFormats = listOf("tflite")
            supportedQuantizations = listOf("int8", "fp16", "bf16")
            featuresBitmask = BitPositions.featuresBitmask
        }
    }

    // getSettingsSchema / getDefaultSettings 见 §7
}
```

### 5.2 Bitmask 计算

[`BackendCapabilitiesParcel`](../app/src/main/aidl/com/aibox/plugin/BackendCapabilitiesParcel.aidl) 用 bitmask 表达 `Set<Enum>`，避免 AIDL 不支持 `Set<T>` 的限制。Bit 位定义见 [`BitPositions.kt`](../litert-plugin/app/src/main/kotlin/com/aibox/litert/BitPositions.kt)：

```kotlin
object BitPositions {
    const val TASK_TEXT_GENERATION = 0
    const val TASK_MULTIMODAL_UNDERSTANDING = 8
    const val TASK_EMBEDDING = 2

    val supportedTasksBitmask: Int =
        (1 shl TASK_TEXT_GENERATION) or
        (1 shl TASK_MULTIMODAL_UNDERSTANDING) or
        (1 shl TASK_EMBEDDING)
}
```

> **重要**：`BitPositions` 中的 ordinal 值必须与宿主 [`TaskType.kt`](../app/src/main/kotlin/com/aibox/domain/inference/TaskType.kt) / `BackendCapabilities.kt` 严格对齐。宿主侧新增枚举值时插件需要同步更新。长期方案是发布 `aibox-plugin-api` AAR 让所有插件依赖同一份常量类。

---

## 6. 实现 InferenceBackendV2（通过 AIDL Stub）

插件**不需要**直接实现宿主侧的 [`InferenceBackendV2`](../app/src/main/kotlin/com/aibox/domain/inference/InferenceBackendV2.kt)。插件实现 `IPluginInterface.Stub` 即可，宿主侧的 [`PluginBackendAdapter`](../app/src/main/kotlin/com/aibox/infra/plugin/PluginBackendAdapter.kt) 会自动把 AIDL 包装成 V2 接口。

### 6.1 Binder 实现骨架

参考 [`litert-plugin/app/src/main/kotlin/com/aibox/litert/LiteRTBinder.kt`](../litert-plugin/app/src/main/kotlin/com/aibox/litert/LiteRTBinder.kt)：

```kotlin
class LiteRTBinder(private val service: LiteRTPluginService) : IPluginInterface.Stub() {

    private val backend = LiteRTBackend()   // 插件自己的推理核心
    private val logTag = "aibox.plugin.litert.binder"

    override fun loadModel(request: LoadModelRequest?): LoadModelResult {
        if (request == null) return fail("LoadModelRequest is null")
        val modelPath = request.modelPath?.takeIf { it.isNotBlank() }
            ?: return fail("modelPath is empty")
        return try {
            val handle = backend.loadModel(modelPath, request.configJson ?: "{}")
            LiteRTLogger.info(logTag, "loadModel ok: path=$modelPath handle=$handle")
            LoadModelResult().apply {
                success = true
                modelHandle = handle
                errorMessage = ""
            }
        } catch (e: Throwable) {
            LiteRTLogger.error(logTag, "loadModel failed: path=$modelPath", e)
            fail("load failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun fail(message: String): LoadModelResult = LoadModelResult().apply {
        success = false
        modelHandle = ""
        errorMessage = message
    }
}
```

### 6.2 Service 入口

参考 [`litert-plugin/app/src/main/kotlin/com/aibox/litert/LiteRTPluginService.kt`](../litert-plugin/app/src/main/kotlin/com/aibox/litert/LiteRTPluginService.kt)：

```kotlin
class LiteRTPluginService : Service() {
    override fun onBind(intent: Intent?): IBinder? {
        // 校验 action（防止其它 App 误绑定）
        if (intent?.action != ACTION_BACKEND_PLUGIN) return null
        return LiteRTBinder(this)
    }
    companion object {
        const val ACTION_BACKEND_PLUGIN = "com.aibox.action.BACKEND_PLUGIN"
    }
}
```

### 6.3 Descriptor 与 Binder 分离

LiteRT 工程让 Descriptor 与 Binder 是**两个独立实例**：

- `LiteRTBinder`（IPluginInterface.Stub）由 `onBind` 返回
- `LiteRTDescriptor`（IPluginDescriptor.Stub）由宿主 `Class.forName("com.aibox.litert.LiteRTDescriptor")` 反射创建

好处：宿主可在"未 bind Service"时先反射创建 Descriptor 读 capabilities，再决定是否值得 bind；坏插件 Descriptor 异常不会拖垮 Service。

---

## 7. 设置项 Schema（SettingField）的写法

插件用 `SettingFieldEntry` AIDL parcelable 描述每个设置项；宿主侧用 [`SchemaRenderer.SettingsListRenderer`](../app/src/main/kotlin/com/aibox/ui/components/unified/SchemaRenderer.kt) 动态生成 UI。

### 7.1 五种控件类型

`SettingField.FieldType` 序数（参考 [`SettingField.kt`](../app/src/main/kotlin/com/aibox/infra/plugin/SettingField.kt)）：

| 序数 | 类型 | 用途 | 必填字段 |
| --- | --- | --- | --- |
| 0 | SWITCH | 布尔开关 | defaultValue = "true" / "false" |
| 1 | DROPDOWN | 下拉单选 | optionValues + optionLabels + defaultValue |
| 2 | SLIDER | 数值滑块 | minValue + maxValue + step + defaultValue |
| 3 | INPUT | 文本输入 | defaultValue + 可选 keyboardHint |
| 4 | GROUP | 分组标题（不渲染控件） | 仅 title |

### 7.2 完整示例（LiteRT 5 项设置）

```kotlin
override fun getSettingsSchema(): MutableList<SettingFieldEntry> {
    val entries = mutableListOf<SettingFieldEntry>()

    // 1. 优先 Delegate（下拉）
    entries += SettingFieldEntry().apply {
        key = "litert.preferredDelegate"
        type = BitPositions.SETTING_TYPE_DROPDOWN  // 1
        title = "优先 Delegate"
        description = "NPU 加速 / GPU / CPU 备选"
        defaultValue = "auto"
        optionValues = listOf("auto", "npu", "gpu", "cpu")
        optionLabels = listOf("自动(推荐)", "Hexagon NPU", "GPU Delegate", "CPU")
    }

    // 2. GPU FP16 推理（开关）
    entries += SettingFieldEntry().apply {
        key = "litert.gpuDelegateFP16"
        type = BitPositions.SETTING_TYPE_SWITCH    // 0
        title = "GPU FP16 推理"
        description = "GPU Delegate 上使用 FP16 加速"
        defaultValue = "true"
    }

    // 3. 性能模式（下拉）
    entries += SettingFieldEntry().apply {
        key = "litert.performanceMode"
        type = BitPositions.SETTING_TYPE_DROPDOWN
        title = "性能模式"
        description = "影响 NPU 频率和能耗"
        defaultValue = "balanced"
        optionValues = listOf("performance", "balanced", "luxury")
        optionLabels = listOf("最高性能", "均衡(推荐)", "省电")
    }

    // 4. 启用视觉编码器（开关）
    entries += SettingFieldEntry().apply {
        key = "litert.enableVisionEncoder"
        type = BitPositions.SETTING_TYPE_SWITCH
        title = "启用视觉编码器"
        description = "支持图像理解 (Gemma + Siglip)"
        defaultValue = "true"
    }

    // 5. 视觉编码分辨率（滑块）
    entries += SettingFieldEntry().apply {
        key = "litert.visionResolution"
        type = BitPositions.SETTING_TYPE_SLIDER   // 2
        title = "视觉编码分辨率"
        description = "图像输入到 Siglip 的分辨率"
        defaultValue = "1024"
        minValue = 512f
        maxValue = 2048f
        step = 64f
    }

    return entries
}

override fun getDefaultSettings(): MutableMap<String, String> = mutableMapOf(
    "litert.preferredDelegate" to "auto",
    "litert.gpuDelegateFP16" to "true",
    "litert.performanceMode" to "balanced",
    "litert.enableVisionEncoder" to "true",
    "litert.visionResolution" to "1024"
)
```

### 7.3 命名约定

- key 必须以 `<pluginName>.` 前缀，如 `litert.preferredDelegate`、`mlc.temperature`，避免与其它插件冲突
- 同一插件的 key 共用同一前缀，宿主 `PluginStore` 把每个插件的设置存到独立 SharedPreferences 文件 `aibox_plugin_<pkg>`

### 7.4 宿主侧如何消费

宿主侧 [`PluginDescriptorAdapter`](../app/src/main/kotlin/com/aibox/infra/plugin/PluginDescriptorAdapter.kt) 把 `getSettingsSchema()` 返回的 `List<SettingFieldEntry>` 转成 `List<SettingField>`，喂给 [`SettingsListRenderer`](../app/src/main/kotlin/com/aibox/ui/components/unified/SchemaRenderer.kt) 自动渲染。**插件不需要写任何 UI 代码**。

---

## 8. 与宿主通信的最佳实践

### 8.1 零异常约定

所有 AIDL 方法**不抛业务异常**。失败路径：

- `loadModel` / `infer` → 返回 `LoadModelResult{success=false, errorMessage="..."}` / `InferResult{success=false, ...}`
- `streamInfer` → 调 `IStreamCallback.onError(reason)`，最后再调一次 `onChunk("", true)` 标记流结束
- `unload` / `applyOptimizations` / `setLogCallback` → 仅记日志，不报错（即使失败也不影响宿主）

### 8.2 跨进程传配置用 JSON 字符串

`InferenceConfigV2` 含 `Map<String, Any> frameworkExtras`，AIDL 不支持任意 Map 跨进程。宿主侧 [`ParcelableConverters`](../app/src/main/kotlin/com/aibox/infra/plugin/ParcelableConverters.kt) 把 `InferenceConfigV2` 序列化为 JSON 字符串。插件侧解析示例（参考 [`LiteRTSettings.kt`](../litert-plugin/app/src/main/kotlin/com/aibox/litert/backend/LiteRTSettings.kt)）：

```kotlin
data class LiteRTSettings(
    val preferredDelegate: String = "auto",
    val gpuDelegateFP16: Boolean = true,
    val performanceMode: String = "balanced",
    val enableVisionEncoder: Boolean = true,
    val visionResolution: Int = 1024
) {
    companion object {
        fun fromConfigJson(json: String): LiteRTSettings {
            return try {
                val obj = JSONObject(json)
                LiteRTSettings(
                    preferredDelegate = obj.optString("preferredDelegate", "auto"),
                    gpuDelegateFP16 = obj.optBoolean("gpuDelegateFP16", true),
                    performanceMode = obj.optString("performanceMode", "balanced"),
                    enableVisionEncoder = obj.optBoolean("enableVisionEncoder", true),
                    visionResolution = obj.optInt("visionResolution", 1024)
                )
            } catch (_: Throwable) {
                LiteRTSettings()  // 解析失败用默认值
            }
        }
    }
}
```

> **为什么用 `org.json.JSONObject` 而非 `kotlinx.serialization` / Gson**：`org.json` 是 Android 平台内置，无第三方依赖，APK 体积最小。插件工程只解析自己定义的 `<pluginName>.*` key，JSONObject 完全够用。

### 8.3 流式推理用 callback，不用返回值

AIDL 不支持返回 `Flow`。流式推理的标准做法是 callback：

```kotlin
override fun streamInfer(
    request: InferRequest?,
    modelHandle: String?,
    callback: IStreamCallback?
) {
    if (request == null || modelHandle.isNullOrBlank() || callback == null) {
        try { callback?.onError("invalid arguments") } catch (_: Throwable) {}
        return
    }
    try {
        backend.streamInfer(request, modelHandle, callback)
    } catch (e: Throwable) {
        try { callback.onError("streamInfer failed: ${e.message}") } catch (_: Throwable) {}
    }
}
```

宿主侧用 [`callbackFlow`](../app/src/main/kotlin/com/aibox/infra/plugin/PluginBackendAdapter.kt) 把 callback 桥到 Kotlin `Flow<StreamChunk>`，订阅时才发起 `streamInfer`，Flow 取消时调 `awaitClose` 清理。

### 8.4 不传 InputStream 跨进程

`byte[]` 走 binder 1MB 缓冲，大模型必爆。`ParcelFileDescriptor` 是 Android 官方姿势：

```kotlin
override fun openModelFileDescriptor(): ParcelFileDescriptor? {
    return try {
        val input = service.openModelFile()  // assets.open(...)
        ParcelFileDescriptor.fromInputStream(input)
    } catch (e: IOException) {
        Log.w(TAG, "openModelFile failed", e)
        null
    }
}
```

---

## 9. 日志回传宿主 LogSystem

### 9.1 为什么要回传

宿主有完整的 [`LogSystem`](../app/src/main/kotlin/com/aibox/util/log/LogSystem.kt)：RingBuffer + 异步刷盘 + 崩溃捕获 + 推理会话独立日志。如果插件用 `android.util.Log.i` 直接打 logcat，宿主崩溃报告里看不到插件日志，定位 bug 时需要 adb 抓双份日志。

### 9.2 实现 LiteRTLogger

参考 [`litert-plugin/app/src/main/kotlin/com/aibox/litert/LiteRTLogger.kt`](../litert-plugin/app/src/main/kotlin/com/aibox/litert/LiteRTLogger.kt)：

```kotlin
object LiteRTLogger {
    @Volatile private var callback: IPluginLogCallback? = null

    fun setCallback(cb: IPluginLogCallback?) {
        callback = cb
    }

    fun info(tag: String, msg: String, t: Throwable? = null) {
        log(Log.INFO, tag, msg, t)
    }

    private fun log(level: Int, tag: String, msg: String, t: Throwable?) {
        val fullMsg = if (t != null) "$msg | ${t.javaClass.simpleName}: ${t.message}" else msg
        try {
            callback?.onLog(level, tag, fullMsg)
            return
        } catch (_: RemoteException) {
            // callback 死亡，降级到 logcat
        }
        android.util.Log.println(level, tag, fullMsg)
    }
}
```

### 9.3 宿主侧注入时机

宿主 [`PluginManager.enablePlugin`](../app/src/main/kotlin/com/aibox/infra/plugin/PluginManager.kt) 在拿到 `IPluginInterface` 后**立即**调 `setLogCallback`，把日志回调注入插件：

```kotlin
// 宿主侧伪代码（PluginManager.enablePlugin）
pluginInterface.setLogCallback(object : IPluginLogCallback.Stub() {
    override fun onLog(level: Int, tag: String, msg: String?) {
        LogSystem.log(LogLevel.fromAndroid(level), tag, msg ?: "")
    }
})
```

注入后插件调 `LiteRTLogger.info(...)`，最终走到宿主 `LogSystem.log`，进 ring buffer + 文件 sink，**与宿主自己的日志同一份文件**，崩溃时一起刷盘。

### 9.4 Tag 命名约定

- 插件 tag 统一前缀 `aibox.plugin.<pluginName>.*`，如 `aibox.plugin.litert.binder` / `aibox.plugin.litert.descriptor`
- 宿主 [`LogSystem.setTagLevel`](../app/src/main/kotlin/com/aibox/util/log/LogSystem.kt) 支持按前缀过滤，如 `setTagLevel("aibox.plugin", LogLevel.INFO)` 一键调静插件噪声

---

## 10. 签名要求与兼容性校验

### 10.1 签名校验

宿主 [`PluginManager.verifySignature`](../app/src/main/kotlin/com/aibox/infra/plugin/PluginManager.kt) 会检查：

- API 28+：`PackageInfo.signingInfo != null`
- 低版本：`PackageManager.GET_SIGNATURES` 取 `signatures.isNotEmpty()`

**最低要求**：插件 APK 必须有签名（debug 或 release 都行，但必须有）。**未签名 APK** 会被宿主拒绝启用。

> **当前实现**只校验"签名存在性"。更严格的"签名指纹白名单"（如只允许 AIBox 官方签发的插件）可在 `verifySignature` 加 `MessageDigest.getInstance("SHA-256").digest(certs[0].toByteArray())` 比对预期指纹。

### 10.2 兼容性校验

[`PluginCompatibilityChecker`](../app/src/main/kotlin/com/aibox/infra/plugin/PluginCompatibilityChecker.kt) 检查 4 项：

1. **`aibox.minVersion`**：插件 manifest meta-data 声明所需最低宿主版本
2. **`targetSdk >= 26`**：插件 targetSdk 不能太旧
3. **ABI**：插件 APK 必须包含 `arm64-v8a` / `armeabi-v7a` / `x86_64` 任一
4. **签名**：与 `verifySignature` 一致

### 10.3 声明 minVersion

```xml
<service ...>
    <intent-filter>
        <action android:name="com.aibox.action.BACKEND_PLUGIN" />
    </intent-filter>
    <meta-data
        android:name="aibox.minVersion"
        android:value="v261018-1" />
</service>
```

宿主版本号字符串按 `.` / `-` 分段比较，数字段比数字、字符串段比字符串。所以 `v261020-1` > `v261018-1`，新宿主能跑老插件，反之不行。

### 10.4 版本号约定

宿主当前版本：[`app/build.gradle.kts`](../app/build.gradle.kts) 中 `versionName = "v261018-1"`。

格式：`v<YYMMDD>-<build>`，如 `v261020-1`。每次发布日期 +1 天，build 号从 1 递增。

---

## 11. 编译 + 发布到 GitHub Releases 流程

### 11.1 本地构建

```bash
cd /path/to/your-plugin
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

### 11.2 CI 模型 / SDK 注入

参考 [`litert-plugin/README.md`](../litert-plugin/README.md) 中的 GitHub Actions 示例。骨架：

```yaml
name: Build LiteRT Plugin

on:
  push:
    tags: ['v*']

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Setup JDK 17
        uses: actions/setup-java@v4
        with: { distribution: 'temurin', java-version: '17' }

      - name: Setup Android SDK
        uses: android-actions/setup-android@v3

      - name: Download LiteRT-LM SDK
        run: |
          curl -L -o litert.zip https://example.com/litert-lm-android.zip
          unzip litert.zip -d litert
          cp litert/libs/arm64-v8a/*.so app/src/main/jniLibs/arm64-v8a/
          cp -r litert/include app/src/main/cpp/

      - name: Build
        run: ./gradlew :app:assembleRelease

      - name: Sign
        uses: r0adkll/sign-android-release@v1
        with:
          releaseDirectory: app/build/outputs/apk/release
          signingKeyBase64: ${{ secrets.SIGNING_KEY }}
          alias: ${{ secrets.KEY_ALIAS }}
          keyStorePassword: ${{ secrets.KEYSTORE_PASSWORD }}
          keyPassword: ${{ secrets.KEY_PASSWORD }}

      - name: Upload to GitHub Releases
        uses: softprops/action-gh-release@v2
        with:
          files: app/build/outputs/apk/release/*-signed.apk
          draft: true
```

### 11.3 发布前自检清单

- [ ] `versionName` 与 manifest meta-data `aibox.version` 一致
- [ ] `applicationId` 与宿主 `com.aibox` 不冲突
- [ ] `<intent-filter>` action 正确（`BACKEND_PLUGIN` / `CHAT_MODULE`）
- [ ] `<queries>` 已在宿主侧声明（不需要在插件侧）
- [ ] `aibox.minVersion` 声明本插件所需最低宿主版本
- [ ] APK 签名（debug 或 release，必须）
- [ ] ABI 至少包含 `arm64-v8a`
- [ ] AIDL 文件 11 个（推理框架后端）或 1 个（聊天模块）从宿主工程复制，字节级一致
- [ ] ProGuard 规则保留所有 `com.aibox.plugin.*` 类
- [ ] 插件不申请 `INTERNET` 等宿主未授予的权限

### 11.4 用户安装流程

1. 用户从 GitHub Releases 下载插件 APK
2. 系统弹"安装未知来源应用"对话框，用户授权
3. 安装完成，宿主 [`PluginInstallReceiver`](../app/src/main/kotlin/com/aibox/infra/plugin/PluginInstallReceiver.kt) 收到 `PACKAGE_ADDED` 广播
4. 用户在宿主"插件"设置页看到新插件，点击启用
5. 宿主校验签名 + 兼容性 → bind Service → 注入日志回调 → 注册到 `BackendManagerV2`
6. 插件出现在宿主"后端选择"下拉框中

---

## 12. 调试技巧

### 12.1 用 `adb shell am start` 直接测试插件 Service

```bash
# 启动插件 Service（让 onCreated 日志先打出来）
adb shell am start-service \
  -n com.aibox.litert.backend/com.aibox.litert.LiteRTPluginService \
  -a com.aibox.action.BACKEND_PLUGIN

# 查看插件进程
adb shell ps | grep litert

# 查看插件日志（宿主 LogSystem 会同时打 logcat）
adb logcat -s aibox.plugin.litert.binder:I
```

### 12.2 检查宿主是否能扫到插件

```bash
# 列出所有声明了 BACKEND_PLUGIN action 的 Service
adb shell pm query-services --action com.aibox.action.BACKEND_PLUGIN
```

返回空说明：(a) 插件未装；(b) 插件 manifest 缺 intent-filter；(c) 宿主 `<queries>` 未声明。

### 12.3 验证设置 schema 渲染

启用插件后，在宿主"设置 → 插件 → <插件名>"页面应自动渲染所有 `getSettingsSchema` 返回的字段。如果空白，检查：

- `getSettingsSchema` 返回非空 `List<SettingFieldEntry>`
- 每个 entry 的 `type` 字段在 0-4 之间（对应 `FieldType` 序数）
- `key` 字段非空且唯一
- ProGuard 未把 `SettingFieldEntry` 混淆掉

---

## 13. 常见坑

### 13.1 `RemoteException` 把宿主搞崩

**症状**：插件进程死亡，宿主调用 `infer` 抛 `DeadObjectException` 导致 UI 闪退。

**修复**：插件 Binder 实现**绝不**让异常逃出 onTransact。所有方法用 `try-catch Throwable`，失败路径走 `LoadModelResult{success=false}`。宿主侧 [`PluginBackendAdapter`](../app/src/main/kotlin/com/aibox/infra/plugin/PluginBackendAdapter.kt) 也用 `try-catch RemoteException` 双保险，把异常转成 `Outcome.Failure`。

### 13.2 模型大文件跨进程传输

**症状**：调用 `openModelFileDescriptor` 后宿主读 `pfd.inputStream.read()` 返回 0 字节。

**原因**：插件侧 `ParcelFileDescriptor.fromInputStream` 把流包装成 pfd 后，**插件侧不能 close 该流**（pfd close 责任在宿主侧）。

**修复**：参考 [`EasyChatModuleBinder.kt`](../easy-chat-module/app/src/main/kotlin/com/easychat/EasyChatModuleBinder.kt)，`fromInputStream` 后不持有 pfd 引用，宿主侧用完调 `pfd.close()` 即可。

### 13.3 ProGuard 把 AIDL Stub 类混淆

**症状**：release 包安装后启用插件，宿主报 `ClassNotFoundException: com.aibox.plugin.IPluginInterface$Stub`。

**修复**：在 `proguard-rules.pro` 加 `-keep class com.aibox.plugin.** { *; }`。

### 13.4 Bitmask 错位

**症状**：插件声明支持 `TEXT_GENERATION`，但宿主 `BackendManagerV2.routeBackend` 找不到。

**原因**：[`BitPositions.kt`](../litert-plugin/app/src/main/kotlin/com/aibox/litert/BitPositions.kt) 中的 ordinal 与宿主 `TaskType.kt` 不一致（如宿主侧新增了枚举值但插件未同步）。

**修复**：每次宿主升级 `domain/inference/` 下的枚举，插件侧 `BitPositions` 必须同步更新。长期方案是依赖 `aibox-plugin-api` AAR 让所有插件共享同一份常量类。

---

## 14. 参考实现索引

| 文档章节 | 参考代码 |
| --- | --- |
| Gradle 工程结构 | [`litert-plugin/settings.gradle.kts`](../litert-plugin/settings.gradle.kts) / [`easy-chat-module/settings.gradle.kts`](../easy-chat-module/settings.gradle.kts) |
| AndroidManifest | [`litert-plugin/app/src/main/AndroidManifest.xml`](../litert-plugin/app/src/main/AndroidManifest.xml) / [`easy-chat-module/app/src/main/AndroidManifest.xml`](../easy-chat-module/app/src/main/AndroidManifest.xml) |
| AIDL 接口 | [`app/src/main/aidl/com/aibox/plugin/`](../app/src/main/aidl/com/aibox/plugin/) |
| Descriptor 实现 | [`litert-plugin/app/src/main/kotlin/com/aibox/litert/LiteRTDescriptor.kt`](../litert-plugin/app/src/main/kotlin/com/aibox/litert/LiteRTDescriptor.kt) |
| Binder 实现 | [`litert-plugin/app/src/main/kotlin/com/aibox/litert/LiteRTBinder.kt`](../litert-plugin/app/src/main/kotlin/com/aibox/litert/LiteRTBinder.kt) |
| Service 入口 | [`litert-plugin/app/src/main/kotlin/com/aibox/litert/LiteRTPluginService.kt`](../litert-plugin/app/src/main/kotlin/com/aibox/litert/LiteRTPluginService.kt) |
| SettingField schema | [`litert-plugin/app/src/main/kotlin/com/aibox/litert/LiteRTDescriptor.kt`](../litert-plugin/app/src/main/kotlin/com/aibox/litert/LiteRTDescriptor.kt) (getSettingsSchema) |
| Bitmask 常量 | [`litert-plugin/app/src/main/kotlin/com/aibox/litert/BitPositions.kt`](../litert-plugin/app/src/main/kotlin/com/aibox/litert/BitPositions.kt) |
| 日志回传 | [`litert-plugin/app/src/main/kotlin/com/aibox/litert/LiteRTLogger.kt`](../litert-plugin/app/src/main/kotlin/com/aibox/litert/LiteRTLogger.kt) |
| ProGuard 规则 | [`litert-plugin/app/proguard-rules.pro`](../litert-plugin/app/proguard-rules.pro) |
| 模块（聊天）实现 | [`easy-chat-module/app/src/main/kotlin/com/easychat/`](../easy-chat-module/app/src/main/kotlin/com/easychat/) |
| 宿主侧 AIDL 适配 | [`app/src/main/kotlin/com/aibox/infra/plugin/`](../app/src/main/kotlin/com/aibox/infra/plugin/) |

---

## 15. 下一步

- 阅读 [`ARCHITECTURE.md`](./ARCHITECTURE.md) 了解宿主侧 `BackendManagerV2` / `PluginManager` / `PluginBackendAdapter` 的整体架构
- 阅读 [`BENCHMARK_METHODOLOGY.md`](./BENCHMARK_METHODOLOGY.md) 了解如何用基准测试验证你的插件性能
- 阅读 [`PERFORMANCE_TUNING.md`](./PERFORMANCE_TUNING.md) 了解 `OptimizationFlags` 各开关对推理性能的影响
- 阅读 [`RELEASE_CHECKLIST.md`](./RELEASE_CHECKLIST.md) 了解 v261020-1 pre-release 前的完整检查清单
