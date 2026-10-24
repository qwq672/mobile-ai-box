# AIBox 架构设计

本文档描述 AIBox 宿主与插件的整体架构，帮助开发者理解各模块职责、关键抽象、生命周期与跨模块约定。

参考实现见 [`app/src/main/kotlin/com/aibox/`](../app/src/main/kotlin/com/aibox/) 全目录。

---

## 1. 总体架构图

```
┌─────────────────────────────────────────────────────────────────────┐
│                          UI 层 (Compose)                            │
│   ChatScreen  ModelsScreen  BenchmarkScreen  OptimizationSettings   │
│      HistoryScreen  SettingsScreen  DownloadScreen                  │
│            │ 用 DesignTokens + UnifiedXxx + SchemaRenderer          │
└─────────────┬──────────────────────────────────┬────────────────────┘
              │                                  │
              │ StateFlow / Flow                  │ 调 ViewModel
              ▼                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        ViewModel 层                                  │
│  ChatViewModel  ModelsViewModel  BenchmarkViewModel  ...            │
└─────────────┬──────────────────────────────────┬────────────────────┘
              │                                  │
              ▼                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     Domain 层 (业务逻辑)                             │
│  ┌────────────────────┐  ┌─────────────────┐  ┌──────────────────┐  │
│  │ inference (V1+V2)  │  │  optimization   │  │   download       │  │
│  │ InferenceEngine    │  │ OptimizationMgr │  │   DownloadManager│  │
│  │ BackendManagerV2   │  │ Ttft/Tg/Energy  │  │   ModelSource x3 │  │
│  │ InferenceBackendV2 │  │                 │  │                  │  │
│  └─────────┬──────────┘  └─────────────────┘  └──────────────────┘  │
│            │                                                        │
└────────────┼────────────────────────────────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     Infra 层 (基础设施)                              │
│  ┌──────────────┐  ┌──────────────┐  ┌───────────────────────────┐   │
│  │  plugin      │  │  benchmark   │  │  log + crash             │   │
│  │  PluginMgr   │  │  Basic/Adv/  │  │  LogSystem + CrashHandler│   │
│  │  AIDL x11    │  │  Comparison  │  │  NativeCrashHandler      │   │
│  │  PluginStore │  │  Storage     │  │  RingBuffer + FileSink    │   │
│  └──────────────┘  └──────────────┘  └───────────────────────────┘   │
│  ┌──────────────┐  ┌─────────────────────────────────────────────┐   │
│  │  jni         │  │  crash/cpp (SIGSEGV/SIGABRT signal handler) │   │
│  │  NativeBridge │  └─────────────────────────────────────────────┘   │
│  └──────────────┘                                                    │
└─────────────────────────────────────────────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────────────────────────────────┐
│                       Native 层 (C++)                              │
│   bridge.cpp  native_bridge.cpp  memory_pool.cpp  crash_handler.cpp │
│                llama.cpp (linked as subdirectory)                   │
└─────────────────────────────────────────────────────────────────────┘
```

外部插件 APK 独立进程，通过 AIDL Binder 与宿主通信：

```
┌─────────────────────────────────┐   ┌─────────────────────────────┐
│   宿主进程 com.aibox            │   │   插件进程                  │
│                                  │   │   com.aibox.litert.backend  │
│   PluginManager ──── bindService │──►│   LiteRTPluginService      │
│        │                         │   │       │ onBind             │
│        ▼                         │   │       ▼                    │
│   PluginBackendAdapter           │◄──│   LiteRTBinder              │
│   (实现 InferenceBackendV2)     │   │   (实现 IPluginInterface)   │
│        │                         │   │       │                    │
│        ▼                         │   │       ▼                    │
│   BackendManagerV2               │   │   LiteRTBackend             │
│        │                         │   │   (native: litert_bridge.cpp)│
│        ▼                         │   └─────────────────────────────┘
│   ChatViewModel                  │
└─────────────────────────────────┘
```

---

## 2. 分层

### 2.1 基础设施层 (`infra/`)

跨业务复用的低层组件，不依赖任何 Domain / UI 代码：

- [`infra/plugin/`](../app/src/main/kotlin/com/aibox/infra/plugin/)：插件生命周期（AIDL 适配 + PluginManager + 持久化 + 兼容性校验）
- [`infra/benchmark/`](../app/src/main/kotlin/com/aibox/infra/benchmark/)：基准测试三层 + 环境监控 + 能耗测量 + Room 持久化
- [`infra/crash/`](../app/src/main/kotlin/com/aibox/infra/crash/)：Java/Kotlin 异常 + Native 信号崩溃捕获
- [`infra/jni/`](../app/src/main/kotlin/com/aibox/infra/jni/)：JNI 桥（NativeBridge）

### 2.2 抽象层 (`domain/inference/`)

定义跨后端 / 跨框架 / 跨任务的统一契约：

- [`InferenceBackendV2`](../app/src/main/kotlin/com/aibox/domain/inference/InferenceBackendV2.kt)：推理后端接口
- [`BackendDescriptor`](../app/src/main/kotlin/com/aibox/domain/inference/BackendDescriptor.kt)：元信息接口
- [`BackendManagerV2`](../app/src/main/kotlin/com/aibox/domain/inference/BackendManagerV2.kt)：多后端路由器
- [`InferenceConfigV2`](../app/src/main/kotlin/com/aibox/domain/inference/InferenceConfigV2.kt) + [`OptimizationFlags`](../app/src/main/kotlin/com/aibox/domain/inference/InferenceConfigV2.kt)：配置与优化开关
- [`TaskType`](../app/src/main/kotlin/com/aibox/domain/inference/TaskType.kt) / [`Modality`](../app/src/main/kotlin/com/aibox/domain/inference/TaskType.kt) / [`BackendCapabilities`](../app/src/main/kotlin/com/aibox/domain/inference/BackendCapabilities.kt)：能力声明
- [`InferenceRequest`](../app/src/main/kotlin/com/aibox/domain/inference/InferenceRequest.kt) / [`InferenceResult`](../app/src/main/kotlin/com/aibox/domain/inference/InferenceResult.kt)：请求 / 响应（含多模态）

### 2.3 后端层 (`domain/inference/` V1 实现)

V1 Backend 接口实现，目前已有：

- [`CpuBackend`](../app/src/main/kotlin/com/aibox/domain/inference/CpuBackend.kt)：CPU（llama.cpp）
- [`VulkanBackend`](../app/src/main/kotlin/com/aibox/domain/inference/VulkanBackend.kt)：Vulkan GPU
- [`OpenClBackend`](../app/src/main/kotlin/com/aibox/domain/inference/OpenClBackend.kt)：OpenCL GPU
- [`NpuBackend`](../app/src/main/kotlin/com/aibox/domain/inference/NpuBackend.kt)：NPU（Hexagon）
- [`InferenceEngine`](../app/src/main/kotlin/com/aibox/domain/inference/InferenceEngine.kt)：V1 统一调度（fallback chain）

V2 与 V1 共存：V2 不要求 V1 Backend 实现 V2 接口，后续通过"V1 → V2 适配器"逐步迁移。

### 2.4 模块层 (`domain/optimization/` `domain/download/`)

业务领域逻辑：

- [`optimization/`](../app/src/main/kotlin/com/aibox/domain/optimization/)：TTFT / TG / 能耗优化器 + OptimizationManager
- [`download/`](../app/src/main/kotlin/com/aibox/domain/download/)：三下载源（HuggingFace / ModelScope / HF Mirror）

### 2.5 UI 层 (`ui/`)

Compose UI，全部走 Design Token + 统一组件库：

- [`ui/theme/DesignTokens.kt`](../app/src/main/kotlin/com/aibox/ui/theme/DesignTokens.kt)：Design Token 唯一来源
- [`ui/components/unified/`](../app/src/main/kotlin/com/aibox/ui/components/unified/)：6 个统一组件 + SchemaRenderer + Templates
- 各业务页（`ui/chat/` `ui/models/` `ui/benchmark/` `ui/optimization/` `ui/settings/` `ui/history/` `ui/download/`）

---

## 3. 关键抽象

### 3.1 InferenceBackendV2

[`InferenceBackendV2.kt`](../app/src/main/kotlin/com/aibox/domain/inference/InferenceBackendV2.kt)

```kotlin
interface InferenceBackendV2 {
    fun getName(): String
    fun getDisplayName(): String
    fun getVersion(): String
    fun describeCapabilities(): BackendCapabilities

    fun loadModel(modelPath: String, config: InferenceConfigV2): Outcome<LoadedModel>
    fun infer(request: InferenceRequest, loadedModel: LoadedModel): Outcome<InferenceResult>
    fun streamInfer(request: InferenceRequest, loadedModel: LoadedModel): Flow<InferenceResult.StreamChunk>
    fun unload(model: LoadedModel)
    fun applyOptimizations(flags: OptimizationFlags)
}
```

设计原则：

1. **零异常约定**：所有可失败方法返回 `Outcome`，不抛异常
2. **不可变**：`LoadedModel` 是 data class，运行期不修改字段
3. **多任务 / 多模态**：通过 `InferenceRequest.taskType` + `ModalityInput` 子类组合表达
4. **能力声明**：`describeCapabilities()` 让 `BackendManagerV2` 做路由决策
5. **生命周期**：`loadModel` → `infer` / `streamInfer` → `unload`
6. **优化开关**：`applyOptimizations` 在 `loadModel` 之前调用

### 3.2 BackendDescriptor

[`BackendDescriptor.kt`](../app/src/main/kotlin/com/aibox/domain/inference/BackendDescriptor.kt)

```kotlin
interface BackendDescriptor {
    fun getName(): String
    fun getDisplayName(): String
    fun getVersion(): String
    fun describeCapabilities(): BackendCapabilities
    fun getSettingsSchema(): List<SettingField>
    fun getDefaultSettings(): Map<String, Any>
    fun parseSettings(values: Map<String, Any>): InferenceConfigV2
}
```

与 `InferenceBackendV2` 的关系：`InferenceBackendV2` 是"运行时契约"（加载 / 推理 / 卸载），`BackendDescriptor` 是"元数据契约"（能力 + 设置 schema）。一个后端实现通常同时实现这两个接口，但分离便于"先注册描述符、后按需加载实现"的懒加载。

### 3.3 BackendManagerV2

[`BackendManagerV2.kt`](../app/src/main/kotlin/com/aibox/domain/inference/BackendManagerV2.kt)

```kotlin
class BackendManagerV2 {
    fun registerBackend(backend: InferenceBackendV2, descriptor: BackendDescriptor)
    fun unregisterBackend(name: String)
    fun listBackends(): List<BackendDescriptor>
    fun getBackend(name: String): InferenceBackendV2?
    fun getDescriptor(name: String): BackendDescriptor?
    fun routeBackend(request: InferenceRequest, modelFormat: String): InferenceBackendV2?
    fun trackLoaded(backendName: String, model: LoadedModel)
    fun untrackLoaded(backendName: String, model: LoadedModel)
    fun listLoadedModels(): List<Pair<String, LoadedModel>>
    fun applyOptimizationsToAll(flags: OptimizationFlags)
}
```

路由策略：

1. 找出支持 `request.taskType` 的所有后端
2. 在候选中筛选能加载 `modelFormat` 的
3. 优先用格式匹配的；若无，回退到任何能跑此任务的（让该后端自己处理格式兼容）

线程安全：`register/unregister` 应在主线程集中完成，运行期不在推理途中增删后端。

---

## 4. 多模态支持

参考 [`InferenceRequest.kt`](../app/src/main/kotlin/com/aibox/domain/inference/InferenceRequest.kt) 与 [`InferenceResult.kt`](../app/src/main/kotlin/com/aibox/domain/inference/InferenceResult.kt)。

### 4.1 ModalityInput

```kotlin
sealed class ModalityInput {
    data class Text(val content: String) : ModalityInput()
    data class Image(val uri: String, val resolution: Int? = null) : ModalityInput()
    data class Audio(val uri: String, val sampleRate: Int = 16000) : ModalityInput()
    data class Video(val uri: String) : ModalityInput()
}

data class InferenceRequest(
    val taskId: String,
    val taskType: TaskType,
    val modelId: String,
    val inputs: List<ModalityInput>,   // 可混合多模态
    val config: InferenceConfigV2,
    val stream: Boolean = false,
    val sessionId: String? = null
)
```

### 4.2 InferenceResult

```kotlin
sealed class InferenceResult {
    data class Text(val content: String) : InferenceResult()
    data class Image(val uri: String) : InferenceResult()
    data class Audio(val uri: String) : InferenceResult()
    data class Multimodal(val parts: List<ModalityOutput>) : InferenceResult()
    data class Embedding(val vector: FloatArray) : InferenceResult()
    data class StreamChunk(val partial: ModalityOutput, val done: Boolean) : InferenceResult()
}
```

### 4.3 TaskType 与 Modality 正交

`TaskType` 10 项描述"做什么"，`Modality` 4 项描述"输入输出维度"：

| TaskType | 典型 inputs | 典型 result |
| --- | --- | --- |
| TEXT_GENERATION | [Text] | Text / StreamChunk |
| EMBEDDING | [Text] | Embedding |
| IMAGE_UNDERSTANDING | [Image, Text] | Text |
| MULTIMODAL_UNDERSTANDING | [Image, Text] | Text |
| AUDIO_TRANSCRIPTION | [Audio] | Text |

后端用 `BackendCapabilities.supportedTasks` 声明能跑哪些任务，`supportedInputModalities` / `supportedOutputModalities` 声明能处理哪些模态。`BackendManagerV2.routeBackend` 据此筛选。

---

## 5. 配置统一

### 5.1 InferenceConfigV2 + Map extras + OptimizationFlags

[`InferenceConfigV2.kt`](../app/src/main/kotlin/com/aibox/domain/inference/InferenceConfigV2.kt) 用一个 data class 覆盖所有后端 / 所有任务类型：

```kotlin
data class InferenceConfigV2(
    val taskType: TaskType = TaskType.TEXT_GENERATION,
    val threads: Int? = null,
    val batchSize: Int = 1,
    val maxTokens: Int = 512,
    val contextLength: Int = 2048,
    val kvQuant: QuantType = QuantType.F16,
    val weightQuant: QuantType? = null,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
    val optimizations: OptimizationFlags = OptimizationFlags.DEFAULT,
    val frameworkExtras: Map<String, Any> = emptyMap()
)
```

- **通用字段**：所有后端都识别（threads / batchSize / maxTokens / contextLength / 量化 / 采样 / 优化开关）
- **`frameworkExtras`**：框架专属配置，用 `Map<String, Any>` 透传，框架自己解析键值约定
  - llama.cpp：`"nGpuLayers" -> 0`、`"antiRollback" -> false`
  - LiteRT：`"preferredDelegate" -> "npu"`、`"maxDequeueBuffer" -> 4`
  - MLC：`"model_lib" -> "...so"`、`"warmup" -> true`

### 5.2 OptimizationFlags 三档预置

```kotlin
data class OptimizationFlags(
    // TTFT
    val preloadModel: Boolean = true,
    val warmupAfterLoad: Boolean = true,
    val preallocateKV: Boolean = true,
    val prebuildSampler: Boolean = true,
    val streamFirstTokenImmediately: Boolean = true,
    // TG
    val forceFlashAttention: Boolean = true,
    val aggressiveGraphOpt: Boolean = true,
    val continuousKVLayout: Boolean = true,
    val reuseSampler: Boolean = true,
    val zeroCopyStream: Boolean = true,
    val enableGraphCache: Boolean = true,
    // 能耗
    val thermalAwareThrottle: Boolean = true,
    val idleUnloadTimeout: Long = 10 * 60 * 1000,
    val lowPowerOnBattery: Boolean = false
) {
    companion object {
        val DEFAULT = OptimizationFlags()
        val PERFORMANCE = OptimizationFlags(thermalAwareThrottle = false, lowPowerOnBattery = false)
        val POWER_SAVE = OptimizationFlags(thermalAwareThrottle = true, lowPowerOnBattery = true, idleUnloadTimeout = 3 * 60 * 1000)
    }
}
```

设计原则：

- **开关式不调参**：每个字段是 Boolean（除 idleUnloadTimeout），不暴露"调到 0.7 还是 0.8"的微参
- **后端"尽力执行"**：不支持的字段静默忽略，不报错
- **三档预置**：用户在设置页一键切换 DEFAULT / PERFORMANCE / POWER_SAVE

### 5.3 用户可调设置 OptimizationSettings

[`OptimizationSettings.kt`](../app/src/main/kotlin/com/aibox/domain/optimization/OptimizationSettings.kt) 在 `OptimizationFlags` 基础上拆分 `idleUnloadTimeout`（总开关）和 `idleUnloadTimeoutMs`（时长），便于 UI 用单独的 SWITCH + SLIDER 渲染。

### 5.4 配置分层（G-settings-fix 去重原则）

**问题背景**：P3 引入 `OptimizationSettings`（开关式优化集合）后，与既有 `SettingsRepository`（V1 参数式设置 + 持久化）出现重叠。用户在「设置」页和「推理优化」页都能改同一件事（典型如「模型预加载」），产生认知负担与一致性风险。

**去重原则**：按"形状"归口，不按"语义"归口。

| 形状 | 归口 | 数据载体 | 持久化文件 | UI 页 |
| --- | --- | --- | --- | --- |
| **开关式**（Boolean / 枚举式选项） | `OptimizationSettings` | `OptimizationSettings` data class | `optimization` DataStore | `OptimizationSettingsScreen` |
| **参数式**（数值 / 字符串，每个模型可不同） | `SettingsRepository` | V1 `InferenceConfig` data class | `settings` DataStore | `SettingsScreen` |
| **推理时配置**（单次推理会话级） | `InferenceConfigV2` | `InferenceConfigV2` data class | （不持久化，调用方构造） | （不直接 UI 暴露） |
| **自动调优**（未来） | 自动调优模块（未建） | （未定义） | （未定义） | （未定义，暂由用户在 Settings 手选） |

**实际归口表**：

| 设置项 | 形状 | 归口 | 当前位置 | 说明 |
| --- | --- | --- | --- | --- |
| Flash Attention 开关 | 开关式 | `OptimizationSettings.forceFlashAttention` | `OptimizationSettingsScreen` → TG | 所有后端一致语义，不支持则静默忽略 |
| 模型预加载 | 开关式 | `OptimizationSettings.preloadModel` | `OptimizationSettingsScreen` → TTFT | 由 `TtftOptimizer.preloadOnAppStart()` 真正消费 |
| 加载后预热 | 开关式 | `OptimizationSettings.warmupAfterLoad` | `OptimizationSettingsScreen` → TTFT | |
| KV cache 预分配 | 开关式 | `OptimizationSettings.preallocateKV` | `OptimizationSettingsScreen` → TTFT | |
| 采样器预构造 / 复用 | 开关式 | `OptimizationSettings.prebuildSampler` / `reuseSampler` | `OptimizationSettingsScreen` → TTFT/TG | |
| 首 token 立即返回 | 开关式 | `OptimizationSettings.streamFirstTokenImmediately` | `OptimizationSettingsScreen` → TTFT | |
| 激进算子融合 | 开关式 | `OptimizationSettings.aggressiveGraphOpt` | `OptimizationSettingsScreen` → TG | |
| KV cache 连续布局 | 开关式 | `OptimizationSettings.continuousKVLayout` | `OptimizationSettingsScreen` → TG | |
| 流式 zero-copy | 开关式 | `OptimizationSettings.zeroCopyStream` | `OptimizationSettingsScreen` → TG | |
| 推理图缓存 | 开关式 | `OptimizationSettings.enableGraphCache` | `OptimizationSettingsScreen` → TG | |
| 散热感知降频 | 开关式 | `OptimizationSettings.thermalAwareThrottle` | `OptimizationSettingsScreen` → 能耗 | 软件层降档（通知后端自行降 batch/threads） |
| 空闲超时卸载 | 开关式 | `OptimizationSettings.idleUnloadTimeout` + `idleUnloadTimeoutMs` | `OptimizationSettingsScreen` → 能耗 | |
| 电池模式低功耗 | 开关式 | `OptimizationSettings.lowPowerOnBattery` | `OptimizationSettingsScreen` → 能耗 | |
| KV 量化类型（F16/Q8_0/Q4_0） | 参数式 | `SettingsRepository` → `InferenceConfig.kvQuant` | `SettingsScreen` → 性能 | 每个模型可不同；3 档枚举 |
| CPU 线程数 | 参数式 | `SettingsRepository` → `InferenceConfig.cpuThreads` | `SettingsScreen` → 性能 | 0 = 自动按大核数 |
| 物理 / 逻辑批处理 | 参数式 | `SettingsRepository` → `InferenceConfig.physicalBatch` / `batch` | `SettingsScreen` → 性能 | |
| 上下文长度 | 参数式 | `SettingsRepository` → `InferenceConfig.contextLength` | `SettingsScreen` → 性能 | |
| GPU/NPU 层数 | 参数式 | `SettingsRepository` → `InferenceConfig.nGpuLayers` | `SettingsScreen` → 后端 | |
| 采样参数（temperature/topK/topP/repeatPenalty） | 参数式 | `SettingsRepository` → `InferenceConfig.*` | `SettingsScreen` → 采样 | |
| 后端选择 | 参数式 | `SettingsRepository` → `InferenceConfig.backend` | `SettingsScreen` → 后端 | 暂由用户手选；自动调优模块未建 |
| 系统提示词 | 参数式（字符串） | `SettingsRepository` → `InferenceConfig.systemPrompt` | `SettingsScreen` → 对话 | |
| 思考模式 | 开关式（对话特性，非推理优化） | `SettingsRepository` → `THINKING_MODE` key | `SettingsScreen` → 对话 | 对话特性，与推理优化正交，保留在 Settings |
| 防回退（Anti-Rollback） | 开关式（V1 leftover） | `SettingsRepository` → `InferenceConfig.antiRollback` | `SettingsScreen` → 热管理 | **过渡期保留**：V1 `ThermalGovernor.tuneConfig` 直接读此字段；V1→V2 适配器落地后再迁到 `OptimizationSettings` |
| 外观 / 主题 / 语言 / 日志 / 下载源 / API key | 通用 App 设置 | `SettingsRepository` 独立 key | `SettingsScreen` 对应 section | 与推理无关，留 Settings |

**搜索归口**：用户在搜索框搜「模型预加载」「Flash Attention」等关键词，由 `SettingRegistry` 返回 metadata，`searchData["screen"] == "optimization"` 时跳转到 `Dest.Optimization`，否则跳 `Dest.Settings`。`SettingRegistry` 的 key 与各 Repository 的 DataStore key 严格对齐，便于点击结果后定位到具体 section。

**G-settings-fix 实际修改清单**：

- 从 `InferenceConfig` 删除 `autoLoadModel: Boolean` 字段（V1 dead code，无消费方）
- 从 `SettingsRepository` 删除 `AUTO_LOAD` preferences key + 对应 read/write 行
- 从 `SettingsScreen` 删除「启动行为」section（`autoLoadModel` SwitchRow）
- 从 `SettingRegistry` 删除 `auto_load` 条目（避免与 `opt.preloadModel` 重复）
- 在 `SettingsScreen` 新增「推理优化」section，Tile 跳转到 `OptimizationSettingsScreen`（`onOpenOptimization` 回调）
- 在 `AppNav` 新增 `Dest.Optimization` 路由 + `composable` 绑定 + `dispatchSearchClick` 按 `screen` 字段分发
- 在 `OptimizationSettings` / `OptimizationSettingsRepository` / `OptimizationSettingsScreen` 无新增字段（task 表列出的开关式优化已全部存在）

**过渡期已知遗留**：

1. **`antiRollback` 仍在 V1 `InferenceConfig`**：V1 `ThermalGovernor.tuneConfig` 第 26 行直接读 `config.antiRollback` 跳过降级。若现在迁移到 V2 `OptimizationSettings`，会破坏 V1 推理路径（V1 InferenceEngine + CpuBackend / VulkanBackend / NpuBackend / OpenClBackend 均依赖 ThermalGovernor）。**正确时机**：V1→V2 适配器落地后，V1 路径退场时一并迁移；届时在 V2 `OptimizationFlags` 加 `disableKernelFreqThrottle` 字段，`OptimizationSettings` 加同名字段，`ThermalGovernor`（V2 版本）读 flags 而非 config。
2. **`thinkingMode` 仍在 `SettingsRepository`**：虽是 Boolean 开关，但属"对话特性"（Qwen3 /think 标志），与推理优化正交。不强行迁移。
3. **`SettingsRepository` 仍持有「采样参数」**：温度 / topK / topP / repeatPenalty 是参数式，留 Settings 无异议；但 InferenceConfigV2 也有同名字段（V2 路径用），目前两套并行——V1 路径读 Settings，V2 路径调方自行构造。最终统一时机同 `antiRollback`。

---

## 6. 插件生命周期

参考 [`PluginManager.kt`](../app/src/main/kotlin/com/aibox/infra/plugin/PluginManager.kt)。

### 6.1 状态机

```
       (用户安装 APK)
              │
              ▼
        ┌─────────────┐
        │ Discovered │  (PluginInstallReceiver 收到 PACKAGE_ADDED)
        └──────┬──────┘
               │
               │ 用户在 UI 点击"启用"
               ▼
        ┌─────────────┐
        │  Installed  │  (verifySignature + CompatibilityChecker)
        └──────┬──────┘
               │
               │ bindService 成功 + setLogCallback + 注册到 BackendManagerV2
               ▼
        ┌─────────────┐
        │   Enabled   │  (PluginStore.setEnabled(true))
        └──────┬──────┘
               │
               │ 用户首次跑推理 / 触发 preloadModel
               ▼
        ┌─────────────┐
        │   Active    │  (BackendManagerV2.trackLoaded)
        └─────────────┘
```

### 6.2 状态转换 API

| 转换 | 方法 | 副作用 |
| --- | --- | --- |
| → Discovered | `PluginInstallReceiver` 收到 `PACKAGE_ADDED` | 不自动启用，等用户手动 |
| → Installed | `PluginManager.enablePlugin(pkg)` | 校验签名 + 兼容性 |
| → Enabled | `enablePlugin` 内 bindService + 注册 | `PluginStore.setEnabled(true)` |
| → Active | `BackendManagerV2.trackLoaded` | 持有 `LoadedModel` 句柄 |
| → Disabled | `PluginManager.disablePlugin(pkg)` | unbindService + 注销 + `setEnabled(false)` |
| → Removed | `PluginManager.uninstallPlugin(pkg)` | disable + clearAll + 触发系统卸载意图 |

### 6.3 包变化处理

`PluginInstallReceiver` 监听 4 种系统广播：

- `PACKAGE_ADDED`：标记 Discovered，不自动启用
- `PACKAGE_REPLACED`：禁用 + 重启用，让新版本生效
- `PACKAGE_REMOVED` / `FULLY_REMOVED`：禁用 + clearAll

---

## 7. 日志系统

参考 [`util/log/LogSystem.kt`](../app/src/main/kotlin/com/aibox/util/log/LogSystem.kt)。

### 7.1 三层 sink

```
   LogSystem.log(level, tag, msg)
              │
              ▼
   ┌─────────────────────────────────────────┐
   │ 分发到所有 sink（CopyOnWriteArrayList）│
   └──┬──────────┬──────────┬───────────────┘
      │          │          │
      ▼          ▼          ▼
  ┌──────┐  ┌─────────┐  ┌───────────┐
  │logcat│  │RingBuffer│  │FileLogSink│
  │ sink │  │  (5000)  │  │(按日轮转) │
  └──────┘  └────┬─────┘  └───────────┘
                │
                ▼
        每 200 条异步落盘
        emergencyFlush 同步刷盘
```

- **AndroidLogcatSink**：输出 logcat，便于 `adb logcat` 抓取
- **RingBufferLogSink**：5000 条环形缓冲，崩溃前 `emergencyFlush` 同步刷盘
- **FileLogSink**：按日期轮转，单文件 50MB 滚动，自动清理 7 天前日志

### 7.2 崩溃捕获

参考 [`infra/crash/CrashHandler.kt`](../app/src/main/kotlin/com/aibox/infra/crash/CrashHandler.kt) 与 [`infra/crash/NativeCrashHandler.kt`](../app/src/main/kotlin/com/aibox/infra/crash/NativeCrashHandler.kt)。

Java/Kotlin 异常层：

1. `UncaughtExceptionHandler` 捕获未处理异常
2. 同步 `LogSystem.emergencyFlushAll()` 刷盘
3. 写独立崩溃文件（含设备信息 + App 状态 + 最近 500 条日志上下文）

Native 信号崩溃层：

1. C++ `crash_handler.cpp` 安装 `SIGSEGV / SIGABRT / SIGFPE / SIGILL / SIGBUS` 信号处理
2. 用 async-signal-safe 函数写崩溃独立文件（含 backtrace）
3. 写入 `/data/data/com.aibox/cache/native_crashes/`（崩溃时无法访问外部存储）

### 7.3 会话日志

```kotlin
val sessionId = LogSystem.startSession("inference", "qwen2.5-0.5b")
LogSystem.info(tag, "msg", sessionId = sessionId)
LogSystem.endSession(sessionId, "=== Session End ===")
```

`FileLogSink` 把带 `sessionId` 的日志写入独立文件 `sessions/session-<id>.log`，便于"某次推理 / 某次 benchmark"独立导出。

### 7.4 基准测试独立日志

`benchmark` 类型 sessionId 写入 `benchmarks/bench-<id>.log`，与推理会话分离。

### 7.5 日志文件布局

```
/Android/data/com.aibox/files/aibox/logs/
├── aibox-YYYY-MM-DD.log          (按日轮转)
├── crashes/
│   └── crash-YYYY-MM-DD_HH-mm-ss.txt
├── sessions/
│   └── session-<id>.log
└── benchmarks/
    └── bench-<id>.log

/data/data/com.aibox/cache/native_crashes/
└── native_crash_<pid>_<ts>.txt
```

---

## 8. UI 统一

### 8.1 Design Token 系统

[`ui/theme/DesignTokens.kt`](../app/src/main/kotlin/com/aibox/ui/theme/DesignTokens.kt) 是宿主**唯一**的 UI 资产来源：

- 静态 token：`spacing4/8/12/16/24/32`、`radiusSmall/Medium/Large/XLarge`、`elevation0/1/2/4/8`、`durationShort/Medium/Long`、`easingStandard/Emphasized/...`
- 动态 token：`Colors.X` / `Type.X` 通过 `@Composable @ReadOnlyComposable` 桥到 `MaterialTheme.colorScheme.X` / `MaterialTheme.typography.X`，深 / 浅色自动适配

**强制约定**：组件层只能通过 `Colors.X` / `Type.X` / `DesignTokens.X` 取色字间距，**不允许**直接写 `Color(0xFF...)` / `fontSize = 14.sp` / `12.dp` 字面量。

### 8.2 统一组件库

[`ui/components/unified/`](../app/src/main/kotlin/com/aibox/ui/components/unified/) 6 个组件：

| 组件 | 用途 | 关键特性 |
| --- | --- | --- |
| `UnifiedSwitch` | 布尔开关 | M3 ListItem + trailing Switch |
| `UnifiedDropdown` | 下拉单选 | 选项前 Check icon 指示当前选中 |
| `UnifiedSlider` | 数值滑块 | 自动按 step 算离散点 + 显示小数位 |
| `UnifiedInput` | 文本输入 | keyboardHint 映射 KeyboardType |
| `UnifiedCard` | 卡片容器 | 用 `UnifiedCardTone` 枚举代 Color 参数 |
| `UnifiedSection` | 分组标题 | 顶部 outlineVariant 细分隔线 |

### 8.3 Schema 渲染引擎

[`SchemaRenderer.kt`](../app/src/main/kotlin/com/aibox/ui/components/unified/SchemaRenderer.kt) 让"插件设置页"等动态 UI 无需硬编码：

```kotlin
@Composable
fun SettingsListRenderer(
    fields: List<SettingField>,
    currentValues: Map<String, Any>,
    onValueChange: (key: String, value: Any) -> Unit
)
```

- `buildSegments(fields)` 按 `FieldType.GROUP` 把扁平列表切分成多段
- 每段渲染为 `UnifiedSection`，每段内字段经 `field.visibleWhen(currentValues)` 过滤
- 派发到 4 个 UnifiedXxx 组件

**插件不需要写任何 UI 代码**——只需通过 `getSettingsSchema` 返回 `List<SettingField>`，宿主自动渲染。

---

## 9. 优化策略

### 9.1 三个维度

| 维度 | 目标 | 优化器 | 关键字段 |
| --- | --- | --- | --- |
| **TTFT** | 首 token 延迟低 | [`TtftOptimizer`](../app/src/main/kotlin/com/aibox/domain/optimization/TtftOptimizer.kt) | `preloadModel` / `warmupAfterLoad` / `preallocateKV` / `prebuildSampler` / `streamFirstTokenImmediately` |
| **TG** | 生成速度快 | [`TgOptimizer`](../app/src/main/kotlin/com/aibox/domain/optimization/TgOptimizer.kt) | `forceFlashAttention` / `aggressiveGraphOpt` / `continuousKVLayout` / `reuseSampler` / `zeroCopyStream` / `enableGraphCache` |
| **能耗** | 散热好 / 省电 | [`EnergyOptimizer`](../app/src/main/kotlin/com/aibox/domain/optimization/EnergyOptimizer.kt) | `thermalAwareThrottle` / `idleUnloadTimeout` / `lowPowerOnBattery` |

### 9.2 开关式不调参

所有 14 个优化字段是 Boolean（除 `idleUnloadTimeout` 是 Long），**不暴露微参**。原因：

- 微参对最终性能影响 5-10%，但对调试与跨设备复现是灾难
- 用户拿到的"性能档"应该是"打开哪些开关"，不是"把 threads 调到 4 还是 6"
- 自动调优（threads / KV 类型 / batch 选择）由 [`ThermalGovernor`](../app/src/main/kotlin/com/aibox/domain/inference/ThermalGovernor.kt) 等单独机制负责，**不混入 OptimizationFlags**

### 9.3 三档预置

| 预置 | 适用场景 | 关键差异 |
| --- | --- | --- |
| `DEFAULT` | 日常使用 | 所有 TTFT / TG 优化开 + 热感知降频开 + idle 10 分钟 |
| `PERFORMANCE` | 短时高强度 | 禁用热感知降频 + 电池模式不降 |
| `POWER_SAVE` | 电池模式 | 热感知 + 电池模式 + idle 3 分钟 |

### 9.4 OptimizationManager 统一入口

[`OptimizationManager.kt`](../app/src/main/kotlin/com/aibox/domain/optimization/OptimizationManager.kt) 串起三个子 optimizer：

- `onAppStart()`：拉初始设置 + 启动 settings/thermal 订阅 + 启动 idle watchdog + 异步 preloadOnAppStart
- `onModelLoaded(backend, model)`：调 `recordActivity` + `warmupAfterLoad`
- `onThermalStateChanged(state)`：HOT/CRITICAL 时通知后端降档（通过 flags，不直接改 threads）
- `recordActivity()`：刷新 lastActivityAt（ChatViewModel 等在每次推理前调）

---

## 10. 零异常约定

参考 [`util/outcome/Outcome.kt`](../app/src/main/kotlin/com/aibox/util/outcome/Outcome.kt)。

### 10.1 Outcome 类型

```kotlin
sealed class Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>()
    data class Failure(val reason: String, val cause: Throwable? = null) : Outcome<Nothing>()

    inline fun <R> map(transform: (T) -> R): Outcome<R>
    inline fun <R> flatMap(transform: (T) -> Outcome<R>): Outcome<R>
    inline fun onSuccess(block: (T) -> Unit): Outcome<T>
    inline fun onFailure(block: (String, Throwable?) -> Unit): Outcome<T>
    fun getOrNull(): T?
    fun reasonOrNull(): String?

    companion object {
        fun <T> success(value: T): Outcome<T>
        fun failure(reason: String, cause: Throwable? = null): Outcome<Nothing>
        inline fun <T> catching(block: () -> T): Outcome<T>
    }
}
```

### 10.2 与 Kotlin Result 的区别

| | Kotlin Result | Outcome |
| --- | --- | --- |
| 失败建模 | 携带 Throwable | 携带 reason String + 可选 cause |
| 跨进程 | 不支持（Throwable 不可 parcelable） | 支持（reason 是 String） |
| 跨模块 | Throwable 类型敏感 | reason 是 String，弱类型 |
| 兼容性 | Kotlin 标准库 | 自定义，便于演进 |

### 10.3 使用范围

- [`InferenceBackendV2.loadModel`](../app/src/main/kotlin/com/aibox/domain/inference/InferenceBackendV2.kt) / `infer` → `Outcome`
- [`InferenceBackendV2.streamInfer`](../app/src/main/kotlin/com/aibox/domain/inference/InferenceBackendV2.kt) → `Flow<StreamChunk>`，错误用 `done=true` chunk 体现
- [`PluginManager.enablePlugin`](../app/src/main/kotlin/com/aibox/infra/plugin/PluginManager.kt) / `disablePlugin` → `Outcome`
- [`BasicBenchmark.run`](../app/src/main/kotlin/com/aibox/infra/benchmark/BasicBenchmark.kt) → 失败返回 `BenchmarkResult.failed`，不抛
- [`PluginBackendAdapter`](../app/src/main/kotlin/com/aibox/infra/plugin/PluginBackendAdapter.kt) → 所有 `RemoteException` 转 `Outcome.Failure`
- [`ParcelableConverters`](../app/src/main/kotlin/com/aibox/infra/plugin/ParcelableConverters.kt) → 所有 JSON 反序列化失败返回 fallback 默认对象

### 10.4 不抛异常的位置

- AIDL 调用：所有 `RemoteException` try-catch
- JSON 反序列化：所有 `Throwable` try-catch，返回 fallback
- BroadcastReceiver.onReceive：try-catch 全异常，避免广播队列阻塞
- 系统服务读取（PowerManager / BatteryManager）：try-catch 兜底
- Native 信号崩溃：用 async-signal-safe 函数，不依赖 JVM

---

## 11. 模块依赖图

```
   AIBoxApp
       │
       ▼
   AppContainer (手写 DI)
       │
       ├──► thermalMonitor / cpuInfo / nativeBridge
       │
       ├──► inferenceEngine (V1)
       │       └──► Backend (V1: Cpu/Vulkan/NPU/OpenCL) + ThermalGovernor + MemoryOptimizer
       │
       ├──► modelRepository / settingsRepository / chatRepository / downloadManager
       │
       ├──► backendManager (V2)  ◄── PluginManager.registerBackend
       │       └──► (空容器，等注册)
       │
       ├──► pluginManager
       │       ├──► PluginStore (SharedPreferences)
       │       ├──► PluginCompatibilityChecker
       │       ├──► PluginInstallReceiver (BroadcastReceiver)
       │       └──► enablePlugin → PluginBackendAdapter → backendManager.registerBackend
       │
       ├──► benchmarkSystem
       │       ├──► EnvironmentMonitor (用 thermalMonitor)
       │       ├──► EnergyProfiler
       │       ├──► BasicBenchmark / AdvancedBenchmark / BackendComparison
       │       └──► BenchmarkStorage (用 Room)
       │
       └──► optimizationManager
               ├──► OptimizationSettingsRepository (DataStore "optimization")
               ├──► TtftOptimizer / TgOptimizer / EnergyOptimizer
               └──► 用 thermalMonitor + backendManager + modelRepository + thermalGovernor
```

---

## 12. V1 与 V2 共存策略

| | V1 | V2 |
| --- | --- | --- |
| 接口 | `Backend` (in InferenceEngine.kt) | `InferenceBackendV2` |
| 调度器 | `InferenceEngine` (含 fallback + 配置调优) | `BackendManagerV2` (只做路由) |
| 配置 | `InferenceConfig` (含 17 字段，LLM 专用) | `InferenceConfigV2` (含 frameworkExtras Map，通用) |
| 异常 | 抛异常 + `Result<Unit>` | `Outcome<T>` |
| 多模态 | 不支持 | `ModalityInput` / `InferenceResult` 多模态 sealed class |
| 多任务 | 仅 LLM | `TaskType` 10 项 |
| 插件 | 无 | AIDL 11 个文件 + `PluginBackendAdapter` |

共存原则：

- V2 类名带 `V2` 后缀，V1 名不动
- V1 `Backend` 不继承 V2 `InferenceBackendV2`
- V1 调用方（`ChatViewModel` / `InferenceEngine`）零影响
- 后续可写"V1 Backend → V2 InferenceBackendV2 适配器"逐步迁移
- 新功能（多模态 / 多框架）一律用 V2

---

## 13. 参考实现索引

| 章节 | 参考代码 |
| --- | --- |
| AIBoxApp 初始化 | [`AIBoxApp.kt`](../app/src/main/kotlin/com/aibox/AIBoxApp.kt) |
| DI 容器 | [`AppContainer.kt`](../app/src/main/kotlin/com/aibox/di/AppContainer.kt) |
| V2 推理抽象 | [`domain/inference/`](../app/src/main/kotlin/com/aibox/domain/inference/) |
| 插件生命周期 | [`infra/plugin/`](../app/src/main/kotlin/com/aibox/infra/plugin/) |
| 基准测试 | [`infra/benchmark/`](../app/src/main/kotlin/com/aibox/infra/benchmark/) |
| 优化系统 | [`domain/optimization/`](../app/src/main/kotlin/com/aibox/domain/optimization/) |
| 日志系统 | [`util/log/`](../app/src/main/kotlin/com/aibox/util/log/) + [`infra/crash/`](../app/src/main/kotlin/com/aibox/infra/crash/) |
| UI 统一 | [`ui/theme/DesignTokens.kt`](../app/src/main/kotlin/com/aibox/ui/theme/DesignTokens.kt) + [`ui/components/unified/`](../app/src/main/kotlin/com/aibox/ui/components/unified/) |
| Outcome 类型 | [`util/outcome/Outcome.kt`](../app/src/main/kotlin/com/aibox/util/outcome/Outcome.kt) |
| AndroidManifest | [`app/src/main/AndroidManifest.xml`](../app/src/main/AndroidManifest.xml) |
| 示例插件 | [`easy-chat-module/`](../easy-chat-module/) + [`litert-plugin/`](../litert-plugin/) |

---

## 14. 延伸阅读

- [`PLUGIN_DEVELOPMENT.md`](./PLUGIN_DEVELOPMENT.md)：插件开发完整指南
- [`BENCHMARK_METHODOLOGY.md`](./BENCHMARK_METHODOLOGY.md)：基准测试方法论
- [`PERFORMANCE_TUNING.md`](./PERFORMANCE_TUNING.md)：性能调优指南
- [`RELEASE_CHECKLIST.md`](./RELEASE_CHECKLIST.md)：发布检查清单
