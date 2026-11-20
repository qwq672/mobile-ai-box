# 变更记录 2026-11-21 · 自研思维导图 + 后台保活 + 渲染/后端 bug 修复

## 背景

用户反馈与诉求：

1. 自己写思维导图代码，别只依赖 mermaid.js。
2. 模型支持后台运行——切后台/息屏后，一次 AI 回复中途就停了。
3. Vulkan/OpenCL 后端修好了吗？基准测试能否正常使用（必须含最基本的预填充 PP 与输出 TG 速度）。
4. 结合优化、UI 体验，顺手检查 bug（含上一轮截图发现的：`q8_0/q4_0` 下划线被吞、采样表格列宽溢出、Mermaid 引擎加载竞态、NPU KV 文案不准）。

## 改动

### 1. 自研思维导图（不依赖 mermaid.js / WebView）

- 新增 `app/src/main/kotlin/com/aibox/ui/chat/MindMapCanvas.kt`。
- 解析 mermaid `mindmap` 的缩进树语法（剥掉 `((圆))`/`[方]`/`{菱}` 形状与 `::icon` 修饰），tidy-tree 简化布局：根在上、子节点向下、父节点水平居中于子节点。
- 纯 Compose Canvas 绘制圆角节点 + 二次贝塞尔连线 + 文本（`rememberTextMeasurer`），支持横向/纵向滚动；配色取主题灰阶 role，无蓝色强调色，深浅主题自适应。
- `MarkdownText.kt` 分流：`mindmap` 走自研 Canvas，其余 `flowchart/sequence/...` 仍走 MermaidBlock(WebView)。离线、无外链脚本、无加载竞态。

### 2. 后台运行保活（解决"后台回复中途停"）

根因四项，逐项处理：

- **前台服务**：新增 `infra/inference/InferenceForegroundService.kt`（`START_STICKY` + 常驻低优先级通知 + dataSync 类型），推理期间提升进程优先级；Manifest 增权限与 `<service>` 声明；`ServiceCompat.startForeground` 兼容 Android 14。
- **WakeLock**：`InferenceEngine.withInferenceBoost` 从 `acquire(60_000)` 改为 10 分钟兜底，长回复/慢 NPU 不再被 doze 冻结；finally 里按 `isHeld` 释放并停服务。
- **不再主动掐断**：`onTrimMemory` 移除 `RUNNING_LOW → interrupt()`；切后台系统频繁发的内存回调不再中断生成。仅 CRITICAL 时 `shrinkIfIdle(0, 512)` 收缩空闲 KV，不碰活跃内存。
- **协程作用域**：`AppContainer.inferenceScope`（应用级 + Main）暴露给 `ChatViewModel`，"落库→生成→落库"整链从 `viewModelScope` 迁出；页面销毁/导航离开后本轮生成继续并落库，用户下次进入读到完整回复。打字机协程同步迁移。

说明：这是轻量保活（推理期间存在、结束即停），不常驻不自启；被系统极端回收仍无法 100% 避免，但已覆盖"切后台/息屏中途停"的主要场景。

### 3. Vulkan/OpenCL 后端与基准测试

- **Vulkan**：代码层编译问题此前已修（vulkan.hpp 版本 static_assert 关闭、CI 装 glslc/headers），hosted NPU job 的 Verify 步骤会校验 APK 内含 `libggml-vulkan`。
- **OpenCL 修复**（`native_bridge.cpp`）：
  - 加载分支补 `opencl`：`g_backend_str=="opencl"` 时显式 `load_backend_lib("opencl")`，原来完全没有该分支，选 OpenCL 静默回退 CPU。
  - `openclAvailable()` 改为同时要求 `libOpenCL.so`（驱动）与 `libggml-opencl.so`（backend）存在才报可用，消除"UI 显示可用、实际回退 CPU"的误报。普通 CI 包未编入 ggml-opencl backend，会如实显示不可用并回退 CPU；需要专门构建才有真正 OpenCL 加速。
- **基准测试**：确认可用且包含最基本的 PP（预填充，`pp_size/TTFT`）与 TG（输出，`tokens/(总时长−TTFT)`）两项，非 stub；UI 展示 `PP x t/s · TG x t/s`。本轮未改动其逻辑。

### 4. bug 修复（上一轮截图）

- `MarkdownText.parseInline`：按 CommonMark 规则，`_` 两侧均为字母/数字时视为普通字符，不再把 `q8_0` 与 `q4_0` 的两处下划线误配成斜体吞字；`*` 行为不变。
- `MdTable`：常见 2~4 列改为按可用宽度均分 + 自动换行（`bodySmall`），不再固定 `min76/max220` 撑破窄屏；>4 列仍横向滚动兜底。
- `MermaidBlock`：JS 侧轮询等待引擎就绪（~3s）再渲染，成功时隐藏错误文案；Compose 侧用 renderKey 去重，切断"渲染→改高度→再渲染"的循环竞态。
- NPU/KV 文案：`SettingsScreen` 改为"KV 量化需要 FA，否则 K/V 整体降级 f16"；`OptimizationSettingsScreen` 把"虚拟 NPU 会话"改称"虚拟设备/分片"并说明不是上下文窗口。

## 未改动 / 约束

- 不开分支，直接提交 main；不引入蓝色强调色；不把 DeepSeek 官方 API 背锅。
- llama.cpp 源码由 CI 在构建时拉取（pins v1.0.0），本地工作区缺该目录属正常，出包走 hosted NPU job。

## 验证

- 直接提交 main，优先 hosted NPU job 编译并交付 APK；下载 APK 附 SHA256，建 Pre-release。
