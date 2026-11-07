package com.pocketllm.domain.inference

import kotlinx.serialization.Serializable

/**
 * 推理参数全集。对应 UI 设置页的所有选项。
 *
 * @property backend 后端选择
 * @property nGpuLayers GPU/NPU 层数（仅 vulkan/npu/opencl 生效）
 * @property cpuThreads CPU 线程数，0 表示自动（按大核数）
 * @property antiRollback 防回退开关：true 时禁止 GPU/NPU 频率因热降档回到 CPU（保证速度但可能更烫）
 *                         false 时允许热感知降级到 CPU（默认，发烫治理）
 * @property physicalBatch 物理批处理大小（prompt 阶段）
 * @property batch         逻辑批处理大小（generation 阶段）
 * @property kvQuant       KV cache 量化级别："f16" / "q8_0" / "q4_0"
 * @property contextLength 上下文长度
 * @property temperature 采样温度
 * @property topK Top-K 采样
 * @property topP Top-P 采样
 * @property repeatPenalty 重复惩罚
 * @property autoLoadModel 启动时自动加载上次使用的模型
 */
@Serializable
data class InferenceConfig(
    val backend: BackendType = BackendType.CPU,
    val nGpuLayers: Int = 0,
    val cpuThreads: Int = 0,            // 0 = 自动
    val antiRollback: Boolean = false,  // 默认 false（开启热降级）
    val physicalBatch: Int = 512,
    val batch: Int = 2048,
    val kvQuant: String = "q8_0",
    val contextLength: Int = 4096,
    val temperature: Float = 0.8f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val repeatPenalty: Float = 1.1f,
    val autoLoadModel: Boolean = false,
    val systemPrompt: String = ""
) {
    /**
     * 计算实际使用的线程数。
     * - 用户显式设 > 0 时按用户值
     * - 用户设 0 (UI 显示「自动」) 时按大核数，但限制在 [2, 4]
     *   （大核通常 2-4 个，再多就是中核或小核，反而拖慢推理 + 发烫）
     */
    fun effectiveThreads(bigCores: Int): Int = if (cpuThreads <= 0) bigCores.coerceIn(2, 6) else cpuThreads
}
