package com.aibox.infra.module.manifest

/**
 * 轻量语义版本 + 版本范围解析（不引入第三方依赖）。
 *
 * 支持：
 * - 精确：`1.2.3`、`=1.2.3`
 * - 比较：`>=1.2.3`、`>1.2.3`、`<=1.2.3`、`<1.2.3`
 * - 插入符：`^1.2.3`（>=1.2.3 且 <2.0.0；0.x 时 <0.(minor+1).0）
 * - 波浪符：`~1.2.3`（>=1.2.3 且 <1.3.0）
 * - 通配：`1.2.*`、`1.*`、`*`
 * - 组合：用空格或逗号表示 AND，如 `>=1.0.0 <2.0.0`
 * - 或：用 `||` 分隔多个范围
 *
 * 目的：让 H/I 1.0/1.1、J/I 1.1 这类兼容关系能在 manifest 里精确表达，
 * 而不是只能写“最低版本”。
 */
data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    companion object {
        private val REGEX = Regex("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:[-+].*)?$")

        fun parse(raw: String): SemVer? {
            val s = raw.trim().removePrefix("v")
            val m = REGEX.matchEntire(s) ?: return null
            return SemVer(
                m.groupValues[1].toIntOrNull() ?: return null,
                m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0,
                m.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
            )
        }
    }
}

object VersionRanges {

    /** 版本是否满足范围表达式；空表达式视为任意版本。 */
    fun satisfies(version: String, expression: String?): Boolean {
        if (expression.isNullOrBlank()) return true
        val v = SemVer.parse(version) ?: return false
        // OR：任一分支满足即可
        return expression.split("||").any { branch ->
            val tokens = branch.trim()
                .split(',', ' ')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            tokens.isNotEmpty() && tokens.all { matchesSingle(v, it) }
        }
    }

    private fun matchesSingle(v: SemVer, token: String): Boolean {
        return when {
            token == "*" -> true

            token.startsWith("^") -> {
                val base = SemVer.parse(token.drop(1)) ?: return false
                v >= base && v < caretUpper(base)
            }

            token.startsWith("~") -> {
                val base = SemVer.parse(token.drop(1)) ?: return false
                v >= base && v < SemVer(base.major, base.minor + 1, 0)
            }

            token.startsWith(">=") -> SemVer.parse(token.drop(2))?.let { v >= it } ?: false
            token.startsWith("<=") -> SemVer.parse(token.drop(2))?.let { v <= it } ?: false
            token.startsWith(">")  -> SemVer.parse(token.drop(1))?.let { v > it } ?: false
            token.startsWith("<")  -> SemVer.parse(token.drop(1))?.let { v < it } ?: false
            token.startsWith("=")  -> SemVer.parse(token.drop(1))?.let { v == it } ?: false

            // 通配：1.2.* / 1.*
            token.contains('*') -> {
                val parts = token.split('.')
                when {
                    parts.size >= 3 && parts[2] == "*" -> {
                        val major = parts[0].toIntOrNull() ?: return false
                        val minor = parts[1].toIntOrNull() ?: return false
                        v.major == major && v.minor == minor
                    }
                    parts.size >= 2 && parts[1] == "*" -> {
                        v.major == (parts[0].toIntOrNull() ?: return false)
                    }
                    else -> false
                }
            }

            else -> SemVer.parse(token)?.let { v == it } ?: false
        }
    }

    private fun caretUpper(base: SemVer): SemVer = when {
        base.major > 0 -> SemVer(base.major + 1, 0, 0)
        base.minor > 0 -> SemVer(0, base.minor + 1, 0)
        else -> SemVer(0, 0, base.patch + 1)
    }
}
