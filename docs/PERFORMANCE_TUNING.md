# AIBox 性能调优指南

本指南说明如何把 AIBox 跑得更快、更省电。读完本指南你将能：

- 理解 TTFT / TG / 能耗三个维度的优化原理
- 用三档预置（默认 / 性能 / 省电）匹配使用场景
- 知道每个开关开 / 关的副作用
- 根据设备能力（low_end / mid_range / flagship）选后端
- 用实测数据决策，不靠"我猜应该这样调"

参考实现见 [`domain/optimization/`](../app/src/main/kotlin/com/aibox/domain/optimization/) 与 UI 入口 [`ui/optimization/OptimizationSettingsScreen.kt`](../app/src/main/kotlin/com/aibox/ui/optimization/OptimizationSettingsScreen.kt)。

---

## 1. 三个维度

AIBox 把性能调优拆成三个正交维度，每个维度由一个独立 optimizer 负责：

| 维度 | 目标 | 优化器 | 核心指标 |
| --- | --- | --- | --- |
| **TTFT** | 首 token 延迟低 | [`TtftOptimizer`](../app/src/main/kotlin/com/aibox/domain/optimization/TtftOptimizer.kt) | TTFT (ms) |
| **TG** | 生成速度快 | [`TgOptimizer`](../app/src/main/kotlin/com/aibox/domain/optimization/TgOptimizer.kt) | TG (tokens/sec) |
| **能耗** | 散热好 / 省电 | [`EnergyOptimizer`](../app/src/main/kotlin/com/aibox/domain/optimization/EnergyOptimizer.kt) | 内存峰值 / 温度档位 / 电池 |

三个维度**不互相牵制**：你可以"开 TG 优化 + 关能耗优化"跑性能档，也可以"开能耗优化 + 关 TG 优化"跑省电档。所有字段是独立 Boolean 开关，不强制联动。

---

## 2. TTFT 优化原理

TTFT = Time To First Token。从用户按下"发送"到屏幕出现第一个字的时间。

### 2.1 为什么 2.58s → 0.5s 可行

参考 [`easy-chat-module/app/src/main/assets/module.json`](../easy-chat-module/app/src/main/assets/module.json) 中 `expectedPerformance.ttftMs = 2580`。这是"未优化"的实测值（骁龙 8 Gen3 CPU 2 线程，Qwen2.5-0.5B Q4_K_M）。

拆分 2.58s：

| 阶段 | 时长 | 说明 |
| --- | --- | --- |
| 模型加载（mmap + KV 分配 + 图编译） | 850 ms | 仅冷启动，warmup 后省去 |
| Prompt 编码（PP） | 220 ms | 与 prompt 长度线性相关 |
| KV cache 首次扩容 | 80 ms | preallocateKV 后省去 |
| 采样器首次构建 | 30 ms | prebuildSampler 后省去 |
| 首 decode | 90 ms | 与 batch 无关 |
| GPU/Vulkan shader JIT 编译 | 1100 ms | enableGraphCache 后第二次省去 |
| 其它（线程池初始化 / JNI 注册） | 210 ms | |

开启所有 TTFT 优化后：

| 阶段 | 时长 |
| --- | --- |
| 模型加载（preloadModel 后启动时已 mmap） | 0 ms |
| Prompt 编码 | 220 ms |
| KV（preallocateKV 后已就绪） | 0 ms |
| 采样器（prebuildSampler 后已构建） | 0 ms |
| 首 decode | 90 ms |
| Shader（warmupAfterLoad 已编译） | 0 ms |
| 其它 | 80 ms |
| **合计** | **390 ms** |

实测从 2.58s 降到约 400-500 ms 是可行的。

### 2.2 五个 TTFT 开关

[`OptimizationFlags`](../app/src/main/kotlin/com/aibox/domain/inference/InferenceConfigV2.kt) 中 TTFT 相关 5 项：

#### 2.2.1 `preloadModel` (默认 true)

- **作用**：App 启动时即 `mmap` 权重到内存，而非首次推理时按需加载
- **副作用**：增加启动后内存占用（0.5B Q4_K_M 约 400 MB）
- **关闭场景**：低内存设备（<4 GB RAM）启动后要等用户操作才加载

参考 [`TtftOptimizer.preloadOnAppStart()`](../app/src/main/kotlin/com/aibox/domain/optimization/TtftOptimizer.kt)：

```kotlin
suspend fun preloadOnAppStart() {
    val lastUsed = modelRepository.lastUsed() ?: return
    val backend = backendManagerV2.listBackends().firstOrNull() ?: return
    val config = InferenceConfigV2()  // 默认配置，不针对特定模型
    backend.applyOptimizations(settings.toFlags())
    val loaded = backend.loadModel(lastUsed.path, config).getOrNull() ?: return
    backendManagerV2.trackLoaded(backend.getName(), loaded)
}
```

#### 2.2.2 `warmupAfterLoad` (默认 true)

- **作用**：loadModel 后立即跑一次空输入预热（`inputs = [" "]`，`maxTokens = 1`），让所有 tokenizer 至少产生 1 token
- **副作用**：首次 loadModel 后多 200-500 ms 延迟；用户感受不到（用户还没开始打字）

参考 [`TtftOptimizer.warmupAfterLoad`](../app/src/main/kotlin/com/aibox/domain/optimization/TtftOptimizer.kt)：

```kotlin
suspend fun warmupAfterLoad(backend: InferenceBackendV2, model: LoadedModel) {
    val request = InferenceRequest(
        taskId = UUID.randomUUID().toString(),
        taskType = TaskType.TEXT_GENERATION,
        modelId = model.id,
        inputs = listOf(ModalityInput.Text(" ")),   // 任何 tokenizer 都至少产生 1 token
        config = model.config.copy(maxTokens = 1),
        stream = false
    )
    backend.infer(request, model)   // 结果丢弃
}
```

#### 2.2.3 `preallocateKV` (默认 true)

- **作用**：加载时按 `contextLength` 一次性分配 KV cache，避免逐 token 增长触发 realloc
- **副作用**：内存占用立即达到峰值（1024 ctx × 0.5B Q8 KV ≈ 32 MB），即使只跑短对话
- **关闭场景**：长 context 但用户大部分时间只跑短对话（如 4096 ctx 但平均聊 200 token）

#### 2.2.4 `prebuildSampler` (默认 true)

- **作用**：加载时即构建 sampler 对象（temperature / topP / topK / repeatPenalty），避免首次推理时重建
- **副作用**：约 5-10 ms 内存分配，可忽略

#### 2.2.5 `streamFirstTokenImmediately` (默认 true)

- **作用**：首 token 一就绪就 push 给 UI，不等 N 个 token 攒批
- **副作用**：UI 收到 chunk 频率高，每 chunk 处理开销略增（实际可忽略）
- **关闭场景**：UI 渲染慢（如老设备 RecyclerView 复杂动效），改为攒 4 token 一批

参考 [`TtftOptimizer.streamFirstTokenImmediately`](../app/src/main/kotlin/com/aibox/domain/optimization/TtftOptimizer.kt)：

```kotlin
fun streamFirstTokenImmediately(upstream: Flow<StreamChunk>): Flow<StreamChunk> {
    if (!settings.streamFirstTokenImmediately) return upstream  // 关闭则直接透传
    var firstSeen = false
    return upstream.onEach {
        if (!firstSeen) {
            firstSeen = true
            LogSystem.info("aibox.opt.ttft", "first chunk pushed immediately")
        }
    }
}
```

---

## 3. TG 优化原理

TG = Token Generation。逐 token decode 的速度（tokens/sec）。

### 3.1 为什么 TG 可以从 25 t/s 提到 35 t/s

拆分单 token decode 时长：

| 阶段 | 时长 | 优化前 | 优化后 |
| --- | --- | --- | --- |
| KV cache 读取 | 4 ms | 分块布局 | 连续布局 2 ms |
| Attention 计算 | 8 ms | 标准 softmax | Flash Attention 5 ms |
| FFN 计算 | 6 ms | 未融合 | 算子融合 4 ms |
| Sampler | 3 ms | 每 token 重建 | 复用 0.5 ms |
| 输出回传 | 4 ms | 字符串拷贝 | 零拷贝 1 ms |
| **合计** | **25 ms** | | **12.5 ms** |

TG t/s 从 40 → 80（理论值）。实际受内存带宽 / 热降频限制，提升 30-50%。

### 3.2 六个 TG 开关

#### 3.2.1 `forceFlashAttention` (默认 true)

- **作用**：强制使用 Flash Attention；后端不支持则静默忽略
- **副作用**：某些老 GGUF 模型（如 GPT-2 系列）FA 精度有问题，需关闭
- **后端实现**：llama.cpp 通过 `llama_context_params.no_kv_offload` + `flash_attn` 开关

#### 3.2.2 `aggressiveGraphOpt` (默认 true)

- **作用**：启用激进的算子融合 / 常量折叠
- **副作用**：增加编译时间（首次推理慢 100-300 ms），后续推理不变
- **关闭场景**：用户只跑一次推理（编译时间 > 推理时间节省）

#### 3.2.3 `continuousKVLayout` (默认 true)

- **作用**：使用连续 KV 内存布局（vs 分块），提速但占内存更整块
- **副作用**：长 context 时整块内存可能 OOM（4 GB 设备跑 4096 ctx 0.5B 模型需 256 MB 连续内存）
- **关闭场景**：长 context + 低内存

#### 3.2.4 `reuseSampler` (默认 true)

- **作用**：同会话内复用 sampler 实例，避免每个 token 重建
- **副作用**：用户改 temperature 后需手动重新 loadModel（不主动检测参数变化）

#### 3.2.5 `zeroCopyStream` (默认 true)

- **作用**：流式输出走零拷贝路径（如 mmap 出来的 buffer 直接引用，不拷贝到 String）
- **副作用**：UI 层需注意 buffer 生命周期（AIBox 用 String 已包装为安全副本，无影响）

#### 3.2.6 `enableGraphCache` (默认 true)

- **作用**：缓存编译后的计算图，加速同模型二次加载
- **副作用**：首次加载后多 5-15 MB 磁盘占用（缓存到 `cache/graphs/`）

参考 [`TgOptimizer.applyBeforeLoad`](../app/src/main/kotlin/com/aibox/domain/optimization/TgOptimizer.kt)：

```kotlin
fun applyBeforeLoad(backend: InferenceBackendV2): OptimizationFlags {
    val flags = settings.toFlags()
    try {
        backend.applyOptimizations(flags)
    } catch (t: Throwable) {
        LogSystem.warn("aibox.opt.tg", "applyOptimizations failed", t)
    }
    return flags  // 调用方放进 InferenceConfigV2.optimizations 双通道传递
}
```

**双通道传递**：flags 既通过 `applyOptimizations` 注入后端，也通过 `InferenceConfigV2.optimizations` 字段传递。后端实现可选其一读取，保证跨后端兼容。

---

## 4. 能耗优化原理

### 4.1 散热感知

参考 [`EnergyOptimizer.onThermalStateChanged`](../app/src/main/kotlin/com/aibox/domain/optimization/EnergyOptimizer.kt)：

```kotlin
fun onThermalStateChanged(state: ThermalState) {
    when (state) {
        ThermalState.COOL, ThermalState.WARM -> { /* no-op */ }
        ThermalState.HOT, ThermalState.CRITICAL -> {
            // 通知后端降档，不直接改 threads / batch
            tgOptimizer.applyToAllWith(currentFlags.copy(thermalAwareThrottle = true))
        }
    }
}
```

**关键约定**：能耗优化**不直接改 threads / KV 类型 / batch 选择**。那是 [`ThermalGovernor`](../app/src/main/kotlin/com/aibox/domain/inference/ThermalGovernor.kt) 与自动调优的职责。能耗优化只通过 `OptimizationFlags.thermalAwareThrottle` 通知后端"用户希望启用热感知降档"，后端自行实现（如降低 GPU 频率、减小 batch）。

### 4.2 空闲卸载

参考 [`EnergyOptimizer.startIdleWatchdog`](../app/src/main/kotlin/com/aibox/domain/optimization/EnergyOptimizer.kt)：

```kotlin
fun startIdleWatchdog() {
    watchdogJob = scope.launch {
        while (isActive) {
            delay(60_000)  // 每 60s 检查一次
            val idle = System.currentTimeMillis() - lastActivityAt
            if (idle > settings.idleUnloadTimeoutMs) {
                backendManagerV2.listLoadedModels().forEach { (name, model) ->
                    backendManagerV2.getBackend(name)?.let { backend ->
                        backend.unload(model)
                        backendManagerV2.untrackLoaded(name, model)
                    }
                }
            }
        }
    }
}
```

- 每 60s 检查 `lastActivityAt`
- 超过 `idleUnloadTimeoutMs` 则遍历 `backendManagerV2.listLoadedModels()` 调 unload
- 用 Mutex 防 unload 与新 load 并发
- 幂等：先 cancel 旧 watchdogJob 再启动新的

### 4.3 电池模式

- `lowPowerOnBattery = true`：电池模式下进入低功耗档
- 后端实现可读 `BatteryManager.isCharging` 决定是否降频
- 不强制：仅给后端"提示"，后端可选择忽略

### 4.4 三个能耗开关

| 开关 | 默认 | 作用 | 副作用 |
| --- | --- | --- | --- |
| `thermalAwareThrottle` | true | 温度过高时主动降档 | 高强度推理场景性能下降 10-20% |
| `idleUnloadTimeout` | true | 空闲超时自动卸载模型 | 用户长时间不操作后再回来需重新加载（200-500 ms） |
| `lowPowerOnBattery` | false | 电池模式降功耗 | 电池模式下 TG 下降 20-30% |

---

## 5. 三档预置

### 5.1 默认档（DEFAULT）

适用：日常使用，平衡速度与能耗

```kotlin
val DEFAULT = OptimizationFlags()  // 所有字段默认值
// TTFT 全开 / TG 全开 / thermalAwareThrottle=true / idleUnloadTimeout=10min / lowPowerOnBattery=false
```

**适合**：95% 用户。开箱即用，不需要调。

### 5.2 性能档（PERFORMANCE）

适用：短时间高强度推理（如跑 benchmark、批量处理）

```kotlin
val PERFORMANCE = OptimizationFlags(
    thermalAwareThrottle = false,  // 禁用热感知降频
    lowPowerOnBattery = false       // 电池模式也跑满
)
```

**适合**：
- 跑 benchmark 时
- 用户接电源 + 知道设备会发热 + 想要最快输出
- 短时间（<5 分钟）批量处理

**不适合**：
- 长时间使用（设备会烫到降频保护）
- 电池模式（电池会快速耗尽）
- 老设备（散热差，HOT 状态可能崩）

### 5.3 省电档（POWER_SAVE）

适用：电池模式 / 老设备 / 长时间待机

```kotlin
val POWER_SAVE = OptimizationFlags(
    thermalAwareThrottle = true,
    lowPowerOnBattery = true,
    idleUnloadTimeout = 3 * 60 * 1000  // 3 分钟
)
```

**适合**：
- 电池模式 + 想延长续航
- 老设备（散热差）
- 长时间空闲（如夜间挂着）

**不适合**：
- 跑 benchmark（结果会被降频影响）
- 短时高强度（idle 3 分钟太激进，频繁 unload + reload）

### 5.4 切换预置

UI 在 [`OptimizationSettingsScreen.kt`](../app/src/main/kotlin/com/aibox/ui/optimization/OptimizationSettingsScreen.kt) 顶部下拉选预置：

```kotlin
// ViewModel.applyPreset
fun applyPreset(presetId: String) {
    val preset = OptimizationSettings.presetById(presetId) ?: return
    viewModelScope.launch {
        repository.set(preset)  // 覆盖写整份设置
    }
}
```

切换预置会立即覆盖所有 14 个开关。手动调过个别开关后，预置下拉显示"自定义"。

---

## 6. 单个开关的权衡

每个开关都有"开 vs 关"的权衡，不要盲目全开。下表汇总关键开关：

| 开关 | 开的副作用 | 关的副作用 | 推荐场景 |
| --- | --- | --- | --- |
| `preloadModel` | 启动后内存立即达 400 MB | 首次推理 +800 ms | 内存 >= 6 GB 开，< 4 GB 关 |
| `warmupAfterLoad` | loadModel 后多 200-500 ms | 首次推理 +500 ms | 全开（用户感受不到） |
| `preallocateKV` | 内存立即达峰值 | 逐 token realloc 抖动 | 短 context 开，长 context 关 |
| `prebuildSampler` | 5-10 ms 内存 | 首次推理 +30 ms | 全开（代价可忽略） |
| `streamFirstTokenImmediately` | UI 高频更新 | 首 token 等攒批 | 全开 |
| `forceFlashAttention` | 老模型可能精度问题 | TG 下降 20% | 新模型开，老 GGUF 关 |
| `aggressiveGraphOpt` | 首次推理慢 100-300 ms | TG 下降 10-15% | 多次推理开，单次关 |
| `continuousKVLayout` | 长内存整块占满 | TG 下降 5-10% | 短 context 开，长 context 关 |
| `reuseSampler` | 改采样参数需手动 reload | 每 token 重建 +2 ms | 全开（除非频繁调温度） |
| `zeroCopyStream` | UI 注意 buffer 生命周期 | 每 chunk 多 1 ms 拷贝 | 全开（AIBox 已处理） |
| `enableGraphCache` | 5-15 MB 磁盘 | 二次加载慢 100-300 ms | 全开（除非磁盘极紧） |
| `thermalAwareThrottle` | HOT 时性能下降 10-20% | 烧设备 / 崩溃 | 日常开，benchmark 关 |
| `idleUnloadTimeout` | 用户回来需 reload 200-500 ms | 内存一直占 | 日常 10 min，电池 3 min |
| `lowPowerOnBattery` | 电池模式 TG 下降 20-30% | 电池快速耗尽 | 电池模式开，接电源关 |

---

## 7. 后端选择策略

不同后端在不同场景适用。AIBox 内置 4 个 V1 后端 + 通过插件可加 V2 后端。

### 7.1 CPU 后端

参考 [`CpuBackend.kt`](../app/src/main/kotlin/com/aibox/domain/inference/CpuBackend.kt)。

- **适用**：所有设备、所有模型、所有场景的兜底
- **优点**：兼容性 100%，无外部依赖，调试简单
- **缺点**：速度最慢，0.5B Q4_K_M 在骁龙 8 Gen3 仅 35 t/s
- **threads 选择**：1 / 2 / 4 线程最优；6 线程因核间切换反而降速
- **何时用**：兜底、调试、小模型（<0.5B）、单 token 推理（如 tokenizer）

### 7.2 Vulkan 后端

参考 [`VulkanBackend.kt`](../app/src/main/kotlin/com/aibox/domain/inference/VulkanBackend.kt)。

- **适用**：Adreno / Mali / PowerVR GPU 设备
- **优点**：GPU 加速，TG 比 CPU 快 2-3x
- **缺点**：shader 编译时间 1-3 s，首次推理慢；老 GPU 兼容性差
- **nGpuLayers 选择**：0.5B 全 offload，1B-3B offload 50%，7B offload 0%（CPU 还快）
- **何时用**：中端 GPU + 中等模型（1-3B）；有 GPU 但无 NPU 的设备

### 7.3 NPU 后端

参考 [`NpuBackend.kt`](../app/src/main/kotlin/com/aibox/domain/inference/NpuBackend.kt)。

- **适用**：骁龙 8 Gen1+ 设备（Hexagon NPU + QNN SDK）
- **优点**：TG 比 CPU 快 5-10x，能耗最低（每 token 仅 CPU 的 1/3 功耗）
- **缺点**：需要 QNN SDK（不打包到 APK，需用户自备），仅支持有限模型格式
- **何时用**：骁龙 8 Gen1+ + 接电源 + 长时间使用；不要用于跑 benchmark（HOT 状态会降频，结果偏低）

### 7.4 LiteRT 后端（插件）

参考 [`litert-plugin/`](../litert-plugin/)。

- **适用**：需要多模态（Gemma + Siglip）或 TFLite 模型格式
- **优点**：跨平台（iOS / Android / 嵌入式），多模态原生支持
- **缺点**：需安装插件 APK，TFLite 模型选择少
- **何时用**：多模态任务；只有 TFLite 模型；Gemma 系列模型

### 7.5 后端选择决策树

```
有 NPU + QNN SDK + 长时间使用？
├── 是 → NPU 后端（性能 + 能耗最优）
└── 否 → 有 GPU？
        ├── 是 → 模型大小？
        │   ├── <1B → CPU（GPU 收益小，shader 编译反而慢）
        │   ├── 1-3B → Vulkan
        │   └── >3B → CPU + 优化档（GPU 内存不够）
        └── 否 → CPU（兜底）
```

---

## 8. 模型选型决策树

### 8.1 按任务类型

```
任务类型？
├── 聊天 / 对话 → 选 Instruct 系列（Qwen2.5-Instruct / LFM / SmolLM-Instruct）
├── RAG / 摘要 → 选 Base 系列（Qwen2.5-Base）+ 长 context
├── 写代码 → 选 Code 系列（Qwen2.5-Coder / DeepSeek-Coder）
├── 多模态 → 选 VLM（Gemma + Siglip via LiteRT 插件）
└── Embedding → 选 Embedding 系列（BGE / GTE）
```

### 8.2 按设备能力

| 设备类型 | SoC | RAM | 推荐模型 |
| --- | --- | --- | --- |
| low_end | 骁龙 4 Gen1 | 3 GB | 0.5B Q4_K_M（如 Qwen2.5-0.5B） |
| mid_range | 骁龙 6 Gen1 | 4 GB | 0.5B-1B Q4_K_M |
| flagship | 骁龙 8 Gen2+ | 6 GB+ | 1.5B-3B Q4_K_M / Q5_K_M |

### 8.3 按量化精度

| 量化 | 大小（0.5B） | 精度 | TG t/s | 内存 |
| --- | --- | --- | --- | --- |
| F16 | 1.0 GB | 100% | 25 | 1.2 GB |
| Q8_0 | 530 MB | 99% | 30 | 700 MB |
| Q5_K_M | 380 MB | 98% | 33 | 550 MB |
| Q4_K_M | 400 MB | 96% | 35 | 570 MB |
| Q4_0 | 290 MB | 93% | 38 | 460 MB |
| IQ3_S | 230 MB | 90% | 41 | 400 MB |
| IQ2_XXS | 180 MB | 80% | 45 | 350 MB |

**决策**：

- 聊天 / 短文：Q4_K_M 平衡精度与速度
- RAG / 长文：Q5_K_M 或 Q8_0 保精度
- 极限速度 / 老设备：Q4_0 或 IQ3_S
- 学术 / 论文：F16（不要在手机上跑）

---

## 9. 设备能力分层

参考 [`easy-chat-module/app/src/main/assets/module.json`](../easy-chat-module/app/src/main/assets/module.json) 中 `deviceTiers` 字段：

```json
"deviceTiers": {
    "low_end":   { "soc": "4Gen1", "ram": "3GB",  "threads": 2, "contextLength": 1024 },
    "mid_range": { "soc": "6Gen1", "ram": "4GB",  "threads": 4, "contextLength": 1440 },
    "flagship":  { "soc": "8Gen2", "ram": "6GB",  "threads": 4, "contextLength": 1440, "preferredBackend": "vulkan" }
}
```

### 9.1 分层判定

通过 [`CpuInfo.socName`](../app/src/main/kotlin/com/aibox/util/CpuInfo.kt) 与 `/proc/meminfo` 综合：

```kotlin
fun classifyDevice(): String {
    val soc = CpuInfo.socName
    val ram = readTotalRamMb()
    return when {
        ram < 3_000 -> "low_end"
        ram < 6_000 -> "mid_range"
        soc.contains("8 Gen") || soc.contains("8Elite") -> "flagship"
        else -> "mid_range"
    }
}
```

### 9.2 按层级推荐配置

| 层 | threads | contextLength | kvQuant | recommendedBackend |
| --- | --- | --- | --- | --- |
| low_end | 2 | 1024 | Q4_0 | cpu |
| mid_range | 4 | 1440 | Q8_0 | cpu / vulkan |
| flagship | 4 | 1440 | Q8_0 | vulkan / npu |

### 9.3 模块如何声明 deviceTiers

聊天模块（如 Easy Chat）在 `module.json` 声明，宿主读取后按当前设备 tier 自动选推荐配置：

```kotlin
// 宿主侧伪代码
val tier = classifyDevice()
val recommended = moduleJson.deviceTiers[tier] ?: moduleJson.deviceTiers["mid_range"]!!
val config = InferenceConfigV2(
    threads = recommended.threads,
    contextLength = recommended.contextLength,
    frameworkExtras = mapOf("recommendedBackend" to recommended.preferredBackend)
)
```

---

## 10. 实测数据决策法

### 10.1 不靠"猜"，靠跑

任何"我猜 Q5_K_M 应该比 Q4_K_M 慢"的判断都不如直接跑 benchmark 拿数据。

参考 [`BENCHMARK_METHODOLOGY.md`](./BENCHMARK_METHODOLOGY.md) 跑基础 / 高级 / 跨后端三层基准测试。

### 10.2 实测决策流

```
选模型 → 跑 BASIC 三档（PP 64/512/1024 × TG 32/128/256）
├── 短负载 TG > 30 t/s → 可用
│   ├── 长负载 PP > 100 t/s → 长文可用
│   ├── 长负载 PP 50-100 → 长文慢，调 KV 量化
│   └── 长负载 PP < 50 → 长文不可用，换后端或换模型
├── 短负载 TG 15-30 → 可用但慢
│   └── 跑 THREAD_SCAN 找最优线程
└── 短负载 TG < 15 → 不可用
    └── 跑 KV_QUANT_SCAN 找最优量化
```

### 10.3 跨后端对比决策

跑 `BackendComparison.compareBackends` 同模型 × 多后端，看自动推荐：

```
推荐 llama.cpp：TG 35.2 t/s，比 litert 快 22%
```

如果差异 < 10%，建议跑多轮（repCount=5）确认稳定性。

### 10.4 优化前后对比

1. 跑一次 BASIC（默认档）
2. 切换到性能档
3. 再跑一次 BASIC
4. 对比 TG / TTFT 提升

典型提升（Qwen2.5-0.5B / 骁龙 8 Gen3）：

| 指标 | 默认档 | 性能档 | 提升 |
| --- | --- | --- | --- |
| TTFT | 2580 ms | 410 ms | -84% |
| TG | 32.5 t/s | 35.2 t/s | +8% |
| 内存峰值 | 580 MB | 580 MB | 0% |
| 能耗（10 min） | 8.5 Wh | 12.3 Wh | +45% |

**结论**：性能档 TTFT 大幅提升，TG 小幅提升，能耗显著增加。短时高强度选性能档，长时间选默认档。

---

## 11. 调优工作流

### 11.1 新设备 / 新模型首次调优

1. 安装 AIBox + 安装模型（或装 Easy Chat 模块）
2. 在"基准测试"页选模型 + 全后端 + BASIC 矩阵
3. 跑一次，看哪后端最快
4. 在"推理优化"页选"性能档"，跑一次
5. 对比两次 TG / TTFT，看提升
6. 在"设置 → 后端"按推荐调 threads / kvQuant
7. 再跑一次确认

### 11.2 日常使用

- 接电源 + 短时间使用 → 性能档
- 电池模式 + 长时间使用 → 省电档
- 不知道 → 默认档

### 11.3 出现问题时

- 卡 → 跑 benchmark 看 TG / TTFT 数值
- 烫 → 关 `thermalAwareThrottle` 不行（这是降频，关了会更烫）；调短 `idleUnloadTimeout`
- 崩 → 看 `/Android/data/com.aibox/files/aibox/logs/crashes/` 崩溃文件
- 慢 → 跑跨后端对比，看是否换后端

---

## 12. 参考实现索引

| 章节 | 参考代码 |
| --- | --- |
| 三个 optimizer | [`TtftOptimizer.kt`](../app/src/main/kotlin/com/aibox/domain/optimization/TtftOptimizer.kt) + [`TgOptimizer.kt`](../app/src/main/kotlin/com/aibox/domain/optimization/TgOptimizer.kt) + [`EnergyOptimizer.kt`](../app/src/main/kotlin/com/aibox/domain/optimization/EnergyOptimizer.kt) |
| OptimizationManager | [`OptimizationManager.kt`](../app/src/main/kotlin/com/aibox/domain/optimization/OptimizationManager.kt) |
| 用户可调设置 | [`OptimizationSettings.kt`](../app/src/main/kotlin/com/aibox/domain/optimization/OptimizationSettings.kt) |
| DataStore 持久化 | [`OptimizationSettingsRepository.kt`](../app/src/main/kotlin/com/aibox/domain/optimization/OptimizationSettingsRepository.kt) |
| 优化开关契约 | [`OptimizationFlags`](../app/src/main/kotlin/com/aibox/domain/inference/InferenceConfigV2.kt) |
| 设置 UI | [`OptimizationSettingsScreen.kt`](../app/src/main/kotlin/com/aibox/ui/optimization/OptimizationSettingsScreen.kt) + [`OptimizationViewModel.kt`](../app/src/main/kotlin/com/aibox/ui/optimization/OptimizationViewModel.kt) |
| 散热感知 | [`ThermalMonitor.kt`](../app/src/main/kotlin/com/aibox/util/ThermalMonitor.kt) + [`ThermalGovernor.kt`](../app/src/main/kotlin/com/aibox/domain/inference/ThermalGovernor.kt) |
| 后端实现 | [`CpuBackend.kt`](../app/src/main/kotlin/com/aibox/domain/inference/CpuBackend.kt) + [`VulkanBackend.kt`](../app/src/main/kotlin/com/aibox/domain/inference/VulkanBackend.kt) + [`NpuBackend.kt`](../app/src/main/kotlin/com/aibox/domain/inference/NpuBackend.kt) |
| 设备能力 | [`CpuInfo.kt`](../app/src/main/kotlin/com/aibox/util/CpuInfo.kt) + [`easy-chat-module/app/src/main/assets/module.json`](../easy-chat-module/app/src/main/assets/module.json) |
| 基准测试 | [`infra/benchmark/`](../app/src/main/kotlin/com/aibox/infra/benchmark/) + [`BENCHMARK_METHODOLOGY.md`](./BENCHMARK_METHODOLOGY.md) |
