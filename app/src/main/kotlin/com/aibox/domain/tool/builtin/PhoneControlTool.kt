package com.aibox.domain.tool.builtin

import android.content.ClipData
import android.content.ClipboardManager
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
 * PhoneControlTool（C4 高级工具）。
 *
 * AI 操作手机的基础功能：打开 App、拨号、发短信、打开 URL、复制到剪贴板。
 *
 * - 工具 ID：`phone_control`（默认关闭，需用户在工具设置面板手动启用）
 * - 分类：[ToolCategory.ADVANCED]，每次执行前需用户确认（除非加入自动确认白名单）
 *
 * 实现策略：
 * - **不直接拨号 / 发短信**（避免 AI 静默拨打付费电话 / 群发短信风险）
 * - 所有"外呼"操作通过 Intent 启动系统 App 让用户最终确认
 *   - `dial`：[Intent.ACTION_DIAL] → 系统拨号界面预填号码（用户手动按"拨号"键）
 *   - `send_sms`：[Intent.ACTION_SENDTO] + `smsto:` → 系统短信界面预填号码 + 内容（用户手动按"发送"）
 * - `open_app` / `open_url` / `copy_clipboard` 即时生效（无副作用或低风险）
 *
 * 权限说明：
 * - **CALL_PHONE / SEND_SMS 权限不直接使用**：AIBox 仅启动 Intent，实际拨号 / 发短信由系统 App 完成
 * - AndroidManifest 声明这两个权限是为了在系统权限页向用户告知"此 App 可能发起电话 / 短信"
 *
 * 设计原则：
 * - 零异常约定：所有失败 → [Outcome.Failure]
 * - Intent.FLAG_ACTIVITY_NEW_TASK：从非 Activity Context 启动 Activity 必须加
 *
 * 输出：
 * - [ToolResult.output]：操作结果文本（如 "已打开拨号 10086"）
 * - [ToolResult.structuredData]：Map 含 action / target / result
 *
 * 失败场景：
 * - 缺 action / target → "Missing action or target"
 * - 未知 action → "Unknown action"
 * - App 未安装 → "App not found"
 * - 无对应系统 App（如无拨号 App）→ [Outcome.Failure]
 */
class PhoneControlTool(
    private val appContext: Context
) : Tool {
    override val id: String = "phone_control"
    override val displayName: String = "操作手机"
    override val description: String =
        "AI 操作手机基础功能:打开 App、拨号、发短信、打开 URL、复制到剪贴板。需要用户确认。"
    override val category: ToolCategory = ToolCategory.ADVANCED
    override val icon: String = "📱"

    override fun getParameterSchema(): ToolParameterSchema = ToolParameterSchema(
        required = listOf(
            ParamSpec(
                name = "action",
                type = ParamType.STRING,
                description = "操作类型",
                enum = listOf("open_app", "dial", "send_sms", "open_url", "copy_clipboard")
            ),
            ParamSpec(
                name = "target",
                type = ParamType.STRING,
                description = "目标(包名 / 电话号码 / URL / 文本)"
            )
        ),
        optional = listOf(
            ParamSpec(
                name = "message",
                type = ParamType.STRING,
                description = "短信内容(action=send_sms 时用)",
                default = ""
            )
        )
    )

    override suspend fun execute(
        params: Map<String, Any>,
        context: ToolContext
    ): Outcome<ToolResult> {
        val action = (params["action"] as? String)?.takeIf { it.isNotBlank() }
            ?: return Outcome.Failure("Missing action")
        val target = (params["target"] as? String)?.takeIf { it.isNotBlank() }
            ?: return Outcome.Failure("Missing target")

        return try {
            LogSystem.info(TAG, "PhoneControl: action=$action target=$target")
            val resultText: String = when (action) {
                "open_app" -> {
                    val intent = appContext.packageManager.getLaunchIntentForPackage(target)
                        ?: return Outcome.Failure("App not found: $target")
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(intent)
                    "已打开 App: $target"
                }
                "dial" -> {
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$target")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    appContext.startActivity(intent)
                    "已打开拨号界面: $target"
                }
                "send_sms" -> {
                    val message = (params["message"] as? String) ?: ""
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$target")).apply {
                        putExtra("sms_body", message)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    appContext.startActivity(intent)
                    "已打开短信界面: $target"
                }
                "open_url" -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    appContext.startActivity(intent)
                    "已打开 URL: $target"
                }
                "copy_clipboard" -> {
                    val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        ?: return Outcome.Failure("Cannot get ClipboardManager")
                    clipboard.setPrimaryClip(ClipData.newPlainText("AIBox", target))
                    "已复制到剪贴板"
                }
                else -> return Outcome.Failure("Unknown action: $action")
            }

            LogSystem.info(TAG, "PhoneControl done: $action → $resultText")
            Outcome.Success(
                ToolResult(
                    success = true,
                    output = resultText,
                    structuredData = mapOf<String, Any>(
                        "action" to action,
                        "target" to target,
                        "result" to resultText
                    ),
                    executionTimeMs = 0
                )
            )
        } catch (e: Throwable) {
            LogSystem.error(TAG, "PhoneControl error: ${e.message}", e)
            Outcome.Failure("PhoneControlTool error: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "aibox.tool.phone_control"
    }
}
