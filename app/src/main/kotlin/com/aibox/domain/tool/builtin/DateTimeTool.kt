package com.aibox.domain.tool.builtin

import com.aibox.domain.tool.ParamSpec
import com.aibox.domain.tool.ParamType
import com.aibox.domain.tool.Tool
import com.aibox.domain.tool.ToolCategory
import com.aibox.domain.tool.ToolContext
import com.aibox.domain.tool.ToolParameterSchema
import com.aibox.domain.tool.ToolResult
import com.aibox.util.log.LogSystem
import com.aibox.util.outcome.Outcome
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * DateTimeTool（B2 基础工具）。
 *
 * 获取当前日期、时间或时间戳。支持时区与自定义格式化。
 *
 * - 工具 ID：`datetime`（与 [ToolSettingsRepository.defaultEnabledTools] 一致，默认启用）
 * - 分类：[ToolCategory.BASIC]，自动执行，无需用户确认
 * - 无副作用、无网络请求、纯本地计算
 *
 * 设计原则：
 * - 零异常约定：[execute] 内 try-catch 兜底，不抛出界
 * - 用 java.time API（API 26+；AIBox minSdk 26 可直接用，无需 desugar）
 * - 默认格式 `yyyy-MM-dd HH:mm:ss`；用户可传任意 DateTimeFormatter 模式
 * - 时区默认系统时区；用户可传 IANA 时区 ID（如 `Asia/Shanghai`、`UTC`）
 *
 * 输出：
 * - [ToolResult.output]：人类可读的多行文本，含格式化时间 + 时间戳（秒/毫秒）+ 时区
 * - [ToolResult.structuredData]：Map<String, Any>，含 `datetime` / `epochSeconds` / `epochMillis` / `timezone`
 *
 * 失败场景：
 * - 时区 ID 不合法（如 `Asia/Foo`）→ [Outcome.Failure]（DateTimeParseException / ZoneRulesException）
 * - 格式模式不合法（如 `yyyy-Foo`）→ [Outcome.Failure]（IllegalArgumentException）
 */
class DateTimeTool : Tool {
    override val id: String = "datetime"
    override val displayName: String = "日期与时间"
    override val description: String =
        "获取当前日期、时间或时间戳。支持时区。可格式化输出。"
    override val category: ToolCategory = ToolCategory.BASIC
    override val icon: String = "🕐"

    override fun getParameterSchema(): ToolParameterSchema = ToolParameterSchema(
        required = emptyList(),
        optional = listOf(
            ParamSpec(
                name = "format",
                type = ParamType.STRING,
                description = "时间格式,如 yyyy-MM-dd HH:mm:ss,默认 yyyy-MM-dd HH:mm:ss",
                default = "yyyy-MM-dd HH:mm:ss"
            ),
            ParamSpec(
                name = "timezone",
                type = ParamType.STRING,
                description = "时区 ID,如 Asia/Shanghai / UTC,默认系统时区"
            ),
            ParamSpec(
                name = "locale",
                type = ParamType.STRING,
                description = "语言标签,如 zh-CN / en-US,默认系统语言（影响星期/月份名称）"
            )
        )
    )

    override suspend fun execute(
        params: Map<String, Any>,
        context: ToolContext
    ): Outcome<ToolResult> {
        return try {
            val format = (params["format"] as? String)?.takeIf { it.isNotBlank() }
                ?: DEFAULT_FORMAT
            val timezone = (params["timezone"] as? String)?.takeIf { it.isNotBlank() }
            // locale 暂存字段，未来按 Locale 做星期/月份本地化时启用；当前不解析（避免误改 format 输出）
            // val locale = (params["locale"] as? String)?.takeIf { it.isNotBlank() }

            val now = LocalDateTime.now()
            val zonedNow = if (timezone != null) {
                now.atZone(ZoneId.of(timezone))
            } else {
                now.atZone(ZoneId.systemDefault())
            }

            val formatter = DateTimeFormatter.ofPattern(format)
            val formatted = zonedNow.format(formatter)

            val output = buildString {
                appendLine("当前时间: $formatted")
                appendLine("时间戳(秒): ${zonedNow.toEpochSecond()}")
                appendLine("时间戳(毫秒): ${zonedNow.toInstant().toEpochMilli()}")
                appendLine("时区: ${zonedNow.zone}")
            }

            val structuredData = mapOf<String, Any>(
                "datetime" to formatted,
                "epochSeconds" to zonedNow.toEpochSecond(),
                "epochMillis" to zonedNow.toInstant().toEpochMilli(),
                "timezone" to zonedNow.zone.toString()
            )

            LogSystem.debug(TAG, "DateTimeTool: format=$format, timezone=$timezone → $formatted")

            Outcome.Success(
                ToolResult(
                    success = true,
                    output = output,
                    structuredData = structuredData,
                    executionTimeMs = 1
                )
            )
        } catch (e: Throwable) {
            // 典型异常：ZoneRulesException（时区 ID 不合法）/ IllegalArgumentException（格式不合法）
            LogSystem.error(TAG, "DateTimeTool error: ${e.message}", e)
            Outcome.Failure("DateTimeTool error: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "aibox.tool.datetime"
        private const val DEFAULT_FORMAT = "yyyy-MM-dd HH:mm:ss"
    }
}
