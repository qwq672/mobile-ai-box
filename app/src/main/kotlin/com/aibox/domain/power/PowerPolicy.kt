package com.aibox.domain.power

import com.aibox.domain.optimization.OptimizationSettings
import com.aibox.infra.power.PowerStatus

/**
 * 电量档位。
 *
 * 注意：档位只影响“建议”与后台省电行为；**不会擅自改用户手动选的 HTP 档**。
 * 手动档在低电量时只会得到提示，由用户决定是否降档。
 */
enum class PowerMode(val label: String, val description: String) {
    NORMAL("正常", "按当前设置运行"),
    SAVER("省电", "电量较低：建议均衡档，减少长时间生成"),
    SURVIVAL("极低电量", "电量极低：建议省电档并缩短回答")
}

/** HTP 性能档 ID ↔ 中文标签。 */
object HtpPerfMode {
    const val AUTO = "auto"
    const val PERFORMANCE = "performance"
    const val BALANCED = "balanced"
    const val POWERSAVE = "powersave"

    val all = listOf(AUTO, PERFORMANCE, BALANCED, POWERSAVE)

    fun label(id: String): String = when (id) {
        AUTO -> "自动"
        PERFORMANCE -> "性能优先"
        BALANCED -> "均衡"
        POWERSAVE -> "省电"
        else -> id
    }

    /** 自动档在某个电量档下解析出的具体档位。 */
    fun resolveAuto(mode: PowerMode): String = when (mode) {
        PowerMode.NORMAL -> PERFORMANCE
        PowerMode.SAVER -> BALANCED
        PowerMode.SURVIVAL -> POWERSAVE
    }
}

/**
 * Hexagon 虚拟 NPU 会话模式。
 *
 * - off：不创建额外虚拟会话，ggml 用默认单会话（HTP0:0）。大模型会受单会话
 *   约 3.5GB 地址窗口限制，可能加载失败或回退 CPU。
 * - auto：按模型文件体积估算会话数（见 [resolveSessionCount]），适合绝大多数用户。
 * - manual：完全按用户设定的 [sessionCount] 创建虚拟会话。
 *
 * 会话数越多，能装下的模型越大，但跨会话通信与 DSP 队列开销也越高；手机通常
 * 只有一块物理 Hexagon，2-3 个虚拟会话对 7B-9B 已是甜点，不建议拉满 8。
 */
object HtpSessionMode {
    const val AUTO = "auto"
    const val MANUAL = "manual"
    const val OFF = "off"

    val all = listOf(AUTO, MANUAL, OFF)

    fun label(id: String): String = when (id) {
        AUTO -> "自动"
        MANUAL -> "手动"
        OFF -> "关闭"
        else -> id
    }

    /**
     * auto 模式：按模型文件体积推算虚拟会话数。
     *
     * 经验阈值（Hexagon 单会话约 3.5GB 窗口，Q4_0 权重体积近似模型大小）：
     * - <= 2.4GB：1 会话（3B 及以下，单会话装得下）
     * - 2.4-3.6GB：2 会话（4B 级，跨过窗口边界）
     * - 3.6-5.0GB：3 会话（7B-9B Q4_0）
     * - > 5.0GB：4 会话（更大模型，仍可能吃力）
     *
     * @param modelBytes 模型文件字节数
     * @param maxSessions 设备允许的会话数上限（默认 4，手机一般够用）
     */
    fun resolveSessionCount(modelBytes: Long, maxSessions: Int = 4): Int {
        val gb = modelBytes / (1024.0 * 1024.0 * 1024.0)
        val raw = when {
            gb <= 2.4 -> 1
            gb <= 3.6 -> 2
            gb <= 5.0 -> 3
            else -> 4
        }
        return raw.coerceIn(1, maxSessions.coerceIn(1, 8))
    }
}

/**
 * 电量策略：把「设置 + 当前电量」换算成可展示、可解释的决策。
 *
 * 关键约定：
 * - 充电中（无论电量）一律视为正常档，不提示
 * - 自动档会随电量变化；手动档只影响提示，不改变实际档位
 * - ≤5% 是“极低电量”硬阈值，与用户可调的低电量阈值相互独立
 */
object PowerPolicy {

    const val CRITICAL_LEVEL = 5

    fun resolve(settings: OptimizationSettings, status: PowerStatus): PowerMode {
        if (!settings.lowPowerMode) return PowerMode.NORMAL
        if (status.charging) return PowerMode.NORMAL
        if (status.levelKnown && status.level <= CRITICAL_LEVEL) return PowerMode.SURVIVAL
        val threshold = settings.lowPowerThreshold.coerceIn(5, 50)
        if (status.powerSave) return PowerMode.SAVER
        if (status.levelKnown && status.level <= threshold) return PowerMode.SAVER
        return PowerMode.NORMAL
    }

    /** 当前真正生效的 HTP 档（自动档会按电量档解析）。 */
    fun effectiveHtp(configured: String, mode: PowerMode): String =
        if (configured == HtpPerfMode.AUTO) HtpPerfMode.resolveAuto(mode) else configured

    /**
     * 是否需要提示用户降档。
     *
     * @return null 表示不需要提示（自动档 / 充电中 / 已经是均衡或省电）
     */
    fun warningLevel(configured: String, mode: PowerMode): Warning? {
        if (configured == HtpPerfMode.AUTO) return null
        if (mode == PowerMode.NORMAL) return null
        if (configured != HtpPerfMode.PERFORMANCE) return null
        return if (mode == PowerMode.SURVIVAL) Warning.CRITICAL else Warning.LOW
    }

    enum class Warning { LOW, CRITICAL }
}
