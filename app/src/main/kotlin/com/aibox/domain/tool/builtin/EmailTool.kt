package com.aibox.domain.tool.builtin

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.aibox.domain.tool.ParamSpec
import com.aibox.domain.tool.ParamType
import com.aibox.domain.tool.Tool
import com.aibox.domain.tool.ToolCategory
import com.aibox.domain.tool.ToolContext
import com.aibox.domain.tool.ToolParameterSchema
import com.aibox.domain.tool.ToolResult
import com.aibox.util.log.LogSystem
import com.aibox.util.outcome.Outcome

/**
 * EmailTool（C2 高级工具）。
 *
 * 通过系统邮件 App 发送邮件。
 *
 * - 工具 ID：`email_send`（默认关闭，需用户在工具设置面板手动启用）
 * - 分类：[ToolCategory.ADVANCED]，每次执行前需用户确认（除非加入自动确认白名单）
 *
 * 实现策略（与 PhoneControlTool 一致）：
 * - **不直接 SMTP 发送**（避免后台静默发邮件被滥用风险）
 * - 用 [Intent.ACTION_SENDTO] + `mailto:` scheme 启动系统邮件 App
 *   → 邮件 App 显示预填的"收件人 + 主题 + 正文"
 *   → 用户在邮件 App 中点"发送"完成
 * - 若设备无邮件 App，startActivity 抛 [android.content.ActivityNotFoundException]
 *   → 转 [Outcome.Failure] 含提示
 *
 * 权限说明：
 * - **不需要 SEND_MAIL 权限**：发邮件由系统邮件 App 完成（与 PhoneControlTool 拨号同模式）
 * - AndroidManifest 不为此工具声明邮件权限
 *
 * 设计原则：
 * - 零异常约定：所有失败 → [Outcome.Failure]
 * - Intent.FLAG_ACTIVITY_NEW_TASK：从非 Activity Context 启动 Activity 必须加
 *
 * 输出：
 * - [ToolResult.output]：告知用户已打开邮件 App + 收件人 + 主题
 * - [ToolResult.structuredData]：Map 含 to / subject / body 长度
 *
 * 失败场景：
 * - 缺 to / subject / body → "Missing to" / "Missing subject" / "Missing body"
 * - 无邮件 App → [Outcome.Failure] 含提示
 * - 其它异常 → [Outcome.Failure]
 */
class EmailTool(
    private val appContext: Context
) : Tool {
    override val id: String = "email_send"
    override val displayName: String = "发送邮件"
    override val description: String =
        "通过系统邮件 App 发送邮件,需要用户在邮件 App 中确认发送。AIBox 不直接 SMTP 发送。"
    override val category: ToolCategory = ToolCategory.ADVANCED
    override val icon: String = "📧"

    override fun getParameterSchema(): ToolParameterSchema = ToolParameterSchema(
        required = listOf(
            ParamSpec(
                name = "to",
                type = ParamType.STRING,
                description = "收件人邮箱地址"
            ),
            ParamSpec(
                name = "subject",
                type = ParamType.STRING,
                description = "邮件主题"
            ),
            ParamSpec(
                name = "body",
                type = ParamType.STRING,
                description = "邮件正文"
            )
        ),
        optional = listOf(
            ParamSpec(
                name = "cc",
                type = ParamType.STRING,
                description = "抄送邮箱(多个用逗号分隔),可选"
            )
        )
    )

    override suspend fun execute(
        params: Map<String, Any>,
        context: ToolContext
    ): Outcome<ToolResult> {
        val to = (params["to"] as? String)?.takeIf { it.isNotBlank() }
            ?: return Outcome.Failure("Missing to")
        val subject = (params["subject"] as? String) ?: return Outcome.Failure("Missing subject")
        val body = (params["body"] as? String) ?: return Outcome.Failure("Missing body")
        val cc = params["cc"] as? String?

        return try {
            LogSystem.info(TAG, "Email: to=$to subject.len=${subject.length}")
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                if (!cc.isNullOrBlank()) {
                    putExtra(Intent.EXTRA_CC, cc.split(",").map { it.trim() }.toTypedArray())
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                appContext.startActivity(intent)
            } catch (e: android.content.ActivityNotFoundException) {
                return Outcome.Failure(
                    "未找到邮件 App,请先安装邮件客户端(Gmail / Outlook 等)"
                )
            }

            LogSystem.info(TAG, "Email intent launched: to=$to")
            Outcome.Success(
                ToolResult(
                    success = true,
                    output = buildString {
                        appendLine("已打开邮件 App,请确认发送:")
                        appendLine("收件人: $to")
                        if (!cc.isNullOrBlank()) appendLine("抄送: $cc")
                        appendLine("主题: $subject")
                        appendLine()
                        appendLine("(请在邮件 App 中点发送按钮完成发送)")
                    },
                    structuredData = mapOf<String, Any>(
                        "to" to to,
                        "subject" to subject,
                        "body_length" to body.length,
                        "cc" to (cc ?: "")
                    ),
                    executionTimeMs = 0
                )
            )
        } catch (e: Throwable) {
            LogSystem.error(TAG, "Email error: ${e.message}", e)
            Outcome.Failure("EmailTool error: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "aibox.tool.email_send"
    }
}
