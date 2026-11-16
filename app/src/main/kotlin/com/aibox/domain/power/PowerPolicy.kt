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
