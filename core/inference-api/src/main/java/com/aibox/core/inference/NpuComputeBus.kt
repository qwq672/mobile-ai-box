package com.aibox.core.inference

/**
 * 通用 NPU 计算层 — **设计草案，仅规划，不实现**。
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * ⚠️  本文件只是架构规划。任何实现代码请勿提交。
 *     原因：NPU 委托实现高度厂商特定，过早写代码会变成屎山。
 *     先定义清楚契约，等 M3 里程碑再决定实现路径。
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * 目标：让任何插件都能调用任意厂商的 NPU 做张量计算，
 *       无需插件自己集成各厂商 SDK（一个插件 APK 可能同时用 Hexagon + NNAPI 兜底）。
 *
 * 设计要点（M3 里程碑再落地）：
 *
 * 1. 三层架构
 *    ┌────────────────────────────────────────────────────┐
 *    │  插件（任何推理/能力插件）                            │
 *    │  调用 NpuComputeBus.execute(graph, inputs)          │
 *    └────────────────┬───────────────────────────────────┘
 *                     │ AIDL（插件 APK ↔ 宿主 NPU Service）
 *                     ▼
 *    ┌────────────────────────────────────────────────────┐
 *    │  NpuComputeBus（宿主侧统一调度，进程内单例）           │
 *    │  - 按 vendor 路由                                   │
 *    │  - 失败 fallback（Hexagon→NNAPI→CPU）                │
 *    │  - 资源配额（防止多插件同时抢占 NPU）                  │
 *    └────────────────┬───────────────────────────────────┘
 *                     │ JNI
 *                     ▼
 *    ┌────────────────────────────────────────────────────┐
 *    │  NpuDelegate（厂商实现：Hexagon V73/V75/V79/V81 ...）  │
 *    │  - 由 :backends:npu-snpe / :backends:npu-hiai 实现   │
 *    │  - 不暴露给插件                                      │
 *    └────────────────────────────────────────────────────┘
 *
 * 2. 张量类型（兼容跨厂商）
 *    - DataType: FP32 / FP16 / INT8 / INT4 / UINT8 / BF16
 *    - Shape: 支持 1D ~ 4D（更高维度按 reshape 拆分）
 *    - Layout: NHWC / NCHW（转换由 NpuDelegate 内部处理）
 *
 * 3. 计算图格式
 *    - 不绑定 PyTorch / TF / ONNX 任何单一格式
 *    - 定义 [NpuComputeGraph]：节点列表 + 边列表 + 内存复用计划
 *    - 支持即时编译（JIT）和预编译缓存（厂商特定，缓存路径私有）
 *
 * 4. 异步执行
 *    - suspend fun execute(graph, inputs): Outcome<Outputs>
 *    - 流式 batch 推理用 Flow<Outputs>
 *    - 超时 / 取消通过 CoroutineContext 控制
 *
 * 5. 兜底策略
 *    - Hexagon 不可用 → NNAPI
 *    - NNAPI 不可用 → CPU（每个插件自带 CPU 实现）
 *    - 兜底失败时返回 [AIBoxError.NpuNotSupported]
 *
 * 6. 资源配额（防 NPU 抢占）
 *    - 全局并发：1（同时只能跑一个 NPU 任务）
 *    - 等待队列：FIFO，超时 30s 后返回 [AIBoxError.NpuNotSupported]
 *    - 内存上限：根据 NPU 实际可用显存动态调整
 *
 * 7. 优先级（Hexagon 版本兼容矩阵）
 *    ┌─────────────────────────────────────────────┐
 *    │ V73 (骁龙 8 Gen 2)        - SNPE 2.x+        │
 *    │ V75 (骁龙 8 Gen 3)        - QNN 2.x+         │
 *    │ V79 (骁龙 X Elite)        - QNN 2.x+         │
 *    │ V81 (骁龙 8 Gen 4 / 8E)   - QNN 2.x+         │
 *    └─────────────────────────────────────────────┘
 *    实现策略：QNN 优先（性能更好），SNPE 兜底（兼容老设备）
 *
 * 8. 安全
 *    - NpuComputeBus 调用前必须验证插件签名
 *    - 厂商 SDK 路径不暴露给插件（防止插件越权加载非法模型）
 *
 * 9. 测试策略（M3 里程碑再具体落地）
 *    - NPU 真机测试：Hexagon V73/V75 真机性能基准
 *    - CPU 兜底测试：所有 graph 必须能在 CPU 上跑出正确结果
 *    - 模拟 NPU 故障：注入 NpuException 验证 fallback 路径
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 状态：DRAFT（仅规划）
 * 下一步：等 M2 完成后再决策是否实现，可能搁置
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 */

// 以下接口签名仅用于规划展示，**没有任何实现**
// 真正实现将位于 :core:npu-bus 模块（M3 里程碑创建）

public interface NpuComputeBus {
    /**
     * 提交一个 NPU 计算图到合适的 NPU 执行。
     *
     * @param graph 计算图（已编译为目标 NPU 的二进制）
     * @param inputs 输入张量
     * @return 计算结果，或在 NPU 不可用时返回 Outcome.Failure
     */
    public suspend fun execute(
        graph: NpuComputeGraph,
        inputs: List<NpuTensor>,
    ): com.aibox.core.common.Outcome<List<NpuTensor>>

    /** 查询当前 NPU 可用性与负载 */
    public fun probe(): NpuStatus
}

public data class NpuStatus(
    public val available: Boolean,
    public val vendor: com.aibox.core.inference.NpuVendor,
    public val hexagonVersion: HexagonVersion?,
    public val freeMemoryMb: Long,
    public val activeJobs: Int,
)

public enum class HexagonVersion {
    V73, V75, V79, V81, UNKNOWN
}

/** 计算图占位（具体结构 M3 决定） */
public interface NpuComputeGraph {
    public val name: String
    public val targetVendor: com.aibox.core.inference.NpuVendor
    public val targetHexagon: HexagonVersion?
    public val inputShapes: List<List<Int>>
    public val outputShapes: List<List<Int>>
    /** 二进制 blob：由 NpuDelegate 编译产物填充 */
    public val binary: ByteArray
}

/** 张量占位 */
public data class NpuTensor(
    public val shape: List<Int>,
    public val dataType: NpuDataType,
    public val layout: NpuLayout,
    public val data: ByteArray,
)

public enum class NpuDataType { FP32, FP16, BF16, INT32, INT16, INT8, INT4, UINT8 }
public enum class NpuLayout { NHWC, NCHW, NDHWC, NCDHW }
