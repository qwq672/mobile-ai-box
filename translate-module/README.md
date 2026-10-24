# 翻译模块(Translate Module)

AIBox 独立模块 APK 之一。定位"组合工作流协调器":不打包 ASR / TTS / MT 实现,
不打包任何模型,只负责协调 **ASR(听) → MT(译) → TTS(说)** 三步工作流;
具体 ASR / TTS 用宿主 AIBox 的 SystemAsrProvider / SystemTtsProvider(基于系统
SpeechRecognizer / TextToSpeech),MT 用宿主 BackendManagerV2 路由用户选择的
LLM 后端做翻译推理。

## 模块定位

| 项目 | 值 |
| --- | --- |
| 模块名 | `translate` |
| 显示名 | 翻译 |
| 类型 | `translate`(组合工作流) |
| 打包模型 | **无**(0 个 .gguf / .tflite / .onnx) |
| 打包 ASR/TTS 实现 | **无**(用宿主 AudioProviderRegistry 的 "system" provider) |
| 打包 MT 实现 | **无**(用宿主 BackendManagerV2 + 用户选择模型) |
| APK 体积 | 极小(<100KB,纯协调逻辑 + module.json) |
| 权限 | 无(<uses-permission> 不申请;RECORD_AUDIO 由宿主持有) |

模块只负责五件事:

1. 用 `assets/module.json` 描述工作流(三步 + 各步用哪个宿主后端 + 配置 + prompt 模板);
2. 用 `AndroidManifest.xml` 的 `<service>` + `<intent-filter>` 暴露入口;
3. 用 AIDL `ITranslateModuleInterface` 暴露翻译启动 / 取消 / 元数据查询 API;
4. 用 `TranslationOrchestrator` 协调三步异步工作流,通过 `ITranslateCallback` 回传进度;
5. 用 `TranslationHostBridge` 接口定义模块对宿主能力的依赖契约(等宿主侧补反向 AIDL)。

## 工程结构

```
translate-module/
├── settings.gradle.kts               # 独立 Gradle 工程,rootProject.name = Translate
├── build.gradle.kts                   # 顶层,声明插件版本
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── README.md                          # 本文件
├── .gitignore
└── app/
    ├── build.gradle.kts               # AGP + Kotlin + coroutines,namespace=com.aibox.translate
    ├── proguard-rules.pro             # 保 AIDL Stub / Service / Descriptor / Orchestrator 不被混淆
    └── src/main/
        ├── AndroidManifest.xml        # Service + meta-data,零权限
        ├── assets/
        │   ├── module.json            # ★ 工作流描述 + 支持语言列表 + prompt 模板
        │   └── .gitkeep
        ├── aidl/com/aibox/plugin/
        │   └── ITranslateModuleInterface.aidl   # 含 ITranslateModuleInterface + ITranslateCallback
        └── kotlin/com/aibox/translate/
            ├── TranslateModuleService.kt       # onBind 返回 Binder;生命周期内持 orchestrator
            ├── TranslateModuleBinder.kt        # ITranslateModuleInterface.Stub 实现
            ├── TranslateModuleDescriptor.kt    # meta-data 中引用的描述符
            ├── TranslationOrchestrator.kt       # ★ 核心逻辑:三步工作流协调 + TranslationHostBridge + TranslationResult + TranslationCallback
            └── Outcome.kt                      # 本模块自带的零异常结果类型(与宿主 com.aibox.util.outcome.Outcome 等价)
```

## 与宿主的通信契约

### 发现阶段(无需绑定)

宿主用 PackageManager 扫描 `action="com.aibox.action.TRANSLATE_MODULE"` 的 Service,读到:

- `aibox.module.name` = `translate`
- `aibox.module.displayName` = `翻译`
- `aibox.module.version` = `1.0.0`
- `aibox.module.type` = `translate`
- `aibox.module.class` = `com.aibox.translate.TranslateModuleDescriptor`

宿主据此识别"这是一个翻译模块",决定是否进入下一阶段。

### 绑定阶段

宿主 `bindService` 到本 Service,`onServiceConnected` 拿到 `IBinder`,
通过 `ITranslateModuleInterface.Stub.asInterface(binder)` 转接口,调用:

| 方法 | 返回 | 用途 |
| --- | --- | --- |
| `getModuleMetadata()` | `String` (JSON) | 拿 `assets/module.json` 全文,解析得到工作流 + 支持语言 + prompt 模板 |
| `getSupportedLanguages()` | `String` (JSON array) | 单独拿语言列表;避免每次解析完整 module.json |
| `setTranslationCallback(callback)` | `void` | 注入 `ITranslateCallback`,后续 startTranslation 通过它回传进度 |
| `startTranslation(text, srcLang, tgtLang, sid)` | `String` (sessionId) | 启动一次异步翻译;立即返回 sid,结果通过 callback 回传 |
| `cancelTranslation(sid)` | `void` | 取消指定会话;幂等;取消后回调 onError("cancelled") |

### 回调阶段(ITranslateCallback)

一次翻译会话的事件序列:

```
onProgress(1, "ASR")                   # Step 1 开始(若 sourceText 为空)
onProgress(2, "MT")                    # Step 1 完成 / 跳过,Step 2 开始
onPartialResult("翻译")                # MT 流式产出(可多次)
onPartialResult("翻译结果")
onProgress(3, "TTS")                   # Step 2 完成,Step 3 开始
onComplete("最终译文", "/path/to/audio.wav")  # 全部完成
```

或失败 / 取消路径:

```
onProgress(...) → onError("asr_failed: ...")      # 任意步骤失败
cancelTranslation(sid) → onError("cancelled")     # 宿主主动取消
```

## module.json 工作流描述

```json
{
  "workflow": {
    "step1": {
      "name": "ASR",
      "uses": "system_asr",          // 委托宿主 AudioProviderRegistry.getDefaultAsrProvider()
      "config": { "language": "auto", "fallback": "zh-CN" }
    },
    "step2": {
      "name": "MT",
      "uses": "llm_backend",         // 委托宿主 BackendManagerV2 路由 LLM
      "config": {
        "model": "user_selected",    // 宿主 UI 让用户选;支持 qwen2.5-0.5b / 1.5b / hunyuan-1.8b
        "promptTemplate": "Translate the following {source_lang} text to {target_lang}. ..."
      }
    },
    "step3": {
      "name": "TTS",
      "uses": "system_tts",          // 委托宿主 AudioProviderRegistry.getDefaultTtsProvider()
      "config": { "language": "target_lang" }
    }
  }
}
```

## 三步工作流实现要点

### Step 1: ASR(听)

- 触发条件:`sourceText` 为空时执行;非空跳过(文本输入回退路径)
- 实现:委托 `TranslationHostBridge.startAsr(sid, language)`
- 宿主侧实现:基于 `android.speech.SpeechRecognizer`,零延迟、无需模型
- 失败处理:`onError("asr_failed: ...")`,工作流终止

### Step 2: MT(译)

- 触发条件:ASR 完成(或被跳过)
- 实现:委托 `TranslationHostBridge.runTranslationStream(..., onPartial)`
- 宿主侧实现:
  1. 用 `module.json.workflow.step2.config.promptTemplate` 构造 prompt,替换占位符
     `{source_lang}` / `{target_lang}` / `{text}`
  2. 调 `BackendManagerV2.routeBackend()` 找到能跑 `TaskType.TEXT_GENERATION` 的后端
  3. 调 `InferenceBackendV2.streamInfer()` 流式产出译文
  4. 每个 chunk 调 `onPartial(累计译文)`
- 失败处理:`onError("mt_failed: ...")`,工作流终止
- 空结果处理:`onError("mt_empty_result")`(LLM 返回空串)

### Step 3: TTS(说)

- 触发条件:MT 完成
- 实现:委托 `TranslationHostBridge.startTts(sid, text, language)`
- 宿主侧实现:基于 `android.speech.tts.TextToSpeech`
- **失败非致命**:TTS 失败时仍调 `onComplete(译文, "")`,译文可用,用户可手动朗读
- 成功:`onComplete(译文, 音频路径)`

## 取消与生命周期

- `cancelTranslation(sid)`:取消指定会话,调 `Job.cancelAndJoin()`,然后 `onError("cancelled")`
- `TranslateModuleService.onDestroy()` → `orchestrator.cancelAll()`:停止所有 in-flight 会话
- 每个会话是独立 Job;SupervisorJob 让单会话失败不影响其他

## 构建与安装

### 本地构建

```bash
cd translate-module
./gradlew :app:assembleRelease
# 输出:app/build/outputs/apk/release/app-release.apk
# 体积:极小(无模型 + 无 ASR/TTS/MT 实现)
```

### 安装到设备

```bash
adb install app/build/outputs/apk/release/app-release.apk
# 安装后宿主 AIBox 启动时自动发现(若已配置 <queries> 或 query 权限)
```

## 骨架状态与后续工作

本工程是**功能骨架**,以下部分待补:

### 1. TranslationHostBridge 的真实实现(待宿主侧补反向 AIDL)

当前 `TranslateModuleService` 中 `hostBridge = null`,所有 step 调用立即返回
`Failure("host_bridge_not_attached")`。后续工作:

1. 宿主侧定义 AIDL 接口 `IHostTranslationServices.aidl`,含:
   - `String startAsr(String sessionId, String language)` (异步,通过回调返回结果)
   - `void runTranslation(String sid, String sourceText, String sourceLang, String targetLang, IHostStreamCallback callback)`
   - `String startTts(String sid, String text, String language)`
2. 宿主侧 Service 实现 `IHostTranslationServices.Stub`,内部委托
   `AudioProviderRegistry` + `BackendManagerV2`
3. 模块侧 `TranslateModuleService.onCreate` 中反向 `bindService` 到宿主,
   拿到 `IBinder` 后包装成 `TranslationHostBridge` 实现并注入 `TranslationOrchestrator`

### 2. 历史记录与收藏

`module.json.features` 列出 `"history"` 和 `"favorites"`,但本骨架未实现持久化;
宿主侧可自行持久化 `ITranslateCallback.onComplete` 的结果。

### 3. 文本输入回退

已支持:`sourceText` 非空时跳过 ASR 直接进 MT。

### 4. 流式 MT

已支持:`TranslationHostBridge.runTranslationStream` 接收 `onPartial` 回调,
宿主侧应映射到 `InferenceBackendV2.streamInfer()` 的 Flow chunks。

## 与宿主工程的差异

| 项目 | AIBox 宿主 | 翻译模块 |
| --- | --- | --- |
| `applicationId` | `com.aibox` | `com.aibox.translate.module` |
| `namespace` | `com.aibox` | `com.aibox.translate` |
| 打包推理框架 | 是(C++ + JNI) | **否**,复用宿主 |
| 打包 ASR/TTS | 是(SystemAsrProvider / SystemTtsProvider) | **否**,复用宿主 |
| 模型文件来源 | 用户下载 / 内置 / 模块注入 | **无模型**(协调器逻辑) |
| AIDL 角色 | 实现侧(IPluginInterface)+ 调用侧 | 实现侧(ITranslateModuleInterface)+ 调用侧(IHostTranslationServices) |
| Activity | 有(主界面) | 无(纯模块) |
| 权限 | RECORD_AUDIO 等 | 无 |

## 与 Easy Chat 模块的差异

| 项目 | Easy Chat | Translate |
| --- | --- | --- |
| `type` | `chat` | `translate` |
| 打包模型 | 1 个 GGUF(~485MB) | 无 |
| 打包推理框架 | 否(用宿主 llama.cpp) | 否(用宿主 LLM 后端 + 系统 ASR/TTS) |
| 工作流 | 单步(LLM 推理) | 三步(ASR → MT → TTS) |
| `module.json` 重点 | 模型推荐配置 | 工作流描述 + 支持语言 |
| 失败模式 | LLM 失败 | 任意 step 失败 / TTS 失败非致命 |
| AIDL 接口 | `IChatModuleInterface`(拉模型文件) | `ITranslateModuleInterface`(启动 / 取消 / 回调) |

## 许可

MIT,见项目根 LICENSE。
