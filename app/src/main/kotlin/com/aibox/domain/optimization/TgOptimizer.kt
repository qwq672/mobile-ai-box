package com.aibox.domain.optimization

import com.aibox.domain.inference.BackendManagerV2
import com.aibox.domain.inference.InferenceBackendV2
import com.aibox.domain.inference.OptimizationFlags
import com.aibox.util.log.LogSystem

/**
 * TG 优化器（P3）：通用代码层降低每 token 计算开销。
 *
 * 通用优化（所有模型一视同仁）：
 * - **Flash Attention 强制启用**（[OptimizationSettings.forceFlashAttention]）：后端不支持则静默忽略。
 * - **算子融合 / 常量折叠**（[OptimizationSettings.aggressiveGraphOpt]）：增加编译时间换推理速度。
 * - **KV cache 连续内存布局**（[OptimizationSettings.continuousKVLayout]）：提速但占内存更整块。
 * - **采样器复用**（[OptimizationSettings.reuseSampler]）：同会话内复用 sampler，避免每 token 重建。
 * - **流式输出 zero-copy**（[OptimizationSettings.zeroCopyStream]）：mmap 出来的 buffer 直接引用。
 * - **推理图缓存**（[OptimizationSettings.enableGraphCache]）：缓存编译后的计算图，加速同模型二次加载。
 *
 * 通用约定（P3 核心约束）：
 * - **不针对任何特定模型做特殊优化**：所有模型走相同 [applyBeforeLoad] → [InferenceBackendV2.applyOptimizations] 路径。
 * - 所有优化都有开关，用户可关闭单个；不依赖自动调优（不动 threads / KV 类型 / batch 选择）。
 *
 * 工作流：
 * 1. 调用方在 `backend.loadModel(path, config)` 之前调 [applyBeforeLoad] 取 flags。
 * 2. [applyBeforeLoad] 内部调 `backend.applyOptimizations(flags)` 把优化开关注入后端。
 * 3. 调用方再用 `config.copy(optimizations = flags)` 调 `backend.loadModel`，确保 flags 通过
 *    [com.aibox.domain.inference.InferenceConfigV2.optimizations] 也传一份给 loadModel（双通道，后端可任选其一消费）。
 *
 * @param backendManager 用于 [applyOptimizationsToAll] 在散热降频 / 预置切换时批量通知所有后端
 * @param settings 优化设置（snapshot）
 */
class TgOptimizer(
    private val backendManager: BackendManagerV2,
    private val settings: OptimizationSettings
) {
    private val tag = "aibox.opt.tg"

    /**
     * 在 `backend.loadModel` 之前调用：注入优化开关到后端，并返回 flags 供调用方放进 [com.aibox.domain.inference.InferenceConfigV2]。
     *
     * - 后端不支持的字段（如后端无 Flash Attention）应静默忽略，不报错（与 [OptimizationFlags] 设计一致）。
     * - 任何异常 try-catch，仅 LogSystem.warn，返回 flags（不让优化注入失败阻塞加载流程）。
     *
     * @return 当前设置对应的 [OptimizationFlags]
     */
    fun applyBeforeLoad(backend: InferenceBackendV2): OptimizationFlags {
        val flags = settings.toFlags()
        runCatching { backend.applyOptimizations(flags) }
            .onFailure { LogSystem.warn(tag, "backend.applyOptimizations threw: ${it.message}", it) }
        LogSystem.debug(tag, "applied flags to backend '${backend.getName()}': $flags")
        return flags
    }

    /**
     * 批量把当前设置应用到所有已注册后端（不重新加载模型）。
     *
     * 调用时机：
     * - 用户在设置页改了某个 TG 开关 → [OptimizationManager.onSettingsChanged] 调本方法通知所有后端。
     * - 散热降频：[EnergyOptimizer] 也可调本方法（传入调整后的 flags），让后端在下次推理前重读热状态。
     *
     * 不抛异常：所有后端的 applyOptimizations 失败仅记日志，不影响其他后端。
     */
    fun applyToAll() {
        val flags = settings.toFlags()
        LogSystem.info(tag, "applyToAll: broadcasting flags to all registered backends")
        backendManager.applyOptimizationsToAll(flags)
    }

    /**
     * 用指定的 flags 覆盖当前设置，并广播给所有后端。
     *
     * 供 [EnergyOptimizer] 在热档位变化时临时下调 flags 用（不持久化）。
     */
    fun applyToAllWith(flags: OptimizationFlags) {
        LogSystem.info(tag, "applyToAllWith: broadcasting overridden flags to all registered backends: $flags")
        backendManager.applyOptimizationsToAll(flags)
    }
}
