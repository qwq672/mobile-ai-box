# AIBox 内置功能矩阵

> 软件本体内置什么 vs 不内置什么的决策依据

## 决策原则

```
                    体积小         体积大(>10MB)
                  ┌────────────┬─────────────────┐
   通用 / 开箱即用 │  ✅ 内置     │  ❌ 插件         │
                  │  (NNAPI)    │  (MNN/TFLite等) │
                  ├────────────┼─────────────────┤
   厂商私有授权    │  ⚠️ 插件     │  ❌ 插件         │
                  │             │  (Hexagon/HiAI) │
                  └────────────┴─────────────────┘
```

## 一、宿主 APK 内置功能清单

宿主 APK 体积预算：**~30MB**（按 ABI 分拆后单包）

### 1. 核心契约层（必须内置，无 .so）

| 模块 | 用途 | 体积 |
|------|------|------|
| `core:common` | Outcome / Dispatchers / 常量 | <100KB |
| `core:logger` | 双轨日志（Room + 文件） | <500KB |
| `core:inference-api` | InferenceBackend 接口定义 | <100KB |
| `core:plugin-host` | 插件发现 + 签名校验 + AIDL | <500KB |
| `core:ui-design` | MD3 主题 + 动态配色 | <100KB |

### 2. UI 功能层（必须内置，无 .so）

| 功能 | 用途 | 必要性 |
|------|------|--------|
| 聊天 UI | 多轮对话、流式渲染、可中断 | ✅ 核心体验 |
| 日志查看器 | 应用内可见所有日志（用户强需求） | ✅ 必须 |
| 插件管理 | 发现/启用/禁用/卸载插件 | ✅ 必须 |
| 模型管理 | 下载/校验/管理本地模型文件 | ✅ 必须 |
| 设置 | 后端切换、NPU 开关、主题、日志级别 | ✅ 必须 |
| 角色管理 | Persona 卡片导入导出 | ✅ 核心体验 |
| 生图 UI | 文生图/图生图界面 | ⚠️ M4 阶段加入 |
| 音频 UI | TTS/STT 界面 | ⚠️ M4 阶段加入 |

### 3. 内置推理后端（带 .so）

| 后端 | 内置理由 | .so 体积 | 是否带 NPU |
|------|----------|----------|------------|
| `backends:llamacpp` | 基础 LLM 后端，开箱即用 | ~8MB (arm64-v8a) | 否（纯 CPU） |
| `backends:npu-nnapi` | 零 .so 成本，通用 NPU 兜底 | 0 | 是（系统 NNAPI） |

**llama.cpp 是唯一内置的"带 .so"后端**，理由：
- 用户首次安装 AIBox 后立刻能跑 LLM（不用先去插件市场）
- llama.cpp 是 GGUF 量化模型的事实标准，社区支持最广
- 体积 8MB 在可接受范围（参考：微信安装包 ~300MB，普通 App ~30MB 是合理水位）

## 二、插件 APK 分发的功能清单

### 1. 重型推理后端（独立插件）

| 插件名 | 内含 .so | 体积 | 用户场景 |
|--------|----------|------|----------|
| `aibox-plugin-mnn` | libMNN*.so | ~15MB | 用 MNN-SD 跑生图 |
| `aibox-plugin-onnx` | libonnxruntime.so | ~8MB | 跑 ONNX 格式模型 |
| `aibox-plugin-tflite` | libtensorflowlite.so | ~5MB | 跑 TFLite 格式模型 |
| `aibox-plugin-mlc` | libmlc_llm.so + tvm | ~30MB | LLM 高性能推理（Vulkan） |
| `aibox-plugin-npu-snpe` | libQNN.so + libsnpe.so | ~20MB | 骁龙 Hexagon V73~V81 加速 |
| `aibox-plugin-npu-hiai` | libhiai*.so | ~15MB | 华为 NPU 加速 |

### 2. 能力插件（独立 APK）

| 插件名 | 用途 | 体积 |
|--------|------|------|
| `aibox-plugin-sd-mnn` | Stable Diffusion 生图（MNN 版） | 5MB + 模型用户自备 |
| `aibox-plugin-vits-tts` | VITS 文本转语音 | 3MB + 模型用户自备 |
| `aibox-plugin-whisper` | Whisper 语音识别 | 5MB + 模型用户自备 |
| `aibox-plugin-controlnet` | ControlNet 扩展（图生图） | 5MB + 模型用户自备 |

### 3. NPU 通用计算层（独立插件，按需）

| 插件名 | 用途 | 状态 |
|--------|------|------|
| `aibox-plugin-npu-bus-hexagon` | NpuComputeBus Hexagon 实现 | DRAFT，未实现 |
| `aibox-plugin-npu-bus-nnapi` | NpuComputeBus NNAPI 实现 | DRAFT，未实现 |

## 三、内建 vs 插件，怎么决定一个新功能该放哪边？

### 决策树

```
新功能 X 是否属于核心体验？
├── 是 → X 是否需要大体积 .so（>5MB）？
│   ├── 否 → ✅ 内置到宿主 APK
│   └── 是 → ⚠️ 评估：能否拆分为「接口内置 + 实现插件」？
│       ├── 是 → ✅ 接口内置于宿主，实现作为插件分发
│       └── 否 → ❌ 整体作为插件分发
└── 否 → X 是否依赖厂商私有授权？
    ├── 是 → ❌ 必须作为插件分发（避免合规问题）
    └── 否 → ⚠️ 评估使用频率
        ├── 高频 → ✅ 内置
        └── 低频 → ❌ 插件
```

### 反例（避免的错误）

| 错误 | 后果 |
|------|------|
| 把 MNN 15MB .so 内置到宿主 | 宿主体积爆涨，不跑 SD 的用户被强迫下载 |
| 把日志查看器作为插件 | 用户排错时插件没装就完蛋了 |
| 把 Hexagon SDK 内置到宿主 | 厂商私有授权合规问题 |
| 把 llama.cpp .so 作为插件 | 用户首次安装后无法开箱即用，体验崩盘 |

## 四、模型文件不在 APK 里

无论内置还是插件，**模型文件都不打进 APK**：
- 模型体积动辄 100MB~5GB，APK 容纳不下
- 用户需要的是"按需下载"，不是"打包全量"
- 模型通过 [ResumableDownloader] 断点续传下载到 `/Android/data/com.aibox/files/aibox/models/`
- 首次启动某功能时引导用户下载模型，模型列表通过宿主配置文件管理

## 五、宿主 APK 体积预算细项

```
aibox-host.apk (arm64-v8a 单包)
├── Kotlin/Java 代码                  ~2MB
├── Compose UI 资源                    ~3MB
├── core/* 模块代码                    ~1MB
├── backends/llamacpp/.so             ~8MB
├── backends/npu-nnapi/.so            0 (用系统 API)
├── Room/SQLite native                 ~1MB
├── Hilt + Coroutines 运行时          ~2MB
├── 应用图标/字体/字符串              ~1MB
└── ─────────────────────────────────────
   总计                              ~18MB  (远低于 30MB 预算)
```

对比：微信 ~300MB、抖音 ~250MB、Telegram ~80MB。AIBox 18MB 是"良心体积"。

## 六、用户视角的安装流程

```
首次安装 AIBox（18MB）
   ↓
打开应用 → 引导页：「AIBox 离线万能AI盒子」
   ↓
内置：聊天界面可用（CPU llama.cpp）
内置：日志查看器可用
内置：插件管理可用
内置：模型管理可用
   ↓
首次聊天时引导：
   "需要下载一个基础模型（Qwen2-1.5B Q4_K_S, 900MB）"
   ↓
下载完成后 → 聊天跑通

用户想跑 SD 生图：
   设置 → 插件管理 → 安装 "aibox-plugin-sd-mnn"
   ↓
   安装插件后 → 自动引导下载 SD 模型（如 SD1.5 量化版 500MB）

用户想跑 Hexagon NPU 加速：
   设置 → 插件管理 → 安装 "aibox-plugin-npu-snpe"
   ↓
   安装插件后 → 设置页出现 "NPU 加速" 开关
```

## 七、未来可能调整的边界

如果将来发现某些"插件"使用率超过 80%（几乎所有用户都装）：
- 考虑合并到宿主 APK（如 MNN-SD 可能升级为内置）
- 但仍保持插件化接口，方便用户禁用

如果某些"内置"功能用户反馈太占空间：
- 考虑拆成可选插件（如 llama.cpp 可能变成可选）
- 但要保证至少一个开箱即用的后端留在宿主内
