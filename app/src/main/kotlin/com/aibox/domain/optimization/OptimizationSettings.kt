package com.aibox.domain.optimization

import com.aibox.domain.inference.OptimizationFlags

/**
 * 优化设置（用户可调，P3）。
 *
 * 设计目标：
 * - 与 [OptimizationFlags] 形状对齐，便于直接 [toFlags] 转换为后端可消费的形式。
 * - 把"是否启用空闲超时卸载"拆成两个独立字段：[idleUnloadTimeout]（开关）+ [idleUnloadTimeoutMs]（时长），
 *   便于在设置页用单独的 SWITCH + SLIDER 渲染；而 [OptimizationFlags.idleUnloadTimeout] 只有 Long
 *   （0 = 永不卸载，正数 = 超时毫秒），由 [toFlags] 时根据开关决定 0 或具体时长。
 * - 三档预置 [DEFAULT] / [PERFORMANCE] / [POWER_SAVE]，与 [OptimizationFlags] 的三档预置保持语义对齐，
 *   用户在 UI 顶部一键切换。
 *
 * 通用约定（与 Task P3 的核心约束一致）：
 * - **所有优化通用、对所有模型一视同仁**：Qwen / LFM / Hunyuan / SmolLM 走相同代码路径，
 *   不针对任何特定模型做特殊优化。
 * - 所有优化都有开关，用户可关闭单个；不依赖自动调优（不动 threads / KV 类型 / batch 选择）。
 *
 * 字段分组（与设置页 UI 的三个 Section 一一对应）：
 * - TTFT 优化（首 token 延迟）：[preloadModel] / [warmupAfterLoad] / [preallocateKV] / [prebuildSampler] / [streamFirstTokenImmediately]
 * - TG 优化（生成速度）：[forceFlashAttention] / [aggressiveGraphOpt] / [continuousKVLayout] / [reuseSampler] / [zeroCopyStream] / [enableGraphCache]
 * - 能耗优化：[thermalAwareThrottle] / [idleUnloadTimeout] / [idleUnloadTimeoutMs] / [lowPowerOnBattery]
 *
 * @see OptimizationFlags 后端可消费的形式
 * @see OptimizationSettingsRepository DataStore 持久化
 */
data class OptimizationSettings(

    // === TTFT 优化（首 token 延迟） ===
    /** 启动时即 mmap 权重，而非首次推理时再加载。 */
    val preloadModel: Boolean = true,
    /** 加载后立即跑一次空输入预热，避免首次真实推理的编译 / 预热开销。 */
    val warmupAfterLoad: Boolean = true,
    /** 加载时按 contextLength 一次性分配 KV cache，避免逐 token 增长触发 realloc。 */
    val preallocateKV: Boolean = true,
    /** 加载时即构建采样器对象，避免首次推理时重建。 */
    val prebuildSampler: Boolean = true,
    /** 首 token 一就绪就 push，不等 N 个 token 攒批。 */
    val streamFirstTokenImmediately: Boolean = true,

    // === TG 优化（生成速度） ===
    /** 强制使用 Flash Attention；后端不支持则静默忽略。 */
    val forceFlashAttention: Boolean = true,
    /** 启用激进的算子融合 / 常量折叠（增加编译时间换推理速度）。 */
    val aggressiveGraphOpt: Boolean = true,
    /** 使用连续 KV 内存布局（vs 分块），提速但占内存更整块。 */
    val continuousKVLayout: Boolean = true,
    /** 同会话内复用 sampler 实例，避免每个 token 重建。 */
    val reuseSampler: Boolean = true,
    /** 流式输出走零拷贝路径（如 mmap 出来的 buffer 直接引用）。 */
    val zeroCopyStream: Boolean = true,
    /** 缓存编译后的计算图，加速同模型二次加载。 */
    val enableGraphCache: Boolean = true,

    // === 能耗优化 ===
    /** 热感知降频：温度过高时主动降低 batch / 线程数。 */
    val thermalAwareThrottle: Boolean = true,
    /** 是否启用"空闲超时卸载模型"特性（总开关）。 */
    val idleUnloadTimeout: Boolean = true,
    /** 空闲多久后自动卸载模型（毫秒）。仅当 [idleUnloadTimeout] = true 时生效。默认 10 分钟。 */
    val idleUnloadTimeoutMs: Long = 10 * 60 * 1000,
    /** 电池模式下进入低功耗档（降低线程数 / 关闭 GPU）；仅提示，不强制。 */
    val lowPowerOnBattery: Boolean = false
) {
    companion object {
        /** 默认档：当前主流手机上的最优折中。 */
        val DEFAULT = OptimizationSettings()

        /** 性能档：禁用热感知降频，跑满；适合短时间高强度推理。 */
        val PERFORMANCE = OptimizationSettings(
            thermalAwareThrottle = false  // 性能优先
        )

        /** 省电档：开热感知降频 + 电池模式 + 短 idle 卸载。 */
        val POWER_SAVE = OptimizationSettings(
            thermalAwareThrottle = true,
            idleUnloadTimeoutMs = 3 * 60 * 1000,
            lowPowerOnBattery = true
        )

        /** 预置 ID → [OptimizationSettings] 实例。 */
        fun presetById(id: String): OptimizationSettings? = when (id) {
            "default" -> DEFAULT
            "performance" -> PERFORMANCE
            "power_save" -> POWER_SAVE
            else -> null
        }

        /** 推断当前设置最接近的预置 ID（用于 UI 顶部下拉显示当前档位）。 */
        fun inferPresetId(s: OptimizationSettings): String = when (s) {
            DEFAULT -> "default"
            PERFORMANCE -> "performance"
            POWER_SAVE -> "power_save"
            else -> "custom"
        }
    }

    /**
     * 把用户设置转换为后端可消费的 [OptimizationFlags]。
     *
     * - 1:1 映射 TTFT / TG / 能耗通用字段。
     * - [idleUnloadTimeout] = false 时，[OptimizationFlags.idleUnloadTimeout] = 0（永不卸载）。
     * - [idleUnloadTimeout] = true 时，[OptimizationFlags.idleUnloadTimeout] = [idleUnloadTimeoutMs]。
     */
    fun toFlags(): OptimizationFlags = OptimizationFlags(
        // TTFT
        preloadModel = preloadModel,
        warmupAfterLoad = warmupAfterLoad,
        preallocateKV = preallocateKV,
        prebuildSampler = prebuildSampler,
        streamFirstTokenImmediately = streamFirstTokenImmediately,
        // TG
        forceFlashAttention = forceFlashAttention,
        aggressiveGraphOpt = aggressiveGraphOpt,
        continuousKVLayout = continuousKVLayout,
        reuseSampler = reuseSampler,
        zeroCopyStream = zeroCopyStream,
        enableGraphCache = enableGraphCache,
        // 能耗
        thermalAwareThrottle = thermalAwareThrottle,
        idleUnloadTimeout = if (idleUnloadTimeout) idleUnloadTimeoutMs else 0L,
        lowPowerOnBattery = lowPowerOnBattery
    )
}
