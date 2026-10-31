# AIBox 模块方案设计 v2

> 性能 / 能耗 / 兼容性的模块架构调整说明

---

## 一、本轮新增模块清单

| 模块 | 用途 | 文件 |
|------|------|------|
| `core/perf-config` | 性能配置集中契约 | `PerformanceConfig.kt`, `BackendRecommender.kt` |
| `core/tool-api` | 工具调用契约 | `Tool.kt` |
| `feature/home` | 自定义主页 + 首次向导 | `HomePageConfig.kt`, `OnboardingFlow.kt` |
| `feature/image`（扩展） | 加编辑/超分/擦除 | `ImageCapability.kt` |
| `feature/persona`（扩展） | 加"我"的角色 | `Persona.kt` v2 |
| `feature/agent`（重构） | 接入 tool-api + 内置工具 | `Agent.kt` v2, `BuiltinTools.kt` |

模块总数从 22 → **25 个**（新增 3 个 core + 1 个 feature，feature/image 是扩展）。

---

## 二、性能配置方案（回答 Q4）

### 2.1 配置完全集中

所有 14 项性能参数都在 `core/perf-config/PerformanceConfig.kt` 单一真相源定义：

```kotlin
PerformanceConfig
├── profile: PerformanceProfile (极速/平衡/节能/自定义)
└── advanced: AdvancedPerformanceConfig
    ├── flashAttention      (FA 开关)
    ├── kvCacheSize         (KV cache)
    ├── cpuThreads          (CPU 核心数)
    ├── batchSize           (逻辑批处理)
    ├── physicalBatchSize   (物理批处理)
    ├── contextLength       (上下文长度)
    ├── memoryCompression   (内存压缩档位 Q4_K_M 等)
    ├── contextCompression  (上下文压缩策略)
    ├── gpuLayers           (GPU/NPU 层数)
    ├── powerMode           (功耗设置 PERFORMANCE/BALANCED/EFFICIENCY)
    ├── speedLimitTps       (速度限制)
    ├── thermalMode         (发热控制 NONE/ADAPTIVE/AGGRESSIVE/STRICT)
    ├── mtpEnabled          (推测解码)
    └── visionPrewarm       (图像识别预热)
```

### 2.2 后端自动隐藏不适用项

调用 `config.applicableFor(backendId)` 返回当前后端支持的字段集合。例：

| 后端 | 隐藏的字段 |
|------|----------|
| `npu-snpe` (Hexagon) | KV cache, CPU 线程, FA 开关, MTP, GPU 层数 |
| `llama.cpp` | 全部支持 |
| `tflite` | 批处理、KV cache、MTP、内存压缩 |
| `mnn` | FA、KV cache、物理批处理、上下文压缩、MTP |

UI 根据 `applicableFor` 决定哪些设置项显示，避免用户调了无效参数。

### 2.3 三档预设 + 自定义

```kotlin
PerformanceProfile.SPEED    // 极速：8 线程 + 全 GPU + MTP + FA + 32 批 + AGGRESSIVE 散热
PerformanceProfile.BALANCED // 平衡：4 线程 + 0 GPU + 无 MTP + ADAPTIVE 散热
PerformanceProfile.POWER     // 节能：2 线程 + 0 GPU + Q4_K_S + STRICT 散热 + 限速 30 tok/s
PerformanceProfile.CUSTOM    // 自定义（用户手调）
```

普通用户一键切换，高级用户展开细调。

### 2.4 后端推荐器（新增）

```kotlin
BackendRecommender.recommendForUseCase(UseCase.CHAT, installedBackends)
  → 返回排序后的推荐列表（含可解释理由）
```

应用场景：
- 首次启动 onboarding："想用 AIBox 做什么？" → 推荐后端
- 用户加载模型后：根据模型格式推荐
- 自动调优：作为初始候选集

---

## 三、工具调用架构（回答 Q3）

### 3.1 内置 vs 插件分层

```
        ┌─────────────────────────────────┐
        │   Agent (feature/agent)         │
        │   LLM 决策 → 调用工具 → 反馈    │
        └──────────┬──────────────────────┘
                   │ 通过 ToolRegistry 查询
                   ▼
        ┌─────────────────────────────────┐
        │   ToolRegistry (core/tool-api)   │
        │   list() / find(id) / execute() │
        └──────────┬──────────────────────┘
                   │
       ┌───────────┴───────────┐
       ▼                       ▼
   ┌─────────┐           ┌──────────────┐
   │ 内置工具 │           │  插件工具 APK │
   │  (5个)  │           │   (用户按需)  │
   └─────────┘           └──────────────┘
```

### 3.2 内置工具清单（5 个）

| 工具 | 用途 | 敏感度 |
|------|------|------|
| `DateTimeTool` | 获取当前时间 | SAFE |
| `CalculatorTool` | 数学计算 | SAFE |
| `DeviceInfoTool` | 设备型号/SoC/内存 | CONFIRM |
| `JavaScriptTool` | JS 沙箱执行（QuickJS） | CONFIRM |
| `FileSystemTool` | 限于 aibox/files 的文件操作 | SENSITIVE |

### 3.3 高级工具（插件 APK 分发，未内置）

| 工具 | 为何不内置 |
|------|----------|
| 邮件管理 | 需邮箱授权，隐私敏感 |
| 手机操控 | 需无障碍权限 |
| 短信 | 涉及运营商费用 |
| 网络搜索 | 违背"完全离线"原则 |
| 日历 | 需日历读写权限 |

### 3.4 敏感操作两层防护（按你要求）

```
LLM 决定调用 FileSystemTool(action=delete, path=x)
    ↓
Agent 推 [AgentEvent.ToolCallRequested] 到 UI
    ↓
[第一层] 检查 tool.sensitivity：
    SAFE → 直接执行
    CONFIRM → 弹确认对话框
    SENSITIVE → 弹确认对话框 + 二次确认
    ↓
[第二层] ToolExecutionGate.approve(tool, args) → 用户授权后才执行
    ↓
Tool.execute(args) → 返回 ToolResult
    ↓
反馈给 LLM 继续推理
```

---

## 四、模块方案优化（回答 Q5）

### 4.1 现有方案问题

| 问题 | 影响 | 修复方式 |
|------|------|---------|
| 性能参数散落在 BackendConfig | 14+ 项散落成屎山 | 抽出 `core/perf-config` 集中 |
| 工具调用绑定 Agent | Chat 无法直接调工具 | 抽出 `core/tool-api` 复用 |
| 后端选择靠用户手选 | 新手不会选 | 加 `BackendRecommender` |
| 多模态模型混在 InferenceBackend | 不是所有后端支持 | 后续拆 `MultimodalBackend` 子接口 |
| 性能配置 UI 无预设 | 用户调不出合理组合 | 加 `PerformanceProfile` 三档预设 |

### 4.2 新增模块依赖关系

```
core/perf-config
    ↑ (被以下模块依赖)
    ├── app                  (UI 设置页)
    ├── feature/chat         (聊天推理时用)
    ├── feature/agent        (Agent 任务用)
    ├── feature/benchmark    (基准测试配置)
    └── feature/autotune     (调优时切换)

core/tool-api
    ↑
    ├── feature/agent        (Agent 内置工具)
    ├── feature/chat         (聊天内可用工具)
    └── plugins/*            (插件 APK 实现工具)
```

### 4.3 性能/能耗/兼容性综合方案

**性能优先级**：

1. **冷启动时间** < 2 秒
   - 用 Hilt 编译期注入，避免反射
   - Compose 模块独立，UI 按需加载
   - .so 库按 ABI 分拆

2. **首字延迟** < 500ms
   - 推理后端预热（warmup）
   - 模型 mmap 加载而非全加载
   - 性能档位 = SPEED 时跳过 warmup

3. **能耗优化**
   - `PowerMode.EFFICIENCY` 时降频
   - `ThermalMode.STRICT` 监测温度超 38℃ 自动降频
   - 推理结束自动释放 NPU 资源
   - 后台时停止所有推理

4. **兼容性**
   - 三档兜底：Hexagon → NNAPI → CPU
   - 模型量化兼容性诊断在加载前告知用户
   - API 不可用时优雅降级（如 NPU 占用率采集）

### 4.4 关键性能契约（写入 docs/PERFORMANCE_CONTRACT.md 待写）

```
设备：骁龙 8 Gen 3 + 12GB RAM
模型：Qwen2-1.5B-Q4_K_M (900MB)
后端：llama.cpp (CPU)
配置：BALANCED
预期：
  - 输出速度：≥ 18 tok/s
  - 预填充速度：≥ 80 tok/s
  - 内存占用：≤ 1.2 GB
  - 首字延迟：≤ 600 ms

设备：骁龙 8 Gen 3 + 12GB RAM
模型：Qwen2-1.5B-Q8_0 (1.5GB)
后端：Hexagon V75 (NPU)
配置：SPEED
预期：
  - 输出速度：≥ 120 tok/s
  - 预填充速度：≥ 500 tok/s
  - 内存占用：≤ 1.8 GB
  - 首字延迟：≤ 200 ms
```

低于预期则触发 NudgeDecider 提示。

---

## 五、未来功能矩阵更新

| 功能 | 模块 | 阶段 | 状态 |
|------|------|------|------|
| 聊天 + 角色扮演 | `feature/chat` + `feature/persona` v2 | M1 | ✅ 接口已定 |
| 生图 | `feature/image` (ImageGenerator) | M4 | ✅ |
| 图像编辑 | `feature/image` (ImageEditor) | M4 | ✅ |
| 图像超分 | `feature/image` (ImageUpscaler) | M4 | ✅ |
| 图像擦除 | `feature/image` (ImageEraser) | M4 | ✅ |
| 音频生成 + TTS | `feature/audio` | M4 | ✅ |
| 翻译 | `feature/translate` | M5 | ✅ |
| 本地 Agent + 工具调用 | `feature/agent` + `core/tool-api` | M5 | ✅ |
| 基准测试 | `feature/benchmark` | M3 | ✅ |
| 自动调优 | `feature/autotune` | M5 | ✅ |
| 自定义主页 | `feature/home` | M2 | ✅ |
| 首次启动向导 | `feature/home` (OnboardingFlow) | M2 | ✅ |
| NPU 兼容诊断 | `core/diagnostics` | M3 | ✅ |
| 性能配置集中管理 | `core/perf-config` | M2 | ✅ |
| 后端智能推荐 | `core/perf-config` (BackendRecommender) | M2 | ✅ |
| NPU 通用计算层 | `core/inference-api/NpuComputeBus` | 搁置 | 仅签名 |
| 插件市场 UI | `feature/plugin-market` | M2 | 未建 |
| 模型市场 UI | `feature/model-market` | M2 | 未建 |

---

## 六、当前 Gradle 模块完整清单

```
aibox/  (25 个模块)
├── app/                                   ← 主应用
├── core/                                  ← 8 个契约模块
│   ├── common
│   ├── logger
│   ├── inference-api
│   ├── plugin-host
│   ├── ui-design
│   ├── diagnostics          [新]
│   ├── perf-config          [新]
│   └── tool-api             [新]
├── feature/                               ← 9 个特性模块
│   ├── chat
│   ├── image                [扩展]
│   ├── audio
│   ├── persona              [扩展]
│   ├── translate
│   ├── agent                [重构]
│   ├── benchmark
│   ├── autotune
│   └── home                 [新]
├── backends/                              ← 7 个后端
│   ├── llamacpp / tflite / onnx / mlc / mnn
│   └── npu-nnapi / npu-snpe / npu-hiai
└── plugins/sample-echo/                   ← 1 个示例插件
```

---

## 七、关键反屎山检查表（每次新增模块必查）

- [ ] 新模块的 build.gradle.kts 是否最小化依赖？
- [ ] 是否只依赖 `core/*` 接口，不依赖 `backends/*` 实现？
- [ ] 配置类是否做成 data class 且不可变？
- [ ] 是否有 `core/*` 模块被绕过直接引用 `feature/*`？
- [ ] 新功能是否在 `docs/` 下有对应文档？
- [ ] 是否有单元测试覆盖关键决策逻辑？
- [ ] 是否有"用户可关闭"的开关（避免强制行为）？

---

## 八、下一步建议

按里程碑推进：

| 优先级 | 任务 |
|-------|------|
| P0 | M1：实现 llama.cpp JNI 桥接，跑通 2B Q4_K 模型 |
| P0 | M2：实现 `feature/home` UI + onboarding 流程 |
| P1 | M2：实现插件市场 UI 骨架 |
| P1 | M3：实现 `feature/benchmark` 完整 + 6 条曲线绘制 |
| P2 | M3：实现 Hexagon NPU 后端 + 性能验证 |
| P2 | M4：实现图像 4 件套（生图/编辑/超分/擦除） |
| P3 | M5：实现 Agent + 5 个内置工具 |
| P3 | M5：实现自动调优执行引擎 |
