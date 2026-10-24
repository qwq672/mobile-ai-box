# LiteRT-LM Backend Plugin

AIBox 第一个**推理框架插件** APK(区别于 Easy Chat 模块 APK:Easy Chat 打包模型,本插件打包**推理框架**)。

## 插件定位

| 项目 | 值 |
| --- | --- |
| 插件名 | `litert` |
| 显示名 | LiteRT-LM (Google) |
| 类型 | `backend`(推理后端) |
| 推理框架 | Google **LiteRT-LM**(原 TensorFlow Lite for LLM) |
| 支持模型 | Gemma 2 系列(必须 .tflite 格式) |
| 多模态 | Siglip 视觉编码器 + Gemma 解码器 |
| 后端 | NPU(Hexagon V73 Delegate)/ GPU(GPU Delegate FP16)/ CPU |
| 内置模型 | **不内置任何模型**,用户自行下载 .tflite |
| 内置推理框架 | **不内置宿主 APK**,独立分发 |
| APK 大小 | ~5 MB(仅 .so + Kotlin + assets;不含模型) |

**核心定位:补充不替代 llama.cpp**。在 Pixel / 骁龙 8 Gen3 等设备上跑 Gemma 时可用 NPU 加速;
对于 Qwen / DeepSeek / Llama 系列仍由宿主内置 llama.cpp 后端处理。

## 工程结构

```
litert-plugin/                                    # 独立 Gradle 工程,rootProject.name = LiteRTLMPlugin
├── settings.gradle.kts
├── build.gradle.kts                              # 顶层:声明 AGP 8.5.2 + Kotlin 2.0.20
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties      # Gradle 8.10.1
├── README.md
├── .gitignore
└── app/
    ├── build.gradle.kts                          # namespace=com.aibox.litert
    ├── proguard-rules.pro                        # 保 AIDL Stub / Service / Descriptor / native 方法
    └── src/main/
        ├── AndroidManifest.xml                   # Service + intent-filter + meta-data
        ├── assets/
        │   └── plugin.json                        # ★ 宿主读取的权威元数据
        ├── aidl/com/aibox/plugin/                 # 11 个 AIDL 文件,复制自宿主 P1.2
        │   ├── IPluginInterface.aidl             #   推理运行时接口
        │   ├── IPluginDescriptor.aidl             #   元信息接口
        │   ├── IStreamCallback.aidl              #   流式推理回调
        │   ├── IPluginLogCallback.aidl            #   日志回传宿主
        │   ├── PluginInfo.aidl                    #   parcelable
        │   ├── SettingFieldEntry.aidl             #   parcelable
        │   ├── BackendCapabilitiesParcel.aidl    #   parcelable
        │   ├── LoadModelRequest.aidl              #   parcelable
        │   ├── LoadModelResult.aidl               #   parcelable
        │   ├── InferRequest.aidl                  #   parcelable
        │   └── InferResult.aidl                   #   parcelable
        ├── kotlin/com/aibox/litert/
        │   ├── LiteRTPluginService.kt             # Service 入口
        │   ├── LiteRTBinder.kt                    # IPluginInterface.Stub 实现
        │   ├── LiteRTDescriptor.kt                # IPluginDescriptor.Stub 实现
        │   ├── BitPositions.kt                    # 宿主枚举 ordinal 常量(本地复制)
        │   ├── LiteRTLogger.kt                    # 日志桥(IPluginLogCallback → 宿主 LogSystem)
        │   └── backend/
        │       ├── LiteRTBackend.kt               # ★ 推理核心(骨架)
        │       ├── LiteRTSettings.kt              # 解析宿主 configJson → 设置对象
        │       ├── ModelType.kt                    # TEXT_ONLY / MULTIMODAL
        │       ├── DelegateDetector.kt            # NPU/GPU/CPU 可用性检测
        │       └── MultimodalPipeline.kt          # Siglip + Gemma 骨架
        ├── cpp/
        │   ├── CMakeLists.txt                     # 构建 libaibox_litert.so
        │   └── litert_bridge.cpp                  # JNI 桥接骨架
        └── jniLibs/arm64-v8a/
            └── .gitkeep                           # ★ CI 注入 LiteRT-LM .so 的目标目录
```

## 与宿主的通信契约

### 发现阶段(无需绑定)

宿主用 PackageManager 扫描 `action="com.aibox.action.BACKEND_PLUGIN"` 的 Service,读到:

- `aibox.descriptor_class` = `com.aibox.litert.LiteRTDescriptor`
- `aibox.version` = `1.0.0`

宿主可立即用 `Class.forName(descriptorClass).newInstance() as IPluginDescriptor`
调用 `describeCapabilities()` 判断本插件能跑什么任务,再决定是否进入绑定阶段。

### 绑定阶段

宿主 `bindService` 到本 Service,`onServiceConnected` 拿到 `IBinder`,
通过 `IPluginInterface.Stub.asInterface(binder)` 转接口,调用:

| 方法 | 返回 | 用途 |
| --- | --- | --- |
| `getPluginInfo()` | `PluginInfo` | 包名 / 版本 / 显示名 / 大小(双路校验) |
| `setLogCallback(callback)` | `void` | 注入日志桥(必须最先调,让插件后续日志回传宿主) |
| `applyOptimizations(flags)` | `void` | 注入 P3 优化开关(preloadModel / warmupAfterLoad 等) |
| `loadModel(request)` | `LoadModelResult` | 加载 .tflite,返回 modelHandle |
| `infer(request, handle)` | `InferResult` | 同步推理 |
| `streamInfer(request, handle, callback)` | `void` | 流式推理(逐 token 回传) |
| `unload(handle)` | `void` | 释放模型 |

宿主通过 `IPluginDescriptor`(反射创建的实例)调用元信息接口:

| 方法 | 返回 | 用途 |
| --- | --- | --- |
| `getName()` | `"litert"` | 后端唯一名 |
| `describeCapabilities()` | `BackendCapabilitiesParcel` | bitmask 形式能力声明 |
| `getSettingsSchema()` | `List<SettingFieldEntry>` | 动态生成设置 UI |
| `getDefaultSettings()` | `Map<String, String>` | 默认设置值 |

## plugin.json 后端能力摘要

| 字段 | 默认值 | 备注 |
| --- | --- | --- |
| `backend.capabilities.supportedTasks` | text\_generation / multimodal\_understanding / embedding | Gemma 支持 embedding |
| `backend.capabilities.supportedInputModalities` | text + image | Siglip 编码图像 |
| `backend.capabilities.supportedOutputModalities` | text only | Gemma 不输出图像 |
| `backend.capabilities.maxContextLength` | 8192 | Gemma 2 上下文长度 |
| `backend.capabilities.maxImageResolution` | 2048 | Siglip 最高分辨率 |
| `backend.capabilities.supportedModelFormats` | `["tflite"]` | 不接受 .gguf |
| `backend.capabilities.supportedQuantizations` | int8 / fp16 / bf16 | LiteRT-LM 支持的量化 |
| `backend.capabilities.features` | hexagon\_npu / streaming / multimodal / function\_calling | 4 项特性 |
| `backend.delegatePriority` | `["npu","gpu","cpu"]` | 自动降级顺序 |
| `backend.multimodalSupport.encoder` | `siglip` | 视觉编码器 |
| `backend.multimodalSupport.decoder` | `gemma` | 文本解码器 |
| `backend.multimodalSupport.visionResolution` | `[512,1024,2048]` | 用户可选 |

## 用户可见设置(SchemaRenderer 动态生成)

宿主用 `getSettingsSchema()` 拿 5 个字段,经 `UnifiedDropdown` / `UnifiedSwitch` /
`UnifiedSlider` 渲染到设置面板:

| key | type | 默认值 | 选项 / 范围 |
| --- | --- | --- | --- |
| `litert.preferredDelegate` | DROPDOWN | `auto` | auto / npu / gpu / cpu |
| `litert.gpuDelegateFP16` | SWITCH | `true` | true / false |
| `litert.performanceMode` | DROPDOWN | `balanced` | performance / balanced / luxury |
| `litert.enableVisionEncoder` | SWITCH | `true` | true / false |
| `litert.visionResolution` | SLIDER | `1024` | 512-2048,步长 64 |

用户改动后,宿主把新值塞进 `InferenceConfigV2.frameworkExtras`,通过下一次
`loadModel(request.configJson)` 下发到本插件(`LiteRTSettings.fromConfigJson` 解析)。

## 构建与 .so 注入

### 本地构建(骨架阶段)

```bash
cd /home/z/my-project/download/aibox/litert-plugin
gradle wrapper           # 首次生成 gradlew / gradle-wrapper.jar
./gradlew :app:assembleRelease
# 产物:app/build/outputs/apk/release/app-release.apk
# 注:此时 libaibox_litert.so 是空骨架,只能让 System.loadLibrary 成功,推理返回占位 JSON
```

### CI 注入 LiteRT-LM .so

```yaml
# .github/workflows/build-litert-plugin.yml(示例,本 Task 范围未创建)
- name: Download LiteRT-LM SDK
  run: |
    curl -L https://github.com/google-ai-edge/LiteRT-LM/releases/download/v0.1/litert-lm-android-aar.zip -o litert-lm.zip
    unzip litert-lm.zip -d litert-lm-aar

- name: Extract .so to jniLibs
  run: |
    mkdir -p app/src/main/jniLibs/arm64-v8a
    unzip litert-lm-aar/*.aar -d litert-lm-extracted
    cp litert-lm-extracted/jni/arm64-v8a/*.so app/src/main/jniLibs/arm64-v8a/

- name: Extract headers to cpp/include
  run: |
    mkdir -p app/src/main/cpp/include
    cp -r litert-lm-extracted/include/* app/src/main/cpp/include/

- name: Build APK
  run: ./gradlew :app:assembleRelease
```

### QNN SDK(Hexagon NPU Delegate)注入

QNN SDK 由 Qualcomm 官方提供(需授权),不公开下载;CI 配置:

```yaml
- name: Download QNN SDK
  run: |
    # 从 Qualcomm Platform Tools 下载(QNN-2.x-linux-aarch64.zip)
    # 把 libQnnHtp.so / libQnnSystem.so 等放到 app/src/main/jniLibs/arm64-v8a/
    # 把 QnnInterface.h 等放到 app/src/main/cpp/include/QNN/
```

## 用户使用步骤

1. 安装 AIBox 宿主 APK(若未安装)。
2. 安装本插件 APK(`com.aibox.litert.backend`)。
3. AIBox 宿主扫描到 BACKEND\_PLUGIN action,显示"LiteRT-LM 后端已就绪"。
4. 用户从 HuggingFace 等下载 Gemma .tflite(如 `gemma-2b-it-int8.tflite`),
   放到 AIBox 模型库目录(`Android/data/com.aibox/files/models/`)。
5. 在 AIBox Chat 界面选模型 → 选后端 LiteRT-LM。
6. 调整 LiteRT 专属设置(delegate / 性能模式 / 视觉分辨率)。
7. 开始聊天;NPU/GPU 路径由 LiteRT-LM 自动调度。

## 与 Easy Chat 模块 / 宿主工程的差异

| 项目 | AIBox 宿主 | Easy Chat 模块 | **LiteRT-LM 插件** |
| --- | --- | --- | --- |
| applicationId | `com.aibox` | `com.easychat.aibox.module` | `com.aibox.litert.backend` |
| Intent Action | (不导出) | `com.aibox.action.CHAT_MODULE` | `com.aibox.action.BACKEND_PLUGIN` |
| 内置模型 | ❌ 用户下载 | ✅ Qwen2.5-0.5B(485MB) | ❌ 用户下载 .tflite |
| 内置推理框架 | ✅ llama.cpp(C++) | ❌ 复用宿主 | ✅ LiteRT-LM(.so) |
| AIDL 角色 | 服务端(IChatModuleInterface 等的客户端) | 实现 IChatModuleInterface | 实现 IPluginInterface + IPluginDescriptor |
| Native 依赖 | llama.cpp 编译进宿主 .so | 无 | liblitert\_lm.so + libQnnHtp.so(CI 注入) |
| Activity | 有(MainActivity) | 无 | 无 |
| 文件大小 | ~50MB(含 llama.cpp) | ~485MB(模型) | ~5MB(仅 .so) |
| 用户安装场景 | 必装(主体) | 装了即开箱用 0.5B 聊天 | 装了让 AIBox 多一个 NPU 后端可选 |

## 已知遗留 / 后续工作

1. **CI 注入 LiteRT-LM .so**:本 Task 只搭骨架,真实 .so 由 CI 下载 + 解压 + 注入
   到 `app/src/main/jniLibs/arm64-v8a/` + `app/src/main/cpp/include/`;CI 配置未创建。
2. **真实 nativeLoadModel 实现**:litert_bridge.cpp 的 TODO 标记了集成点,后续接 LiteRT-LM SDK 后填充。
3. **Siglip 视觉编码器 .tflite 路径**:多模态模型可能需要分别加载 Siglip + Gemma 两个 .tflite,
   或一个联合 .tflite;具体路径由 LiteRT-LM SDK 文档决定,本骨架不假设。
4. **宿主侧 <queries> 配置**:Android 11+ 宿主工程 AndroidManifest 需要加
   `<queries><intent><action android:name="com.aibox.action.BACKEND_PLUGIN"/></intent></queries>`,
   否则宿主 PackageManager.queryIntentServices 返回空;本 Task 范围内未触碰宿主工程。
5. **宿主侧 IPluginInterface AIDL 复制**:宿主工程 `app/src/main/aidl/com/aibox/plugin/`
   已经有这 11 个 AIDL 文件(P1.2 创建),与本工程复制的副本字节一致(后续发布成 AAR 后移除副本)。
6. **签名配置**:release buildType 没配 signingConfig,实际发布需要 keystore;留给发布工程师配置。
7. **环境无 Android SDK + NDK,未跑 :app:assembleRelease**:编译正确性靠人工 review
   AGP 8.5.2 + Kotlin 2.0.20 + CMake 3.22.1 + AIDL 语法 + JNI 命名约定。

## 许可

Apache-2.0(与 LiteRT-LM / Gemma 上游一致)。
