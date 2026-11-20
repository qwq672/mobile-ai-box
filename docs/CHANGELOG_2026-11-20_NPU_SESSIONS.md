# 2026-11-20 改动说明：大模型 NPU 加载 + 虚拟会话 + 生图框架起步

## 背景与目标

依据 `荣耀Magic6端侧大模型推理综合测试报告`：4B+ 模型在 Hexagon NPU 上用 Q4_K_M
会因单个约 3.5GB 地址窗口超限导致 PP 暴跌（同 4.21B 模型 Q4_K_M PP 61.67 vs Q4_0 PP 445.14）。
目标是让更大模型（Qwen3.5-9B Q4_0 等）在速度不崩的前提下完整加载并正常对话。

核心判断：llama.cpp 的 **多虚拟会话 layer-split** 是突破单会话窗口的官方手段；
但本项目 native 层此前只把**第一个** HTP 设备传给 llama.cpp，导致多会话形同虚设。

## llama.cpp 版本决策：维持 v1.0.0（跳过升级）

- 项目 pin（`.github/workflows/hexagon-npu.yml`、`build-apk.yml` hosted job）已是 `v1.0.0`（2026-11-13 稳定版）。
- 我们关心的 Hexagon 优化均已在 v1.0.0 之前合并：
  - MoE gating 基础能力（expert-mask skip，PR #26631）
  - 虚拟/多会话（PR #26501、#28589）
  - 防静默 CPU fallback（PR #29511、#30115）
  - 大模型单 session map/unmap（PR #21705）
- 想要的 Qwen3.5 gate/up+GLU 融合（#31820）、DeltaNet HMX 优化仍是 open PR / 别的后端，升级拿不到。
- v1.0.0 之后的 b125xx 是 prerelease，增量与重点无关且 API 漂移风险高 → **不升级**。

## 改动一：修复多会话设备选择 bug（native）

文件：`app/src/main/cpp/native_bridge.cpp`
- `llamaLoad` 中 `s_selected[2]` 只取第一个 HTP 设备就 `break` 的问题；
  改为 `s_selected[9]`，NPU 路径收集**全部** HTP 虚拟会话设备（最多 8 个 + nullptr 终止），
  Vulkan/OpenCL 仍只取一个。这是多会话能否生效的关键。

## 改动二：新增虚拟会话设置（自动/手动/开关）

参数链路（与 forceFlashAttention 同模式）：
```
OptimizationSettingsScreen（FilterChip 自动/手动/关闭 + 手动时滑块 1-8）
  → OptimizationViewModel.setHtpSessionMode/Count
  → optimization DataStore（htp_session_mode / htp_session_count）
  → ModelsViewModel.activate 注入 InferenceConfig
  → NpuBackend.load：auto 按模型文件体积解析会话数
  → NativeBridge.setHexagonNpuSessions → native setenv
  → GGML_HEXAGON_DEVICES=HTP0:0,HTP0:1,...（须在 hexagon 枚举设备前设置）
```

- `HtpSessionMode`（`domain/power/PowerPolicy.kt`）：auto/manual/off 常量、中文标签、
  `resolveSessionCount(文件体积)`：<=2.4GB→1、<=3.6GB→2、<=5.0GB→3、更大→4（上限可配）。
- auto 模式按模型体积自动拆分；manual 用用户设定；off 走默认单会话。
- 仅 Hexagon NPU 后端有效，其他后端无副作用。加载时生效，改后需重新加载模型。

改动文件：
- `app/src/main/cpp/native_bridge.cpp`（新增 JNI setter + 设备选择修复 + `<cstdio>`）
- `app/src/main/kotlin/com/aibox/infra/jni/NativeBridge.kt`（JNI 声明）
- `app/src/main/kotlin/com/aibox/domain/power/PowerPolicy.kt`（HtpSessionMode）
- `app/src/main/kotlin/com/aibox/domain/optimization/OptimizationSettings.kt`（2 字段）
- `app/src/main/kotlin/com/aibox/domain/optimization/OptimizationSettingsRepository.kt`（读写）
- `app/src/main/kotlin/com/aibox/domain/inference/InferenceConfig.kt`（2 字段）
- `app/src/main/kotlin/com/aibox/domain/inference/FrameworkConfig.kt`（merge 保留新字段）
- `app/src/main/kotlin/com/aibox/domain/inference/NpuBackend.kt`（解析 + 设置）
- `app/src/main/kotlin/com/aibox/ui/models/ModelsViewModel.kt`（注入 config）
- `app/src/main/kotlin/com/aibox/ui/optimization/OptimizationViewModel.kt`（setter）
- `app/src/main/kotlin/com/aibox/ui/optimization/OptimizationSettingsScreen.kt`（UI）
- `app/src/main/kotlin/com/aibox/domain/search/providers/SettingRegistry.kt`（搜索项）

## 改动三：生图模块推理框架起步（sd.cpp，阶段一）

采用"独立库 + stub 接口"的增量方式，不破坏现有可用版本：

- `app/src/main/cpp/sdcpp_bridge.cpp`（新）：JNI 骨架，暴露 sdVersion/sdAvailable/
  sdLoadModel/sdTxt2Img/sdUnload，阶段一返回明确 stub 值（不假装能出图）。
- `app/src/main/cpp/CMakeLists.txt`：新增独立 target `aibox_sdcpp`（不依赖 llama.cpp）。
- `app/src/main/kotlin/com/aibox/infra/jni/SdCppBridge.kt`（新）：JNI 门面（@JvmStatic）。
- `app/src/main/kotlin/com/aibox/ui/imagegen/ImageGenViewModel.kt`：`ImageBackend` 新增
  `SDCPP`（宿主内置）项 + 框架版本/可用性诊断字段。UI 因遍历枚举自动出现新卡片。

阶段二（未做）：`add_subdirectory(sd.cpp)` 引入真实引擎，替换 stub 内部实现，Kotlin 侧不动。

## 回归验证基线（Magic6 / SM8650 / Hexagon v75）

- Qwen3.5-4B Q4_0（图2）：PP 445.14 / TG 12.40 / 上下文 8192 / HTP0 —— 多会话改动后应基本保持。
- Qwen3.5-4B Q4_K_M（图1）：PP 61.67 / TG 7.40 —— 对照窗口超限症状。
- Qwen3.5-2B-heretic Q8_0（新截图）：PP 999.80 / TG 18.31 / 上下文 8192 / HTP0（报告待更新项）。
- 新增观测点：日志 `setHexagonNpuSessions`、`llamaLoad: backend=npu -> N device(s)`、
  `虚拟会话: mode=... count=...`；Qwen3.5-9B Q4_0 应看到多设备（2-3）而非 1。

## 编译

- 本地无 Android SDK，走 hosted NPU job（build-apk.yml `build_npu_hosted`），
  llama.cpp pin v1.0.0 保持不变。
