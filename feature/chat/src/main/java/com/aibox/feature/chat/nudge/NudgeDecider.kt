package com.aibox.feature.chat.nudge

import com.aibox.core.inference.HexagonVersion
import com.aibox.core.inference.InferenceResult
import kotlinx.coroutines.flow.Flow

/**
 * 性能引导决策器 — 决定"是否应该向用户提示安装加速插件"。
 *
 * 设计哲学（v2 改进）：
 *   旧方案只看硬件维度（"你有 NPU 没用"），误伤率高；
 *   新方案看【模型维度 + 硬件维度】双因子：
 *     - "你在跑小模型，速度却很慢" → 说明加速路径没走通 → 提示
 *     - "你在跑大模型，速度合理" → 不提示
 *     - "你在跑大模型，速度慢" → 不提示（本就该慢）
 *   核心判定：实际 tokens/s 显著低于该模型参数量级别的合理水平。
 *
 * 反屎山原则：
 * - 决策逻辑独立成纯 Kotlin 类，不依赖 Android Context（便于单元测试）
 * - 所有判定阈值集中在 [Thresholds]，禁止散落 magic number
 * - 用户偏好通过 [NudgePreferences] 接口抽象，不直接 import DataStore
 * - 推荐插件类型是 [RecommendedBackend.GPU] 或 [RecommendedBackend.NPU]，
 *   而不是绑死某个具体插件 ID（避免 NPU SDK 厂商切换时改代码）
 *
 * 设计参考：docs/PERFORMANCE_NUDGE.md "v2: 模型感知双因子方案"
 */
public class NudgeDecider(
    private val deviceInfo: DeviceInfo,
    private val installedPlugins: Set<String>,
    private val preferences: NudgePreferences,
) {

    /**
     * 推理完成后调用此方法判断是否该提示。
     *
     * @param result 当前推理结果
     * @param modelParams 模型参数量（亿为单位，如 7 表示 7B）。null 表示未知（按"中型模型"对待）
     */
    public suspend fun decideAfterInference(
        result: InferenceResult,
        modelParams: Long? = null,
    ): NudgeDecision {
        // ━━━ 1. 用户已永久禁用提示 ━━━
        if (preferences.accelerationNudgeDisabled()) return NudgeDecision.No

        // ━━━ 2. 当前已经在用 NPU（已经在加速了） ━━━
        if (result.npuUsed) return NudgeDecision.No

        // ━━━ 3. 冷却期内（避免连续推理都提示） ━━━
        val lastNudgeTime = preferences.lastAccelerationNudgeTime()
        val now = System.currentTimeMillis()
        if (now - lastNudgeTime < Thresholds.COOLDOWN_MS) return NudgeDecision.No

        // ━━━ 4. 必须有可测量的 tokens/s 数据 ━━━
        if (result.tokensPerSecond <= 0.0) return NudgeDecision.No

        // ━━━ 5. 模型参数量感知：判断"小模型 + 慢" ━━━
        val paramBuckets = ModelPerformanceBuckets.forParams(modelParams)
        if (paramBuckets == null) {
            // 模型太大（>13B）或未知：不提示，避免误伤
            return NudgeDecision.No
        }

        // ━━━ 6. 实际 tokens/s 显著低于合理水平 ━━━
        val actualTps = result.tokensPerSecond
        if (actualTps >= paramBuckets.cpuExpectedTps * Thresholds.UNDERPERFORM_RATIO) {
            // 速度还在合理范围，不提示
            return NudgeDecision.No
        }

        // ━━━ 7. 决定推荐什么类型：GPU 还是 NPU ━━━
        val recommended = pickRecommended()
        if (recommended == null) return NudgeDecision.No

        // ━━━ 8. 如果推荐 NPU，且对应插件已装，不提示 ━━━
        if (recommended is RecommendedBackend.NPU) {
            if (recommended.pluginId in installedPlugins) return NudgeDecision.No
        }

        // ━━━ 9. 生成可显示的提示 ━━━
        return NudgeDecision.Show(
            currentTokensPerSec = actualTps,
            expectedTokensPerSec = paramBuckets.acceleratedExpectedTps,
            modelParamsB = modelParams,
            modelCategory = paramBuckets.label,
            recommendedBackend = recommended,
            currentLatencyMs = result.latencyMillis,
        )
    }

    /**
     * 选择推荐哪种加速后端：
     * - 设备支持 Hexagon → 推荐 NPU（更高效）
     * - 不支持 → 推荐 GPU（vulkan delegate）
     * - 都不可用 → null（不提示）
     */
    private fun pickRecommended(): RecommendedBackend? {
        val hexagon = deviceInfo.hexagonVersion
        return if (hexagon != null && hexagon != HexagonVersion.UNKNOWN) {
            RecommendedBackend.NPU(
                pluginId = HEXAGON_PLUGIN_ID,
                vendorName = "Hexagon ${hexagon.name}",
                estimatedSpeedup = when (hexagon) {
                    HexagonVersion.V73 -> 4.0
                    HexagonVersion.V75 -> 6.0
                    HexagonVersion.V79 -> 8.0
                    HexagonVersion.V81 -> 10.0
                    HexagonVersion.UNKNOWN -> 3.0
                },
            )
        } else {
            // 非 Hexagon 设备 → 推荐 GPU（vulkan）
            if (deviceInfo.hasVulkanSupport) {
                RecommendedBackend.GPU(
                    pluginId = GPU_PLUGIN_ID,
                    estimatedSpeedup = 2.5,  // vulkan 通用 2-3x
                )
            } else {
                null
            }
        }
    }

    public companion object {
        /** Hexagon NPU 加速插件 ID */
        public const val HEXAGON_PLUGIN_ID: String = "com.aibox.plugins.npu_snpe"

        /** GPU 加速插件 ID（vulkan delegate 类插件，M3 阶段填充实际 ID） */
        public const val GPU_PLUGIN_ID: String = "com.aibox.plugins.gpu_vulkan"
    }
}

/** 设备信息 */
public data class DeviceInfo(
    public val hexagonVersion: HexagonVersion?,
    public val socModel: String,
    public val totalMemoryMb: Long,
    public val hasVulkanSupport: Boolean = true,  // 大多数 Android 10+ 设备都有
)

/** 用户偏好接口（实现位于 app 模块，基于 DataStore） */
public interface NudgePreferences {
    public suspend fun accelerationNudgeDisabled(): Boolean
    public suspend fun lastAccelerationNudgeTime(): Long
    public suspend fun markAccelerationNudgeShown(timestamp: Long)
    public suspend fun disableAccelerationNudge()
    public suspend fun reset()
    public val changes: Flow<Unit>
}

/** 推荐的后端类型 */
public sealed class RecommendedBackend {
    public abstract val pluginId: String
    public abstract val estimatedSpeedup: Double

    public data class NPU(
        override val pluginId: String,
        public val vendorName: String,
        override val estimatedSpeedup: Double,
    ) : RecommendedBackend()

    public data class GPU(
        override val pluginId: String,
        override val estimatedSpeedup: Double,
    ) : RecommendedBackend()
}

/** 决策结果 */
public sealed class NudgeDecision {
    public data object No : NudgeDecision()

    public data class Show(
        public val currentTokensPerSec: Double,
        public val expectedTokensPerSec: Double,
        public val modelParamsB: Long?,
        public val modelCategory: String,
        public val recommendedBackend: RecommendedBackend,
        public val currentLatencyMs: Long,
    ) : NudgeDecision() {

        /** 生成 Snackbar 显示文案（中文） */
        public fun snackbarTextCn(): String {
            val actual = "%.1f".format(currentTokensPerSec)
            val expected = "%.0f".format(expectedTokensPerSec)
            val category = modelCategory
            return "当前 $category 模型仅 $actual tokens/s。" +
                "下载配套的 GPU 或 NPU 后端模块，速度也许会更快（预计 ~$expected tok/s）。"
        }

        /** 生成 Snackbar 显示文案（英文） */
        public fun snackbarTextEn(): String {
            val actual = "%.1f".format(currentTokensPerSec)
            val expected = "%.0f".format(expectedTokensPerSec)
            return "Only $actual tok/s for $modelCategory model. " +
                "Download a GPU/NPU backend plugin — could reach ~$expected tok/s."
        }
    }
}

/**
 * 模型参数量级别 + 合理性能预期。
 *
 * 来源依据：CPU 推理的实测基线（骁龙 8 Gen 2 + llama.cpp + Q4_K 量化）：
 *   - 1.5B  : 30-50 tok/s
 *   - 3B    : 15-25 tok/s
 *   - 7B    : 5-10 tok/s
 *   - 13B   : 2-4 tok/s
 *   - 70B   : 0.3-0.8 tok/s（明显 CPU 不够用，NPU 也救不了多少）
 *
 * NPU/GPU 加速预期：通常是 CPU 的 3-10 倍，但与 Hexagon 版本相关。
 */
public enum class ModelPerformanceBuckets(
    public val label: String,
    public val minParamsB: Long,
    public val maxParamsB: Long,
    public val cpuExpectedTps: Double,
    public val acceleratedExpectedTps: Double,
) {
    TINY(label = "小型", minParamsB = 0L, maxParamsB = 1_000_000_000L, cpuExpectedTps = 30.0, acceleratedExpectedTps = 80.0),
    SMALL(label = "小型", minParamsB = 1_000_000_000L, maxParamsB = 3_000_000_000L, cpuExpectedTps = 15.0, acceleratedExpectedTps = 60.0),
    MEDIUM(label = "中型", minParamsB = 3_000_000_000L, maxParamsB = 7_000_000_000L, cpuExpectedTps = 7.0, acceleratedExpectedTps = 30.0),
    LARGE(label = "大型", minParamsB = 7_000_000_000L, maxParamsB = 13_000_000_000L, cpuExpectedTps = 2.5, acceleratedExpectedTps = 12.0),
    ;

    public companion object {
        /** 找出指定参数量所属的桶。null 表示太大（>13B）或太特殊 */
        public fun forParams(params: Long?): ModelPerformanceBuckets? {
            if (params == null) return null  // 未知模型保守不提示
            return entries.firstOrNull { params >= it.minParamsB && params < it.maxParamsB }
        }
    }
}

/**
 * 触发阈值集中定义（反 magic number）。
 */
public object Thresholds {
    /** 推理时间过短门槛（v2 不再强制要求，但保留用于过滤噪声） */
    public const val MIN_LATENCY_TO_NUDGE_MS: Long = 500L

    /** 推理时间过长门槛（v2 不再强制要求，但保留用于过滤异常长任务） */
    public const val MAX_LATENCY_TO_NUDGE_MS: Long = 120_000L

    /** "实际性能低于预期的多少倍"才触发提示。0.5 表示低于合理水平的 50% */
    public const val UNDERPERFORM_RATIO: Double = 0.5

    /** 同一提示冷却期 7 天 */
    public const val COOLDOWN_MS: Long = 7L * 24 * 60 * 60 * 1000
}
