# AIBox

> 离线万能 AI 盒子 · 完全本地推理 · Apache 2.0 开源

## 版本
v261018-1 (pre-release) — v261020-1 准备中

## 项目简介

AIBox 是一个 Android 端**完全离线**的 AI 推理框架。所有模型推理在本地完成，不依赖云端。基于 llama.cpp 提供 LLM 能力，通过插件架构支持 LiteRT-LM / MLC-LLM 等多推理框架，以及聊天模块（含模型）和工具插件。

详细架构见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

## 功能

### 核心推理
- **真 LLM 推理**：基于 llama.cpp v0.4.1，支持 GGUF 格式模型
- **多后端**：CPU / Vulkan (GPU) / OpenCL (GPU) / NPU (Hexagon, 需 QNN SDK)
- **聊天**：流式输出 + 思考模式（Qwen3 /think）+ 多轮上下文
- **模型管理**：导入本地 GGUF + 在线下载（HuggingFace / ModelScope / HF Mirror）

### 插件系统
- **推理框架后端**：第三方插件 APK 提供新的推理框架（如 LiteRT-LM via Hexagon NPU）
- **聊天模块**：第三方插件 APK 打包模型 + 元数据（如 Easy Chat 含 Qwen2.5-0.5B）
- **工具插件**：扩展宿主功能（如模型转换、Tokenizer 工具）
- 跨进程 AIDL Binder 通信，零异常约定（Outcome 类型）
- 宿主侧 `PluginManager` 自动发现 + 兼容性校验 + 签名校验

### 日志系统
- **三层 sink**：RingBuffer（5000 条）+ 异步刷盘 + 按日轮转文件
- **崩溃捕获**：Java/Kotlin 异常 + Native 信号（SIGSEGV/SIGABRT 等）
- **会话日志**：推理 / 基准测试独立日志文件
- 崩溃前 `emergencyFlush` 同步刷盘，闪退也能拿到完整日志
- 日志查看器 UI（按 tag 过滤、按级别着色、按会话独立查看）

### 基准测试系统
- **三层架构**：基础（PP/TG/TTFT）/ 高级（冷启动 / 多轮 / 流式平滑度）/ 跨后端对比
- **环境快照**：SoC / 热档位 / 后台 CPU / 充电状态 / RAM
- **能耗测量**：电流 × 电压反推 + 电量百分比兜底
- **中位数统计**：repCount ≥ 3，过滤 GC 抖动
- 不依赖第三方 App 数据，所有结果走同一代码路径

### 优化系统
- **三个维度**：TTFT（首 token 延迟）/ TG（生成速度）/ 能耗（散热 + 空闲卸载）
- **三档预置**：默认 / 性能 / 省电，一键切换
- **14 个开关**：每个开关独立，可单独开 / 关
- **开关式不调参**：不暴露微参，避免"调到 0.7 还是 0.8"的纠结
- DataStore 持久化，重启保留

### UI 统一
- **Design Token 系统**：颜色 / 字体 / 间距 / 圆角 / 阴影 / 动画统一来源
- **6 个统一组件**：UnifiedSwitch / Dropdown / Slider / Input / Card / Section
- **Schema 渲染引擎**：插件设置页无需写 UI，宿主自动渲染

### 散热管理
- 温度监控（sysfs + PowerManager 综合）
- 自动降频（HOT / CRITICAL 状态触发 thermalAwareThrottle）
- 空闲超时自动卸载模型（释放内存）

## 快速开始（5 分钟）

### 1. 编译宿主 APK

```bash
cd /path/to/aibox
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

### 2. 安装到设备

```bash
adb install app/build/outputs/apk/release/app-release.apk
```

### 3. 启动并下载模型

- 打开 AIBox App
- 进入"模型"页 → 选 HuggingFace / ModelScope / HF Mirror → 搜索 `qwen2.5-0.5b-instruct-q4_k_m`
- 下载（约 400 MB）

### 4. 开始聊天

- 进入"聊天"页 → 选刚下载的模型 → 输入"你好"
- 首次推理慢（TTFT 约 2-3 s），后续快

### 5. 调优（可选）

- 进入"推理优化"页 → 选"性能档"
- 重新开始聊天，TTFT 应降到 500 ms 以内

## 架构介绍

AIBox 分层架构：

```
UI (Compose) → ViewModel → Domain (业务) → Infra (基础设施) → Native (C++)
```

V1 与 V2 推理抽象层共存：
- V1：`InferenceEngine` + `Backend`（含 fallback chain + 配置调优，仅 LLM）
- V2：`BackendManagerV2` + `InferenceBackendV2`（多任务 / 多模态 / 多框架 / 插件）

详细架构图与抽象说明见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

## 文档

| 文档 | 说明 |
| --- | --- |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | 架构设计、分层、关键抽象、生命周期、零异常约定 |
| [`docs/PLUGIN_DEVELOPMENT.md`](docs/PLUGIN_DEVELOPMENT.md) | 插件开发完整指南（AIDL / Gradle / Manifest / 设置 schema / 发布） |
| [`docs/BENCHMARK_METHODOLOGY.md`](docs/BENCHMARK_METHODOLOGY.md) | 基准测试方法论（三层架构 / 环境快照 / 中位数 / 实测决策法） |
| [`docs/PERFORMANCE_TUNING.md`](docs/PERFORMANCE_TUNING.md) | 性能调优指南（TTFT / TG / 能耗 / 三档预置 / 后端选择） |
| [`docs/RELEASE_CHECKLIST.md`](docs/RELEASE_CHECKLIST.md) | v261020-1 发布前完整检查清单 |

## 示例工程

| 工程 | 类型 | 说明 |
| --- | --- | --- |
| [`easy-chat-module/`](easy-chat-module/) | 聊天模块 | 含 Qwen2.5-0.5B Q4_K_M 模型，零配置聊天模块 APK |
| [`litert-plugin/`](litert-plugin/) | 推理框架后端 | LiteRT-LM + Hexagon NPU 加速，支持 Gemma + Siglip 多模态 |

## 设置

- **后端切换**：CPU / Vulkan / OpenCL / NPU（视设备支持）
- **性能配置**：threads / kvQuant / batchSize / contextLength
- **优化档**：默认 / 性能 / 省电
- **主题**：浅色 / 深色（跟随系统）
- **日志**：可见文件 + 级别配置 + 清空

## License

- 宿主：[Apache 2.0](LICENSE)
- Easy Chat 模块：MIT
- LiteRT 插件：Apache 2.0
- llama.cpp：MIT
