package com.aibox.feature.autotune

import kotlinx.coroutines.flow.Flow

/**
 * 自动调优器 — 类 TokForge 风格的本地推理性能自动调优。
 *
 * 反屎山原则：
 * - 接口纯 Kotlin，无 Android 依赖（便于单测）
 * - 调优项列表 [TuneKinds] 集中定义，禁止散落
 * - 三档粒度（低/中/高）由 [TuneIntensity] 控制，控制单次跑多少项组合
 * - 流式产出 [TuneProgress]，UI 实时展示进度和已得结果
 *
 * 设计参考：docs/NUDGE_AND_DIAGNOSTICS.md "自动调优设计"
 */
public interface AutoTuner {

    /**
     * 启动自动调优。
     *
     * @param config 调优配置
     * @return 实时进度流 + 最终推荐
     */
    public fun tune(config: TuneConfig): Flow<TuneProgress>

    /** 取消当前调优 */
    public fun cancel()
}

/**
 * 调优配置。
 *
 * @param intensity 调优粒度（低/中/高）
 *        低 = 3 项核心调整
 *        中 = 6 项（核心 + 边际）
 *        高 = 全部 9+ 项组合（最详尽）
 * @param targetMetric 优化目标：tps 优先 / 内存优先 / 平衡
 */
public data class TuneConfig(
    public val backendId: String,
    public val modelPath: String,
    public val intensity: TuneIntensity,
    public val targetMetric: TuneMetric = TuneMetric.BALANCED,
    public val testPrompt: String = "请用 200 字解释什么是机器学习。",
    public val maxTokens: Int = 256,
)

public enum class TuneIntensity {
    /** 低：3 项核心调整（KV / 核心数 / 后端） */
    LOW,

    /** 中：6 项（核心 + 批处理 + 上下文） */
    MEDIUM,

    /** 高：9+ 项全部（含 MTP / 多档量化对比等） */
    HIGH,
    ;

    /** 该粒度下要测的组合数量估算 */
    public val estimatedCombinations: Int get() = when (this) {
        LOW -> 3
        MEDIUM -> 6
        HIGH -> 12
    }
}

public enum class TuneMetric {
    /** 速度优先：选 tokens/s 最高的 */
    TPS_PRIORITY,

    /** 内存优先：选内存占用最低的 */
    MEMORY_PRIORITY,

    /** 平衡：综合打分（tps * 0.7 + 内存 * 0.3） */
    BALANCED,
}

/**
 * 调优进度。
 *
 * [TuneProgress.Running] 中的 [result] 是当前最佳组合（实时更新）。
 * [TuneProgress.Done] 是最终推荐。
 */
public sealed class TuneProgress {
    public data class Running(
        public val currentStep: Int,
        public val totalSteps: Int,
        public val currentKind: TuneKind,
        public val currentConfig: TuneVariant,
        public val currentTps: Double?,
        public val currentMemoryMb: Long?,
        public val bestSoFar: TuneResult?,
    ) : TuneProgress()

    public data class Done(
        public val recommendation: TuneResult,
        public val allResults: List<TuneResult>,
        public val durationMs: Long,
    ) : TuneProgress()

    public data class Failed(val message: String) : TuneProgress()
}

/**
 * 单个调优项类型（用户列出的所有调优维度）。
 *
 * 反屎山：所有调优维度集中定义，禁止新增维度时散落代码。
 */
public enum class TuneKind {
    /** KV cache 大小调整（影响长上下文性能和内存） */
    KV_CACHE,

    /** 批处理大小调整（影响吞吐量） */
    BATCH_SIZE,

    /** CPU 线程数调整（影响 CPU 后端速度） */
    CPU_THREADS,

    /** 推理后端切换（CPU / NNAPI / Hexagon） */
    BACKEND_SWITCH,

    /** 上下文长度调整（影响内存和长对话能力） */
    CONTEXT_LENGTH,

    /** 多档量化对比（Q4_K_M / Q5_K_M / Q8_0 等不同压缩档对比） */
    QUANTIZATION_COMPARE,

    /** MTP（Multi-Token Prediction）开关对比 */
    MTP_TOGGLE,

    /** GPU 层数调整（llama.cpp 的 gpu-layers 参数） */
    GPU_LAYERS,

    /** 内存分配策略（mmap / mlock / 全加载） */
    MEMORY_STRATEGY,
}

/** 单个调优组合的具体配置变体 */
public data class TuneVariant(
    public val kind: TuneKind,
    public val label: String,        // UI 展示用，如 "KV cache: 1024"
    public val config: TuneVariantConfig,
)

public data class TuneVariantConfig(
    public val kvCacheSize: Int? = null,
    public val batchSize: Int? = null,
    public val cpuThreads: Int? = null,
    public val backendId: String? = null,
    public val contextLength: Int? = null,
    public val quantization: String? = null,
    public val mtpEnabled: Boolean? = null,
    public val gpuLayers: Int? = null,
    public val memoryStrategy: String? = null,
)

/** 单次调优测试结果 */
public data class TuneResult(
    public val variant: TuneVariant,
    public val tps: Double,
    public val prefillTps: Double,
    public val memoryMb: Long,
    public val latencyMs: Long,
    public val score: Double,        // 按目标计算的得分
)

/**
 * 调优组合生成策略 — 决定每个 [TuneIntensity] 下要跑哪些组合。
 *
 * 反屎山：策略独立成类，便于测试和扩展。
 * 高级模式下会生成 N×M 组合（N 维度 × M 档位），可能跑 30+ 分钟，
 * 必须由用户主动触发并支持随时取消。
 */
public class TuneCombinationPlanner {

    /**
     * 根据粒度生成调优组合列表。
     */
    public fun plan(intensity: TuneIntensity, baseConfig: TuneConfig): List<TuneVariant> {
        val combos = mutableListOf<TuneVariant>()

        // 必跑：核心 3 项
        combos += kvVariants()
        combos += threadsVariants()
        combos += backendVariants()

        if (intensity == TuneIntensity.LOW) return combos

        // 中档：+3 项
        combos += batchVariants()
        combos += contextVariants()
        combos += gpuLayersVariants()

        if (intensity == TuneIntensity.MEDIUM) return combos

        // 高档：+3 项（最详尽）
        combos += quantizationVariants()
        combos += mtpVariants()
        combos += memoryStrategyVariants()

        return combos
    }

    private fun kvVariants(): List<TuneVariant> = listOf(
        TuneVariant(TuneKind.KV_CACHE, "KV: 512", TuneVariantConfig(kvCacheSize = 512)),
        TuneVariant(TuneKind.KV_CACHE, "KV: 1024", TuneVariantConfig(kvCacheSize = 1024)),
        TuneVariant(TuneKind.KV_CACHE, "KV: 2048", TuneVariantConfig(kvCacheSize = 2048)),
    )

    private fun threadsVariants(): List<TuneVariant> = listOf(
        TuneVariant(TuneKind.CPU_THREADS, "Threads: 2", TuneVariantConfig(cpuThreads = 2)),
        TuneVariant(TuneKind.CPU_THREADS, "Threads: 4", TuneVariantConfig(cpuThreads = 4)),
        TuneVariant(TuneKind.CPU_THREADS, "Threads: 8", TuneVariantConfig(cpuThreads = 8)),
    )

    private fun backendVariants(): List<TuneVariant> = listOf(
        TuneVariant(TuneKind.BACKEND_SWITCH, "Backend: CPU", TuneVariantConfig(backendId = "llama.cpp")),
        TuneVariant(TuneKind.BACKEND_SWITCH, "Backend: NNAPI", TuneVariantConfig(backendId = "npu-nnapi")),
        TuneVariant(TuneKind.BACKEND_SWITCH, "Backend: Hexagon", TuneVariantConfig(backendId = "npu-snpe")),
    )

    private fun batchVariants(): List<TuneVariant> = listOf(
        TuneVariant(TuneKind.BATCH_SIZE, "Batch: 1", TuneVariantConfig(batchSize = 1)),
        TuneVariant(TuneKind.BATCH_SIZE, "Batch: 8", TuneVariantConfig(batchSize = 8)),
        TuneVariant(TuneKind.BATCH_SIZE, "Batch: 32", TuneVariantConfig(batchSize = 32)),
    )

    private fun contextVariants(): List<TuneVariant> = listOf(
        TuneVariant(TuneKind.CONTEXT_LENGTH, "Ctx: 2048", TuneVariantConfig(contextLength = 2048)),
        TuneVariant(TuneKind.CONTEXT_LENGTH, "Ctx: 4096", TuneVariantConfig(contextLength = 4096)),
        TuneVariant(TuneKind.CONTEXT_LENGTH, "Ctx: 8192", TuneVariantConfig(contextLength = 8192)),
    )

    private fun gpuLayersVariants(): List<TuneVariant> = listOf(
        TuneVariant(TuneKind.GPU_LAYERS, "GPU: 0 (off)", TuneVariantConfig(gpuLayers = 0)),
        TuneVariant(TuneKind.GPU_LAYERS, "GPU: 10", TuneVariantConfig(gpuLayers = 10)),
        TuneVariant(TuneKind.GPU_LAYERS, "GPU: 99 (max)", TuneVariantConfig(gpuLayers = 99)),
    )

    private fun quantizationVariants(): List<TuneVariant> = listOf(
        TuneVariant(TuneKind.QUANTIZATION_COMPARE, "Q4_K_M", TuneVariantConfig(quantization = "Q4_K_M")),
        TuneVariant(TuneKind.QUANTIZATION_COMPARE, "Q5_K_M", TuneVariantConfig(quantization = "Q5_K_M")),
        TuneVariant(TuneKind.QUANTIZATION_COMPARE, "Q8_0", TuneVariantConfig(quantization = "Q8_0")),
        TuneVariant(TuneKind.QUANTIZATION_COMPARE, "F16", TuneVariantConfig(quantization = "F16")),
    )

    private fun mtpVariants(): List<TuneVariant> = listOf(
        TuneVariant(TuneKind.MTP_TOGGLE, "MTP: off", TuneVariantConfig(mtpEnabled = false)),
        TuneVariant(TuneKind.MTP_TOGGLE, "MTP: on", TuneVariantConfig(mtpEnabled = true)),
    )

    private fun memoryStrategyVariants(): List<TuneVariant> = listOf(
        TuneVariant(TuneKind.MEMORY_STRATEGY, "Mem: mmap", TuneVariantConfig(memoryStrategy = "mmap")),
        TuneVariant(TuneKind.MEMORY_STRATEGY, "Mem: mlock", TuneVariantConfig(memoryStrategy = "mlock")),
        TuneVariant(TuneKind.MEMORY_STRATEGY, "Mem: full-load", TuneVariantConfig(memoryStrategy = "full")),
    )
}
