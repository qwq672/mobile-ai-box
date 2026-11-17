package com.aibox.domain.tool.builtin

import com.aibox.domain.tool.ChoiceMode
import com.aibox.domain.tool.ParamSpec
import com.aibox.domain.tool.ParamType
import com.aibox.domain.tool.Tool
import com.aibox.domain.tool.ToolCategory
import com.aibox.domain.tool.ToolContext
import com.aibox.domain.tool.ToolParameterSchema
import com.aibox.domain.tool.ToolResult
import com.aibox.domain.tool.UserChoiceRequest
import com.aibox.util.log.LogSystem
import com.aibox.util.outcome.Outcome

/**
 * 让用户做选择的工具（Agent 交互）。
 *
 * - 工具 ID：`user_choice`
 * - 分类：ADVANCED（用户需在设置里启用），但 [requiresConfirmation] = false：
 *   它本身就会弹出选择面板，不需要再叠一层"是否执行"的确认。
 *
 * 支持：
 * - 单选 / 多选 / 纯文本回答
 * - 选择后追加补充说明（allow_extra）
 *
 * 执行依赖前台 UI：`ToolContext.choiceCallback` 为 null 时返回失败，避免后台任务挂起。
 */
class UserChoiceTool : Tool {
    override val id: String = "user_choice"
    override val displayName: String = "询问用户"
    override val description: String =
        "当需要用户做决定或补充信息时调用。可提供单选/多选选项，也可要求文字回答。"
    override val category: ToolCategory = ToolCategory.ADVANCED
    override val requiresConfirmation: Boolean = false
    override val icon: String = "🙋"

    override fun getParameterSchema(): ToolParameterSchema = ToolParameterSchema(
        required = listOf(
            ParamSpec(
                name = "question",
                type = ParamType.STRING,
                description = "要问用户的问题，简短明确"
            )
        ),
        optional = listOf(
            ParamSpec(
                name = "mode",
                type = ParamType.STRING,
                description = "回答方式：single（单选）/ multiple（多选）/ text（文字回答）",
                enum = listOf("single", "multiple", "text"),
                default = "single"
            ),
            ParamSpec(
                name = "options",
                type = ParamType.ARRAY,
                description = "选项列表；mode=text 时留空。2~6 条，每条尽量短"
            ),
            ParamSpec(
                name = "allow_extra",
                type = ParamType.BOOLEAN,
                description = "是否允许用户在选择之外补充说明，默认 true",
                default = true
            ),
            ParamSpec(
                name = "extra_hint",
                type = ParamType.STRING,
                description = "补充说明输入框的提示文案"
            )
        )
    )

    override suspend fun execute(
        params: Map<String, Any>,
        context: ToolContext
    ): Outcome<ToolResult> {
        val question = (params["question"] as? String)?.trim().orEmpty()
        if (question.isBlank()) return Outcome.Failure("Missing question")

        val mode = ChoiceMode.from(params["mode"] as? String)
        val options = (params["options"] as? List<*>)
            ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            ?.distinct()
            ?.take(6)
            ?: emptyList()
        if (mode != ChoiceMode.TEXT && options.size < 2) {
            return Outcome.Failure("user_choice: 单选/多选至少需要 2 个选项")
        }

        val request = UserChoiceRequest(
            question = question,
            mode = mode,
            options = options,
            allowExtra = (params["allow_extra"] as? Boolean) ?: true,
            extraHint = (params["extra_hint"] as? String).orEmpty()
        )

        val callback = context.choiceCallback
        if (callback == null) {
            LogSystem.warn(TAG, "user_choice called without UI callback")
            return Outcome.Failure("当前没有可用的选择界面（需要在聊天前台调用）")
        }

        val answer = callback(request)
            ?: return Outcome.Failure("用户取消了回答")

        LogSystem.info(
            TAG,
            "user_choice answered: selected=${answer.selected.size}, text=${answer.text.isNotBlank()}"
        )
        return Outcome.Success(
            ToolResult(
                success = true,
                output = answer.toToolOutput(),
                structuredData = answer
            )
        )
    }

    companion object {
        private const val TAG = "aibox.tool.user_choice"
    }
}
