package com.aibox.backends.npu.snpe

import com.aibox.core.logger.AILogger
import com.aibox.core.inference.HexagonVersion

/**
 * Hexagon NPU 检测器 — 检测 SoC 型号并推断 Hexagon 版本。
 *
 * 优先适配：高通骁龙 SoC 上搭载的 Hexagon NPU。
 *  - V73  → 骁龙 8 Gen 2
 *  - V75  → 骁龙 8 Gen 3
 *  - V79  → 骁龙 X Elite
 *  - V81  → 骁龙 8 Gen 4 / 8 Elite
 *
 * 检测策略：
 *  1. 读 Build.HARDWARE + Build.BOARD
 *  2. 通过 ro.board.platform 系统属性精确获取 SoC 平台名（如 "kalama"）
 *  3. 把平台名映射到 Hexagon 版本
 */
public class HexagonDetector(
    private val logger: AILogger,
) {
    public fun detect(): HexagonDetectResult {
        val platform = readSystemProperty("ro.board.platform")
        val socModel = android.os.Build.HARDWARE ?: ""
        val version = mapToHexagonVersion(platform, socModel)
        return HexagonDetectResult(
            available = version != null,
            version = version,
            platform = platform,
            socModel = socModel,
        )
    }

    private fun readSystemProperty(name: String): String? = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val method = clazz.getMethod("get", String::class.java)
        method.invoke(null, name) as? String
    } catch (_: Throwable) {
        null
    }

    /** 平台代号 → Hexagon 版本映射（保持与高通官方文档同步） */
    private fun mapToHexagonVersion(platform: String?, socModel: String): HexagonVersion? {
        if (platform == null) return null
        return when (platform.lowercase()) {
            // V73: 骁龙 8 Gen 2
            "kalama", "u", "cape" -> HexagonVersion.V73
            // V75: 骁龙 8 Gen 3
            "pineapple", "pomelo", "pdx234" -> HexagonVersion.V75
            // V79: 骁龙 X Elite
            "x1" -> HexagonVersion.V79
            // V81: 骁龙 8 Gen 4 / 8 Elite
            "sun", "taro", "qrd8gen4" -> HexagonVersion.V81
            else -> null
        }
    }
}

public data class HexagonDetectResult(
    public val available: Boolean,
    public val version: HexagonVersion?,
    public val platform: String?,
    public val socModel: String,
)
