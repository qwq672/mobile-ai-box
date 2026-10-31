# AIBox 基准测试方法论

本指南说明 AIBox 自建基准测试系统的使用方法，以及为什么这套系统比依赖第三方 App（PocketPal / OffGrid / llama.cpp Android Demo 等）的数据更可靠。读完本指南你将能：

- 理解为什么 AIBox 不能直接引用其它 App 的 benchmark 数据做选型
- 用 AIBox 自建基准测试的三层架构（基础 / 高级 / 跨后端）跑出可信数据
- 解读 PP t/s / TG t/s / TTFT 三个核心指标，决定何时换模型或换后端
- 用环境快照排除"硬件漂移"导致的误判

参考实现见 [`app/src/main/kotlin/com/aibox/infra/benchmark/`](../app/src/main/kotlin/com/aibox/infra/benchmark/) 与 UI 入口 [`ui/benchmark/`](../app/src/main/kotlin/com/aibox/ui/benchmark/)。

---

## 1. 为什么不能依赖第三方 App 数据

### 1.1 实现差异巨大

不同 App 即使都标榜"基于 llama.cpp bXXXX"，实测同一份模型同一台手机的 TG t/s 可能差 30%-50%。原因：

- **编译选项差异**：是否 `-O3` / `-ffast-math` / 是否开 `-DLLAMA_FAQUANT`、是否链 Vulkan / OpenCL
- **CPU affinity 差异**：是否绑定大核、是否 `taskset` 隔离小核
- **采样器实现差异**：是否复用 `llama_sampler` 句柄、是否每个 token 重建
- **批处理差异**：prompt phase 是 `llama_decode(batch_size=N)` 还是逐 token 调用
- **计时边界差异**：TTFT 是否含 `llama_load_model` 时间，是否含 KV 预分配
- **GC 抖动**：JVM 包装层每 token 调 JNI 一次 vs Kotlin 持 native 句柄批量回传

### 1.2 测试方法学差异

不同 App 的 benchmark 模式天差地别：

- PocketPal：固定 prompt + 固定 max_tokens，无 warmup，单次跑取均值
- OffGrid：跑 N 次取均值，无环境快照
- llama.cpp upstream `llama-bench`：CLI 工具，PP / TG 分开测，但 Android 端没集成 UI

直接拿这些 App 的数据对比，等同于"用 5 把尺子各量一次身高，然后选最长那个"。

### 1.3 AIBox 自建基准测试的定位

AIBox 不打算让用户"用 AIBox 的数据对比 PocketPal"，而是让用户**自己用同一套工具量自己的设备**：

- 用户在 AIBox 选模型 → 选后端 → 跑基准测试 → 拿到一份"该模型 × 该后端 × 该设备"的精确数据
- 用户在 AIBox 选模型 → 选多后端 → 跑跨后端对比 → 拿到"该模型在此设备最优后端"的推荐
- 用户在 AIBox 改优化开关 → 重跑基准 → 拿到"开关前后的实测提升"

所有数据走同一条代码路径、同一份计时逻辑、同一份环境快照，**对比意义明确**。

---

## 2. 三层架构

AIBox 基准测试系统分三层，每层解决不同问题：

| 层 | 入口 | 类 | 解决的问题 |
| --- | --- | --- | --- |
| **Layer 1 基础** | [`BasicBenchmark`](../app/src/main/kotlin/com/aibox/infra/benchmark/BasicBenchmark.kt) | "这个模型在这台手机跑多快？"—— PP / TG / TTFT 三项核心指标 |
| **Layer 2 高级** | [`AdvancedBenchmark`](../app/src/main/kotlin/com/aibox/infra/benchmark/AdvancedBenchmark.kt) | "用户真实使用场景体验如何？"—— 冷启动 / 多轮 / 流式平滑度 |
| **Layer 3 跨后端** | [`BackendComparison`](../app/src/main/kotlin/com/aibox/infra/benchmark/BackendComparison.kt) | "同模型多后端，哪个最快？"—— 自动推荐 |

统一入口在 [`BenchmarkSystem`](../app/src/main/kotlin/com/aibox/infra/benchmark/BenchmarkSystem.kt)，UI 在 [`BenchmarkScreen`](../app/src/main/kotlin/com/aibox/ui/benchmark/BenchmarkScreen.kt)。

---

## 3. Layer 1 基础基准测试矩阵

### 3.1 BASIC 三档负载

参考 [`BenchmarkSpec.kt`](../app/src/main/kotlin/com/aibox/infra/benchmark/BenchmarkSpec.kt) 中的 `BenchmarkMatrix.BASIC`：

| 档 | PP (prompt tokens) | TG (generate tokens) | repCount | 模拟场景 |
| --- | --- | --- | --- | --- |
| 轻负载 | 64 | 32 | 3 | 典型短消息回复 |
| 中负载 | 512 | 128 | 3 | RAG / 长文摘要 |
| 重负载 | 1024 | 256 | 3 | 长文档处理 / 大段续写 |

设计要点：

- **PP × TG 笛卡尔积**：覆盖"短 prompt 短生成 / 短 prompt 长生成 / 长 prompt 短生成 / 长 prompt 长生成"四种典型负载
- **repCount = 3**：最低 3 次以稳定中位数；想更稳可调到 5 或 7
- **warmup = 1**：首次推理触发的 mmap / KV 分配 / 图编译开销不污染结果

### 3.2 THREAD_SCAN 线程扫描

`BenchmarkMatrix.THREAD_SCAN_THREADS = [1, 2, 4, 6]`

用 [`buildThreadScan`](../app/src/main/kotlin/com/aibox/infra/benchmark/BenchmarkSpec.kt) 把基线 spec 复制 4 份，每份只改 `config.threads`：

```kotlin
val threadSpecs = BenchmarkMatrix.buildThreadScan(
    baseSpec = BenchmarkSpec(
        modelPath = "/path/to.gguf",
        config = InferenceConfigV2(),
        ppSize = 64,
        tgSize = 32
    )
)
threadSpecs.forEach { spec -> basicBenchmark.run(backend, loaded, spec) }
```

输出 4 份 `BenchmarkResult`，对比 TG t/s 找出最优线程数。典型结果：

| threads | TG t/s |
| --- | --- |
| 1 | 12.3 |
| 2 | 23.5 |
| 4 | 31.2 |
| 6 | 28.7（线程切换开销开始大于收益） |

**决策**：骁龙 8 Gen3 跑 0.5B 模型，4 线程最优；6 线程因核间切换反而降速。

### 3.3 KV_QUANT_SCAN 量化扫描

`BenchmarkMatrix.KV_QUANT_SCAN_QUANTS = [F16, Q8_0, Q4_0]`

测 KV cache 量化对 PP / TG 的影响：

| kvQuant | PP t/s | TG t/s | 内存占用 | 精度 |
| --- | --- | --- | --- | --- |
| F16 | 245.3 | 35.2 | 100% | 100% |
| Q8_0 | 251.7 | 38.1 | 50% | 99% |
| Q4_0 | 263.4 | 41.5 | 25% | 95% |

**决策**：聊天场景 Q4_0 足够；RAG / 长文摘要建议 Q8_0。

---

## 4. Layer 2 高级场景

参考 [`AdvancedBenchmark.kt`](../app/src/main/kotlin/com/aibox/infra/benchmark/AdvancedBenchmark.kt)。

### 4.1 coldStart 冷启动

```kotlin
suspend fun coldStart(
    backend: InferenceBackendV2,
    modelPath: String,
    config: InferenceConfigV2
): ColdStartResult
```

**测什么**：从"模型未加载"到"首条响应完成"的端到端时长。

**包含**：
1. `loadModel` 时长（mmap / KV 预分配 / 图编译）
2. 首 token 延迟（TTFT）
3. 整次响应时长

**典型结果**（Qwen2.5-0.5B Q4_K_M / 骁龙 8 Gen3 CPU 2 线程）：

| 阶段 | 时长 |
| --- | --- |
| loadModel | 850 ms |
| TTFT | 2580 ms |
| 总响应（32 tokens） | 3620 ms |

**决策**：loadModel + TTFT 占总时长 95%，说明冷启动是瓶颈，应启用 `OptimizationFlags.preloadModel` + `warmupAfterLoad`。

### 4.2 shortChat 短聊天

```kotlin
suspend fun shortChat(
    backend: InferenceBackendV2,
    model: LoadedModel,
    message: String = "你好"
): ShortChatResult
```

**测什么**：单轮"你好" → 回复，TTFT + TG t/s + 总 tokens + 响应文本。

**与 coldStart 区别**：`coldStart` 包含 `loadModel`，`shortChat` 假设模型已加载（用户已用过一次）。

### 4.3 multiTurn 多轮累积上下文

```kotlin
suspend fun multiTurn(
    backend: InferenceBackendV2,
    model: LoadedModel,
    turns: Int = 10
): MultiTurnResult
```

**测什么**：10 轮对话，每轮追加 user + assistant 累积 history，记录：

- `ttftPerTurn: List<Long>` —— 每轮 TTFT（应随 context 增长而增长）
- `tgTpsPerTurn: List<Double>` —— 每轮 TG t/s（应随 KV cache 接近上限而下降）
- `contextGrowth: List<Int>` —— 每轮 context token 数

**典型结果**（Qwen2.5-0.5B / 1440 ctx）：

| 轮 | context tokens | TTFT (ms) | TG t/s |
| --- | --- | --- | --- |
| 1 | 12 | 240 | 35.2 |
| 5 | 480 | 580 | 32.1 |
| 10 | 1320 | 1450 | 28.7 |

**决策**：context 接近 maxContextLength 时 TG 下降 15-20%。如果用户实际聊天超过 10 轮，应考虑开 `continuousKVLayout` 或缩 context。

### 4.4 longPrompt 长 prompt 处理

```kotlin
suspend fun longPrompt(
    backend: InferenceBackendV2,
    model: LoadedModel,
    promptTokens: Int = 4096
): LongPromptResult
```

**测什么**：构造约 `promptTokens` tokens 的 prompt，`maxTokens=16` 只测 PP，返回 `ppTps + ttftMs`。

**用途**：评估 RAG 场景。如果 PP t/s < 100，长文摘要体验差，应换 KV 量化或换后端。

### 4.5 streamingSmoothness 流式平滑度

```kotlin
suspend fun streamingSmoothness(
    backend: InferenceBackendV2,
    model: LoadedModel,
    targetTokens: Int = 100
): StreamingSmoothnessResult
```

**测什么**：记录 100 个 token 的产出间隔，计算：

- `meanIntervalMs` —— 平均间隔
- `varianceMs2` —— 方差
- `maxIntervalMs` —— 最大间隔
- `maxIntervalPosition` —— 最大间隔位置（用于检测 GC 抖动 / NPU 节流）

**典型结果**：

| 后端 | 平均间隔 | 方差 | 最大间隔 | 位置 | 诊断 |
| --- | --- | --- | --- | --- | --- |
| CPU 4 线程 | 28 ms | 12 ms² | 95 ms | token 67 | GC 抖动 |
| Vulkan | 22 ms | 5 ms² | 45 ms | token 12 | 平滑 |
| NPU | 18 ms | 80 ms² | 320 ms | token 50 | NPU 节流（散热降频） |

**决策**：CPU 方差大但平均快；NPU 平均快但偶发卡顿；Vulkan 综合最稳。

---

## 5. Layer 3 跨后端对比

参考 [`BackendComparison.kt`](../app/src/main/kotlin/com/aibox/infra/benchmark/BackendComparison.kt)。

### 5.1 用法

```kotlin
val report = backendComparison.compareBackends(
    modelPath = "/sdcard/Android/data/com.aibox/files/models/qwen2.5-0.5b.gguf",
    modelFormat = "gguf",
    backends = listOf("llamaCpp", "litert", "mlc"),
    baseConfig = InferenceConfigV2(),
    spec = BenchmarkSpec(modelPath, InferenceConfigV2(), ppSize = 64, tgSize = 32, repCount = 3)
)
```

### 5.2 流程

1. 对每个后端调 `loadModel`
2. 用同一份 `BenchmarkSpec` 跑 `BasicBenchmark.run`
3. 收集 `Map<String, BenchmarkResult>`
4. 找出 TG t/s 最高的作为 `recommendedBackend`
5. 生成"人话版"推荐语，如："推荐 llama.cpp：TG 35.2 t/s，比 litert 快 22%"

### 5.3 进度推送

`progress: StateFlow<ComparisonProgress>` 实时推送当前正在跑的后端、已完成数、各后端结果。UI 用 `LinearProgressIndicator` 显示进度条。

### 5.4 失败处理

某后端 `loadModel` 失败（如 LiteRT 加载 GGUF 不兼容）时，`BenchmarkResult.failed` 标记 `failed=true`，不阻塞其它后端测试。最终推荐报告会跳过 failed 项。

---

## 6. 环境快照

参考 [`EnvironmentMonitor.kt`](../app/src/main/kotlin/com/aibox/infra/benchmark/EnvironmentMonitor.kt)。

每次基准测试前后各拍一份 `EnvironmentSnapshot`：

```kotlin
data class EnvironmentSnapshot(
    val deviceSoC: String,            // CpuInfo.socName，如 "Snapdragon 8 Gen 3"
    val cpuFreqCurrent: Long,          // 当前所有 CPU 核最大主频（kHz）
    val thermalState: ThermalState,    // COOL / WARM / HOT / CRITICAL
    val backgroundCpuUsage: Int,       // 后台 CPU 占用（0-100，/proc/stat 差分）
    val chargingState: Boolean,        // 是否充电
    val batteryLevel: Int,             // 电量（0-100，-1 = 读不到）
    val totalRamMb: Int,
    val freeRamMb: Int
)
```

### 6.1 数据来源

| 字段 | 来源 |
| --- | --- |
| `deviceSoC` | [`CpuInfo.socName`](../app/src/main/kotlin/com/aibox/util/CpuInfo.kt)（读 `/proc/cpuinfo`） |
| `cpuFreqCurrent` | `/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq` 取所有核最大值 |
| `thermalState` | [`ThermalMonitor.thermalPercent`](../app/src/main/kotlin/com/aibox/util/ThermalMonitor.kt) + `PowerManager.currentThermalStatus`，按 25/60/85 阈值映射 |
| `backgroundCpuUsage` | `/proc/stat` 两次采样差分 100ms |
| `chargingState` / `batteryLevel` | `Intent.ACTION_BATTERY_CHANGED` sticky intent |
| `totalRamMb` / `freeRamMb` | `/proc/meminfo` MemTotal / MemAvailable |

所有读取都 `try-catch` 兜底，失败返回 0 / "unknown"，不阻塞测试。

### 6.2 热档位含义

参考 [`BenchmarkResult.kt`](../app/src/main/kotlin/com/aibox/infra/benchmark/BenchmarkResult.kt) 中的 `ThermalState`：

| 状态 | thermalPercent | PowerManager 等级 | 含义 |
| --- | --- | --- | --- |
| COOL | 0-24 | NONE / LIGHT | 可正常跑长测 |
| WARM | 25-59 | MODERATE | 可继续跑，但需关注降频 |
| HOT | 60-84 | SEVERE | 明显降频；基准结果需注明 |
| CRITICAL | 85+ | CRITICAL / EMERGENCY / SHUTDOWN | 不可信结果，应中止重跑 |

**关键原则**：测试期间 `thermalState` 跨档位变化（如 WARM → HOT），结果应注明"有降频影响"，不能直接和 COOL 跑出来的数据对比。

### 6.3 环境漂移检测

对比 `envBefore` 与 `envAfter`：

- `thermalState` 升档（如 WARM → HOT）→ 测试结果偏低
- `backgroundCpuUsage` 从 15% 升到 60% → 后台有重负载，结果偏低
- `cpuFreqCurrent` 从 2841 MHz 降到 1800 MHz → 系统主动降频
- `batteryLevel` 降超过 5% → 长测试可能触发电量保护

发现以上漂移时，建议重跑或注明"环境不稳定"。

---

## 7. 中位数 vs 平均

### 7.1 为什么用中位数

参考 [`BasicBenchmark.kt`](../app/src/main/kotlin/com/aibox/infra/benchmark/BasicBenchmark.kt) 的中位数算法。

**原因**：移动设备推理有偶发抖动源：

- GC 暂停（10-200 ms）
- 系统调度抢占（10-50 ms）
- IO 抖动（如其它 App 写盘）
- 热档位瞬降（一个 token 跑出 5x 平均时间）

**均值**被这些抖动拉偏。**中位数**只看"中间那个值"，天然忽略上下两端极值。

### 7.2 数学对比

假设 repCount=5，TG t/s 实测：[35.1, 34.8, 12.3, 35.2, 35.0]

- 均值 = 30.48
- 中位数 = 35.0

那次 12.3 是因为 GC 卡顿。均值比中位数低 13%，得出"这台手机跑 30 t/s"的错误结论。中位数正确反映"正常情况下跑 35 t/s"。

### 7.3 repCount 选择

| repCount | 中位数稳定性 | 耗时 | 适用 |
| --- | --- | --- | --- |
| 1 | 差（无去抖） | 最短 | 快速冒烟测试 |
| 3 | 够（去 1 个极值） | 短 | **默认值，推荐** |
| 5 | 好（去 2 个极值） | 中 | 重要决策 |
| 7+ | 极好 | 长 | 论文级数据 |

> `BasicBenchmark` 默认 repCount=3，warmup=1，5 次推理里 1 次 warmup + 3 次统计 + 1 次兜底。中位数取 `sorted[1]`。

---

## 8. 重复次数与 warmup

### 8.1 warmup 的重要性

首次推理触发的开销：

- `loadModel` 之后的 `llama_decode` 第一次调用要 JIT 编译 Vulkan shader / OpenCL kernel
- KV cache 首次扩容触发 realloc
- 采样器对象首次构建
- Tensorizer 首次编解码 prompt（如果用 BPE，词表加载有约 100 ms 开销）

这些开销会让"首次推理" TTFT 高 200-500 ms，TG t/s 低 10-30%。

### 8.2 warmup 数值

`BenchmarkSpec.warmup = 1` 默认 1 次。如果发现首次推理结果明显异常，调到 2 或 3。

### 8.3 与 preloadModel / warmupAfterLoad 的关系

[`OptimizationFlags`](../app/src/main/kotlin/com/aibox/domain/inference/InferenceConfigV2.kt) 有 `preloadModel` / `warmupAfterLoad` 两个开关。开启后：

- `preloadModel=true` → App 启动时 mmap 权重，省去首次推理的"按需加载页"
- `warmupAfterLoad=true` → loadModel 后跑一次空输入预热

**这两个开关不是 benchmark 的 warmup**。它们是"用户体验优化"，让真实使用时 TTFT 低。benchmark 的 warmup 是"为了让测试结果稳定"，与 `warmupAfterLoad` 是两个独立机制。

---

## 9. 如何解读结果

### 9.1 三个核心指标

| 指标 | 全称 | 单位 | 含义 |
| --- | --- | --- | --- |
| **PP** | Prompt Processing | tokens / second | 处理 prompt 的速度；越高表示 prompt 编码越快 |
| **TG** | Token Generation | tokens / second | 逐 token decode 的速度；越高表示生成越快 |
| **TTFT** | Time To First Token | 毫秒 | 首 token 延迟；主要包含 prompt 处理 + KV 分配 + 首 decode |

### 9.2 三个指标的关系

- 用户感受"卡不卡"主要看 **TTFT**：>1s 用户感觉慢，>3s 用户感觉卡
- 用户感受"输出快不快"主要看 **TG**：>30 t/s 阅读顺，<15 t/s 慢
- 用户感受"长文摘要快不快"主要看 **PP**：>200 t/s 快，<50 t/s 慢

### 9.3 三个指标如何取舍

不同场景对三个指标的敏感度不同：

| 场景 | TTFT | TG | PP |
| --- | --- | --- | --- |
| 短消息聊天 | 高 | 高 | 低 |
| RAG / 长文摘要 | 中 | 中 | 高 |
| 长生成（写代码 / 续写） | 低 | 高 | 低 |
| 流式问答 | 高 | 中 | 中 |

### 9.4 实测数据决策法

不要靠"猜"模型能不能跑，跑 benchmark 拿数据。

**示例决策树**（Qwen2.5-0.5B Q4_K_M on Snapdragon 8 Gen 3）：

```
跑 BASIC 短负载（PP=64, TG=32）
├── TG > 30 t/s → 可用，进入下一项
│   ├── TTFT < 1000 ms → 良好，默认推荐
│   ├── TTFT 1000-3000 ms → 可接受，建议开 preloadModel
│   └── TTFT > 3000 ms → 卡，建议开 preloadModel + warmupAfterLoad
├── TG 15-30 t/s → 可用但慢，建议开 forceFlashAttention
├── TG < 15 t/s → 不可用，换更小模型或换后端
│   ├── 换 0.5B Q4_0 → 重跑
│   ├── 换 Vulkan 后端 → 重跑
│   └── 换 NPU 后端 → 重跑
└── failed → 检查日志，可能是模型格式不兼容
```

### 9.5 何时该换模型

基于实测数据决策，不要凭"我猜这个模型应该能跑"。

**触发条件**（任一满足）：

- TG t/s < 10（用户阅读体验差）
- TTFT > 5000 ms（用户感觉卡）
- 冷启动总时长 > 10 s（用户嫌慢）
- 内存峰值 > 设备 RAM 80%（容易 OOM）
- 多轮 context > maxContextLength 70% 时 TG 下降 > 30%

**换模型决策树**：

```
当前模型不满足 → 检查瓶颈
├── TG 低 + 内存富余 → 换同家族更大模型？不！TG 低通常意味着后端/线程不够优；先调 threads / KV 量化
├── TG 低 + 内存紧 → 换更小量化（Q4_K_M → Q4_0 → IQ3_S）
├── TTFT 高 + 内存富余 → 开 preloadModel + warmupAfterLoad，重测
├── TTFT 高 + 内存紧 → 减 contextLength
├── PP 低 + 短 prompt → 换更小模型（0.5B → 0.2B），或换 NPU 后端
└── PP 低 + 长 prompt → 换 NPU 后端或减小 context
```

---

## 10. 完整使用流程

### 10.1 在 UI 中跑一次基础基准测试

1. 打开 AIBox App → 进入"基准测试"页（通过抽屉导航，或在 `AppNav` 中加 `Dest.Benchmark`）
2. 选择模型（从已下载模型列表）
3. 选择后端（FilterChip 多选，默认全选）
4. 选择测试矩阵（`BASIC` / `THREAD_SCAN` / `KV_QUANT_SCAN` / `COMPARISON` / `COLD_START` / `MULTI_TURN` / `SMOOTHNESS`）
5. 调整 repCount（默认 3）和 warmup（默认 1）
6. 点击"开始测试"

UI 代码见 [`BenchmarkScreen.kt`](../app/src/main/kotlin/com/aibox/ui/benchmark/BenchmarkScreen.kt)，全部用统一组件库（`UnifiedCard` / `UnifiedSection` / `UnifiedDropdown` / `SettingsListRenderer`）。

### 10.2 解读结果

每个 `BenchmarkResult` 显示为一张卡片：

```
=== BasicBenchmark Result ===
spec: PP=64 TG=32 repCount=3
PP t/s:  245.3
TG t/s:  35.2
TTFT:    285 ms
总时长:  1180 ms
峰值内存: 412 MB
能耗:    1280 mJ

环境:
  SoC:    Snapdragon 8 Gen 3
  CPU:    2841 MHz
  热档位:  COOL (12%)
  后台CPU: 18%
  电量:    85% (充电中)
  RAM:    12 GB / 4.2 GB free

日志: /sdcard/Android/data/com.aibox/files/aibox/logs/benchmarks/bench-<id>.log
```

### 10.3 历史对比

每次测试结果写入 Room 数据库 `benchmark_results` 表（参考 [`BenchmarkStorage.kt`](../app/src/main/kotlin/com/aibox/infra/benchmark/BenchmarkStorage.kt)）。UI 历史区显示最近 N 条结果，可看到"同模型不同后端"、"同模型不同优化档"、"不同时间同模型"的对比。

### 10.4 日志关联

每次 benchmark 调 `LogSystem.startSession` 创建独立 `sessionId`，原始数据写入 `sessions/bench-<id>.log`。崩溃时（如 native crash）该文件作为独立附件随崩溃报告导出。

---

## 11. 常见误用与陷阱

### 11.1 在充电时跑 benchmark

**问题**：充电时设备发热快，热档位容易升到 HOT，结果偏低。

**修复**：跑 benchmark 前拔掉充电线，让设备处于稳定温度。

### 11.2 后台有其它 App 占 CPU

**问题**：后台微信 / 网易云等占 CPU 20-40%，benchmark 结果偏低。

**修复**：测试前清理后台。`EnvironmentSnapshot.backgroundCpuUsage` 字段可用来判断当前是否有干扰。

### 11.3 直接拿两条 `BenchmarkResult` 对比但环境差异大

**问题**：早上 COOL 状态跑 TG=35 t/s，下午 HOT 状态跑 TG=28 t/s，得出"模型变慢了"错误结论。

**修复**：对比前先看 `EnvironmentSnapshot.thermalState`，环境跨档位时不能直接对比。

### 11.4 用 mean 而非 median

**问题**：自己写脚本对 `BenchmarkResult` 取均值，被 GC 抖动拉偏。

**修复**：`BasicBenchmark` 已默认中位数。从 Room 历史表读数据时也按"取中位数"语义理解，不要二次平均。

### 11.5 PP t/s 用 TTFT 近似

**问题**：`BasicBenchmark` 用 `ppTps = ppSize / TTFT` 近似，实际 prompt 处理时间略短。

**原因**：TTFT 含 prompt 处理 + KV 分配 + 首 decode，比纯 prompt 处理多约 5-10%。

**修复**：跨后端对比时误差均匀（都含这三部分），结论可靠。单后端绝对 PP t/s 值会略低于真实值，需在文档中注明。

### 11.6 能耗数据为 null

**问题**：多数 Android 设备 `BATTERY_PROPERTY_CURRENT_NOW` 不可读（厂商限制），`EnergyProfiler` 回退到电量百分比反推，精度有限。

**修复**：能耗字段为 null 时不要强行解读；绝对能耗测量需外部功率计，已在 [`EnergyProfiler.kt`](../app/src/main/kotlin/com/aibox/infra/benchmark/EnergyProfiler.kt) KDoc 标注。

---

## 12. 参考实现索引

| 章节 | 参考代码 |
| --- | --- |
| 三层架构 | [`BenchmarkSystem.kt`](../app/src/main/kotlin/com/aibox/infra/benchmark/BenchmarkSystem.kt) |
| Layer 1 基础 | [`BasicBenchmark.kt`](../app/src/main/kotlin/com/aibox/infra/benchmark/BasicBenchmark.kt) + [`BenchmarkSpec.kt`](../app/src/main/kotlin/com/aibox/infra/benchmark/BenchmarkSpec.kt) |
| Layer 2 高级 | [`AdvancedBenchmark.kt`](../app/src/main/kotlin/com/aibox/infra/benchmark/AdvancedBenchmark.kt) |
| Layer 3 跨后端 | [`BackendComparison.kt`](../app/src/main/kotlin/com/aibox/infra/benchmark/BackendComparison.kt) |
| 环境快照 | [`EnvironmentMonitor.kt`](../app/src/main/kotlin/com/aibox/infra/benchmark/EnvironmentMonitor.kt) + [`BenchmarkResult.kt`](../app/src/main/kotlin/com/aibox/infra/benchmark/BenchmarkResult.kt) |
| 能耗测量 | [`EnergyProfiler.kt`](../app/src/main/kotlin/com/aibox/infra/benchmark/EnergyProfiler.kt) |
| Room 持久化 | [`BenchmarkStorage.kt`](../app/src/main/kotlin/com/aibox/infra/benchmark/BenchmarkStorage.kt) + [`data/db/Entities.kt`](../app/src/main/kotlin/com/aibox/data/db/Entities.kt) |
| UI | [`BenchmarkScreen.kt`](../app/src/main/kotlin/com/aibox/ui/benchmark/BenchmarkScreen.kt) + [`BenchmarkViewModel.kt`](../app/src/main/kotlin/com/aibox/ui/benchmark/BenchmarkViewModel.kt) |
| 优化开关影响 | [`OptimizationFlags`](../app/src/main/kotlin/com/aibox/domain/inference/InferenceConfigV2.kt)（见 [`PERFORMANCE_TUNING.md`](./PERFORMANCE_TUNING.md)） |
