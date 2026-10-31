package com.aibox.core.tool

import com.aibox.core.common.Outcome
import kotlinx.serialization.Serializable

/**
 * 工具调用契约 — Agent 和 Chat 都能用，工具来源可内置可插件。
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 工具架构分层决策（按用户要求）：
 *
 *   内置工具（feature/agent 内部代码实现）：
 *     - [CalculatorTool]      : 数学计算（开源，无版权问题）
 *     - [DatetimeTool]        : 查询当前时间
 *     - [DeviceInfoTool]      : 获取设备型号/SoC/内存
 *     - [FileSystemTool]     : 文件操作（限 aibox/files，敏感操作前确认）
 *     - [JavaScriptTool]     : JS 沙箱执行（用 QuickJS）
 *
 *   高级工具（插件 APK 分发，按需安装授权）：
 *     - [EmailTool]           : 邮件管理（需邮箱授权）
 *     - [PhoneControlTool]    : 手机操控（需无障碍权限）
 *     - [WebSearchTool]      : 网络搜索（违背离线原则，默认不装）
 *     - [SmsTool]            : 发短信
 *     - [CalendarTool]       : 日历读写
 *
 *   所有工具统一通过 [ToolRegistry] 注册，Agent / Chat 不关心来源
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 */
public interface Tool {
    public val id: String
    public val displayName: String
    public val description: String
    public val parameterSchema: ToolParameterSchema
    public val sensitivity: ToolSensitivity
    public val source: ToolSource

    /**
     * 执行工具。
     *
     * 敏感工具（[ToolSensitivity.SENSITIVE]）：
     * - 执行前必须由 UI 弹出确认对话框（用户已要求）
     * - 用户拒绝时返回 [Outcome.Failure]
     * - 实现端不自己弹对话框，只标记 sensitivity，由 UI 层统一拦截
     */
    public suspend fun execute(args: Map<String, String>): Outcome<ToolResult>
}

/**
 * 工具敏感级别。
 *
 * - [SAFE]      : 直接执行（如获取时间、计算）
 * - [CONFIRM]   : 执行前 UI 弹确认（如读文件、运行 JS）
 * - [SENSITIVE] : 需要明确授权 + 二次确认（如写文件、发邮件、操控手机）
 */
@Serializable
public enum class ToolSensitivity {
    SAFE,
    CONFIRM,
    SENSITIVE,
}

/** 工具来源（用于审计日志） */
@Serializable
public sealed class ToolSource {
    public data class BuiltIn(val module: String) : ToolSource()       // 内置工具
    public data class Plugin(val packageName: String) : ToolSource()    // 插件工具
}

@Serializable
public data class ToolParameterSchema(
    public val required: List<ToolParam>,
    public val optional: List<ToolParam> = emptyList(),
)

@Serializable
public data class ToolParam(
    public val name: String,
    public val type: ParamType,
    public val description: String,
) {
    public enum class ParamType { STRING, INTEGER, FLOAT, BOOLEAN, OBJECT }
}

@Serializable
public data class ToolResult(
    public val output: String,
    public val isError: Boolean = false,
    public val metadata: Map<String, String> = emptyMap(),
)

/**
 * 工具注册表 — 所有工具（内置 + 插件）的唯一查询入口。
 */
public interface ToolRegistry {
    public fun list(): List<Tool>
    public fun find(id: String): Tool?
    public fun register(tool: Tool)
    public fun unregister(id: String)
    public fun listBySensitivity(level: ToolSensitivity): List<Tool>
    public fun listBySource(source: ToolSource): List<Tool>
}

/**
 * 工具执行请求 — UI 层包装用。
 *
 * [needsConfirmation] = true 时，UI 必须先弹确认对话框
 */
public data class ToolExecutionRequest(
    public val toolId: String,
    public val args: Map<String, String>,
    public val needsConfirmation: Boolean,
)
