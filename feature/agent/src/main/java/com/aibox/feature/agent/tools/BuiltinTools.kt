package com.aibox.feature.agent.tools

import com.aibox.core.common.Outcome
import com.aibox.core.tool.Tool
import com.aibox.core.tool.ToolParam
import com.aibox.core.tool.ToolParameterSchema
import com.aibox.core.tool.ToolResult
import com.aibox.core.tool.ToolSensitivity
import com.aibox.core.tool.ToolSource

/**
 * 内置工具集合 — AIBox 自带的工具，无需用户安装插件即可用。
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 内置 vs 高级工具划分原则（按用户决策）：
 *
 * 内置（本文件实现）：
 *   开源无版权问题 + 不涉及设备权限 + 不依赖外部 API
 *   - [DateTimeTool]        : 获取当前时间
 *   - [CalculatorTool]      : 数学计算
 *   - [DeviceInfoTool]      : 设备型号/SoC/内存查询
 *   - [JavaScriptTool]     : JS 沙箱执行（用 QuickJS）
 *   - [FileSystemTool]     : 限于 aibox/files 目录的文件操作
 *
 * 高级工具（插件 APK 分发）：
 *   涉及隐私权限或外部 API，用户主动安装授权
 *   - 邮件管理 / 短信 / 手机操控 / 网络搜索 / 日历
 *
 * 关键约束：
 *   - 内置工具不允许访问 aibox 目录外（避免越权）
 *   - 敏感操作（写文件、删除文件）必须 [ToolSensitivity.SENSITIVE]
 *   - 工具执行必须可被 [AgentTask.requireConfirmationForSensitive] 拦截
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 */
public object BuiltinTools {

    public fun all(): List<Tool> = listOf(
        DateTimeTool(),
        CalculatorTool(),
        DeviceInfoTool(),
        JavaScriptTool(),
        FileSystemTool(),
    )

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 1. 时间工具
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    public class DateTimeTool : Tool {
        override val id: String = "datetime"
        override val displayName: String = "获取当前时间"
        override val description: String = "返回当前设备时间，可指定格式"
        override val sensitivity: ToolSensitivity = ToolSensitivity.SAFE
        override val source: ToolSource = ToolSource.BuiltIn("feature/agent")
        override val parameterSchema: ToolParameterSchema = ToolParameterSchema(
            required = emptyList(),
            optional = listOf(
                ToolParam("format", ToolParam.ParamType.STRING, "时间格式，默认 yyyy-MM-dd HH:mm:ss"),
            ),
        )

        override suspend fun execute(args: Map<String, String>): Outcome<ToolResult> {
            val format = args["format"] ?: "yyyy-MM-dd HH:mm:ss"
            val now = java.text.SimpleDateFormat(format, java.util.Locale.getDefault())
                .format(java.util.Date())
            return Outcome.Success(ToolResult(output = now))
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 2. 计算器工具
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    public class CalculatorTool : Tool {
        override val id: String = "calculator"
        override val displayName: String = "数学计算"
        override val description: String = "解析并计算数学表达式，如 '1+2*3' 或 'sin(0.5)'"
        override val sensitivity: ToolSensitivity = ToolSensitivity.SAFE
        override val source: ToolSource = ToolSource.BuiltIn("feature/agent")
        override val parameterSchema: ToolParameterSchema = ToolParameterSchema(
            required = listOf(ToolParam("expression", ToolParam.ParamType.STRING, "数学表达式")),
        )

        override suspend fun execute(args: Map<String, String>): Outcome<ToolResult> {
            val expr = args["expression"] ?: return Outcome.Failure(
                com.aibox.core.common.AIBoxError.Generic("tool.calculator", "missing expression")
            )
            return try {
                // ⚠️ TODO M5: 用 QuickJS 或 javax.script 沙箱执行，禁止 eval
                // 骨架阶段：返回 stub
                Outcome.Success(ToolResult(output = "[stub] calculator not yet implemented for: $expr"))
            } catch (t: Throwable) {
                Outcome.Failure(com.aibox.core.common.AIBoxError.Generic("tool.calculator", "calc error", t))
            }
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 3. 设备信息工具
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    public class DeviceInfoTool : Tool {
        override val id: String = "device_info"
        override val displayName: String = "获取设备信息"
        override val description: String = "返回设备型号、SoC、内存、Android 版本"
        override val sensitivity: ToolSensitivity = ToolSensitivity.CONFIRM
        override val source: ToolSource = ToolSource.BuiltIn("feature/agent")
        override val parameterSchema: ToolParameterSchema = ToolParameterSchema(
            required = emptyList(),
            optional = listOf(ToolParam("include_battery", ToolParam.ParamType.BOOLEAN, "是否包含电量信息")),
        )

        override suspend fun execute(args: Map<String, String>): Outcome<ToolResult> {
            val info = buildString {
                append("型号: ").append(android.os.Build.MODEL).append('\n')
                append("品牌: ").append(android.os.Build.BRAND).append('\n')
                append("Android: ").append(android.os.Build.VERSION.RELEASE)
                    .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
                append("硬件: ").append(android.os.Build.HARDWARE).append('\n')
            }
            return Outcome.Success(ToolResult(output = info))
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 4. JavaScript 执行工具（沙箱）
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    public class JavaScriptTool : Tool {
        override val id: String = "javascript"
        override val displayName: String = "运行 JavaScript"
        override val description: String = "在沙箱中执行 JavaScript 代码（无网络访问、无文件访问）"
        override val sensitivity: ToolSensitivity = ToolSensitivity.CONFIRM
        override val source: ToolSource = ToolSource.BuiltIn("feature/agent")
        override val parameterSchema: ToolParameterSchema = ToolParameterSchema(
            required = listOf(ToolParam("code", ToolParam.ParamType.STRING, "JS 代码")),
        )

        override suspend fun execute(args: Map<String, String>): Outcome<ToolResult> {
            val code = args["code"] ?: return Outcome.Failure(
                com.aibox.core.common.AIBoxError.Generic("tool.js", "missing code")
            )
            // TODO M5: 集成 QuickJS 沙箱执行
            return Outcome.Success(ToolResult(output = "[stub] JS execution sandbox not yet implemented"))
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 5. 文件系统工具（限 aibox/files 目录）
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    public class FileSystemTool : Tool {
        override val id: String = "filesystem"
        override val displayName: String = "文件操作"
        override val description: String = "在 AIBox 专属目录内读写文件。敏感操作需确认。"
        override val sensitivity: ToolSensitivity = ToolSensitivity.SENSITIVE
        override val source: ToolSource = ToolSource.BuiltIn("feature/agent")
        override val parameterSchema: ToolParameterSchema = ToolParameterSchema(
            required = listOf(
                ToolParam("action", ToolParam.ParamType.STRING, "read/write/create/delete/list"),
                ToolParam("path", ToolParam.ParamType.STRING, "相对路径，限制在 aibox/files 内"),
            ),
            optional = listOf(
                ToolParam("content", ToolParam.ParamType.STRING, "写入内容（write/create 时必填）"),
            ),
        )

        override suspend fun execute(args: Map<String, String>): Outcome<ToolResult> {
            val action = args["action"] ?: return Outcome.Failure(
                com.aibox.core.common.AIBoxError.Generic("tool.fs", "missing action")
            )
            val path = args["path"] ?: return Outcome.Failure(
                com.aibox.core.common.AIBoxError.Generic("tool.fs", "missing path")
            )

            // ⚠️ 关键安全约束：禁止路径逃逸
            if (path.contains("..") || path.startsWith("/")) {
                return Outcome.Failure(
                    com.aibox.core.common.AIBoxError.Generic("tool.fs", "path traversal forbidden: $path")
                )
            }

            return Outcome.Success(ToolResult(output = "[stub] fs.$action($path) pending M5"))
        }
    }
}
