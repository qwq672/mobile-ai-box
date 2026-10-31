package com.aibox.core.perf

import kotlinx.serialization.Serializable

/**
 * 完整性能配置 — 集中所有 14+ 项性能参数。
 *
 * 反屎山核心：
 *   本文件是性能参数的"单一真相源"。
 *   所有 BackendConfig / ChatSession / 推理请求必须最终映射到本类，
 *   禁止任何模块定义"自己的"性能参数。
 *
 * UI 分层（用户视角）：
 *   - 普通用户：只看到 [PerformanceProfile] 三档预设（极速/平衡/节能）
 *   - 高级用户：展开看 [advanced] 各项细节
 *
 * 后端兼容（自动隐藏不支持的项）：
 *   - 调用 [applicableFor] 让 UI 知道哪些字段对当前后端有意义
 *   - 例：NPU 后端不会显示 [kvCacheSize] 和 [cpuThreads]
 */
@Serializable
public data class PerformanceConfig(
    /** 预设档位（普通用户用），与 [advanced] 互斥影响 */
    public val profile: PerformanceProfile = PerformanceProfile.BALANCED,

    /** 高级模式具体参数（[profile] = CUSTOM 时生效） */
    public val advanced: AdvancedPerformanceConfig = AdvancedPerformanceConfig(),
) {
    /** 当前是否处于"自定义"模式 */
    public val isCustom: Boolean get() = profile == PerformanceProfile.CUSTOM

    /**
     * 返回当前配置下"对指定后端适用"的字段列表。
     * UI 用此信息决定显示哪些设置项。
     */
    public fun applicableFor(backendId: String): Set<PerformanceField> {
        return PerformanceField.applicableFor(backendId)
    }
}

/**
 * 三档预设 + 自定义。
 *
 * - [SPEED]   : 极速（牺牲能耗，最大并行度）
 * - [BALANCED]: 平衡（默认，适合大多数场景）
 * - [POWER]   : 节能（降频，长续航）
 * - [CUSTOM]  : 自定义（用户手动调）
 */
@Serializable
public enum class PerformanceProfile {
    SPEED,
    BALANCED,
    POWER,
    CUSTOM,
    ;

    public companion object {
        /** 预设档位对应的 [AdvancedPerformanceConfig] */
        public fun presetConfig(profile: PerformanceProfile): AdvancedPerformanceConfig = when (profile) {
            SPEED -> AdvancedPerformanceConfig(
                cpuThreads = 8,
                batchSize = 32,
                physicalBatchSize = 32,
                contextLength = 4096,
                memoryCompression = MemoryCompression.Q4_K_M,
                contextCompression = ContextCompression.NONE,
                gpuLayers = 99,
                powerMode = PowerMode.PERFORMANCE,
                speedLimitTps = 0.0,
                thermalMode = ThermalMode.AGGRESSIVE,
                mtpEnabled = true,
                flashAttention = true,
            )
            BALANCED -> AdvancedPerformanceConfig(
                cpuThreads = 4,
                batchSize = 8,
                physicalBatchSize = 8,
                contextLength = 4096,
                memoryCompression = MemoryCompression.Q4_K_M,
                contextCompression = ContextCompression.NONE,
                gpuLayers = 0,
                powerMode = PowerMode.BALANCED,
                speedLimitTps = 0.0,
                thermalMode = ThermalMode.ADAPTIVE,
                mtpEnabled = false,
                flashAttention = true,
            )
            POWER -> AdvancedPerformanceConfig(
                cpuThreads = 2,
                batchSize = 1,
                physicalBatchSize = 1,
                contextLength = 2048,
                memoryCompression = MemoryCompression.Q4_K_S,
                contextCompression = ContextCompression.SLIDING_WINDOW,
                gpuLayers = 0,
                powerMode = PowerMode.EFFICIENCY,
                speedLimitTps = 30.0,
                thermalMode = ThermalMode.STRICT,
                mtpEnabled = false,
                flashAttention = false,
            )
            CUSTOM -> AdvancedPerformanceConfig()
        }
    }
}

/**
 * 高级性能参数（用户细调用）。
 *
 * 所有字段都是可空或带默认值，便于部分设置。
 * 字段类型集中在文件顶部，禁止在其他地方重复定义同类枚举。
 */
@Serializable
public data class AdvancedPerformanceConfig(
    /** Flash Attention 开关（llama.cpp 专属，部分模型格式不支持） */
    public val flashAttention: Boolean = true,

    /** KV cache 大小（影响长上下文性能和内存），null = 后端默认 */
    public val kvCacheSize: Int? = null,

    /** CPU 线程数（影响 CPU 后端速度） */
    public val cpuThreads: Int = 4,

    /** 批处理大小（逻辑层，影响吞吐量） */
    public val batchSize: Int = 8,

    /** 物理批处理大小（硬件实际处理的 batch，可能小于逻辑 batch） */
    public val physicalBatchSize: Int = 8,

    /** 上下文长度（影响长对话能力 vs 内存） */
    public val contextLength: Int = 4096,

    /** 内存压缩档位（GGUF 量化档位选择） */
    public val memoryCompression: MemoryCompression = MemoryCompression.Q4_K_M,

    /** 上下文压缩策略（长对话时怎么裁剪） */
    public val contextCompression: ContextCompression = ContextCompression.NONE,

    /** GPU/NPU 层数（llama.cpp 的 gpu-layers，0=纯CPU，99=全GPU） */
    public val gpuLayers: Int = 0,

    /** 功耗模式（系统 power profile） */
    public val powerMode: PowerMode = PowerMode.BALANCED,

    /** 速度限制（tokens/s 上限，0=不限） */
    public val speedLimitTps: Double = 0.0,

    /** 发热控制策略 */
    public val thermalMode: ThermalMode = ThermalMode.ADAPTIVE,

    /** MTP (Multi-Token Prediction / Speculative Decoding) 开关 */
    public val mtpEnabled: Boolean = false,

    /** 视觉模型预热（图像识别专用，提前加载 vision tower） */
    public val visionPrewarm: Boolean = false,
)

/** 内存压缩档位（GGUF 量化） */
@Serializable
public enum class MemoryCompression {
    F16, Q8_0, Q6_K, Q5_K_M, Q5_K_S, Q4_K_M, Q4_K_S, Q4_0, Q3_K_M, Q2_K
}

/** 上下文压缩策略 */
@Serializable
public enum class ContextCompression {
    /** 不压缩，长对话超限直接报错 */
    NONE,

    /** 滑动窗口：只保留最近 N 轮 */
    SLIDING_WINDOW,

    /** 摘要压缩：每 N 轮摘要 + 保留最新 N 轮 */
    SUMMARY_COMPRESSION,

    /** KV cache 量化压缩（保留全部上下文，但 KV 量化降低精度） */
    KV_QUANTIZATION,
}

/** 功耗模式（绑定 Android PowerManager） */
@Serializable
public enum class PowerMode {
    /** 性能模式（绑定 PERFORMANCE 模式） */
    PERFORMANCE,

    /** 平衡（默认） */
    BALANCED,

    /** 节能（绑定 POWER_SAVE，降频） */
    EFFICIENCY,

    /** 极限节能（强制降频 + 限制并发） */
    ULTRA_EFFICIENCY,
}

/** 发热控制策略 */
@Serializable
public enum class ThermalMode {
    /** 不控制，依赖系统默认散热 */
    NONE,

    /** 自适应：检测到设备发热（>40℃）自动暂停推理 */
    ADAPTIVE,

    /** 激进：允许高发热（适合短时极速任务） */
    AGGRESSIVE,

    /** 严格：发热超过 38℃ 立即降频 */
    STRICT,
}

/**
 * 性能字段标识 — UI 用此判断哪些项对当前后端有意义。
 */
public enum class PerformanceField {
    FLASH_ATTENTION,
    KV_CACHE,
    CPU_THREADS,
    BACKEND_SELECT,
    BATCH_SIZE,
    PHYSICAL_BATCH_SIZE,
    CONTEXT_LENGTH,
    MEMORY_COMPRESSION,
    CONTEXT_COMPRESSION,
    GPU_LAYERS,
    NPU_LAYERS,
    POWER_MODE,
    SPEED_LIMIT,
    THERMAL_MODE,
    MTP,
    VISION_PREWARM,
    ;

    public companion object {
        /** 不同后端适用的性能字段集合 */
        public fun applicableFor(backendId: String): Set<PerformanceField> {
            return when {
                backendId.startsWith("npu-") -> NPU_APPLICABLE
                backendId == "llama.cpp" -> LLAMACPP_APPLICABLE
                backendId == "mnn" -> MNN_APPLICABLE
                backendId == "onnx" -> ONNX_APPLICABLE
                backendId == "tflite" -> TFLITE_APPLICABLE
                else -> GENERIC_APPLICABLE
            }
        }

        // NPU 后端：KV cache 和 CPU 线程不可调
        private val NPU_APPLICABLE: Set<PerformanceField> = setOf(
            BACKEND_SELECT, BATCH_SIZE, CONTEXT_LENGTH, MEMORY_COMPRESSION,
            CONTEXT_COMPRESSION, NPU_LAYERS, POWER_MODE, SPEED_LIMIT, THERMAL_MODE,
        )

        // llama.cpp：基本所有项都适用
        private val LLAMACPP_APPLICABLE: Set<PerformanceField> = setOf(
            FLASH_ATTENTION, KV_CACHE, CPU_THREADS, BACKEND_SELECT, BATCH_SIZE,
            PHYSICAL_BATCH_SIZE, CONTEXT_LENGTH, MEMORY_COMPRESSION, CONTEXT_COMPRESSION,
            GPU_LAYERS, POWER_MODE, SPEED_LIMIT, THERMAL_MODE, MTP, VISION_PREWARM,
        )

        private val MNN_APPLICABLE: Set<PerformanceField> = setOf(
            CPU_THREADS, BACKEND_SELECT, BATCH_SIZE, CONTEXT_LENGTH, MEMORY_COMPRESSION,
            POWER_MODE, SPEED_LIMIT, THERMAL_MODE,
        )

        private val ONNX_APPLICABLE: Set<PerformanceField> = setOf(
            CPU_THREADS, BACKEND_SELECT, BATCH_SIZE, PHYSICAL_BATCH_SIZE, CONTEXT_LENGTH,
            POWER_MODE, SPEED_LIMIT, THERMAL_MODE,
        )

        private val TFLITE_APPLICABLE: Set<PerformanceField> = setOf(
            CPU_THREADS, BACKEND_SELECT, BATCH_SIZE, CONTEXT_LENGTH, POWER_MODE,
            SPEED_LIMIT, THERMAL_MODE,
        )

        /** 通用兜底 */
        private val GENERIC_APPLICABLE: Set<PerformanceField> = setOf(
            BACKEND_SELECT, CONTEXT_LENGTH, POWER_MODE, SPEED_LIMIT, THERMAL_MODE,
        )
    }
}
