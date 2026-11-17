package com.aibox.domain.tool

/** user_choice 工具的选择模式。 */
enum class ChoiceMode(val id: String) {
    SINGLE("single"),
    MULTIPLE("multiple"),
    TEXT("text");

    companion object {
        fun from(id: String?): ChoiceMode =
            entries.firstOrNull { it.id == id?.trim()?.lowercase() } ?: SINGLE
    }
}

/**
 * 模型向用户发起的提问（user_choice 工具）。
 *
 * @param question 问题正文
 * @param mode 选择模式：单选 / 多选 / 纯文本回答
 * @param options 可选项（TEXT 模式为空）
 * @param allowExtra 是否允许在选择之外补充说明
 * @param extraHint 补充说明的提示文案
 */
data class UserChoiceRequest(
    val question: String,
    val mode: ChoiceMode = ChoiceMode.SINGLE,
    val options: List<String> = emptyList(),
    val allowExtra: Boolean = true,
    val extraHint: String = ""
)

/**
 * 用户对 [UserChoiceRequest] 的回答。
 *
 * @param selected 选中的选项（SINGLE 最多一个；TEXT 为空）
 * @param text 文本回答或补充说明（可空）
 */
data class UserChoiceAnswer(
    val selected: List<String> = emptyList(),
    val text: String = ""
) {
    /** 转成给模型看的自然语言结果。 */
    fun toToolOutput(): String = buildString {
        appendLine("用户回答：")
        if (selected.isNotEmpty()) {
            appendLine("选择：${selected.joinToString("；")}")
        }
        if (text.isNotBlank()) {
            appendLine("补充：${text.trim()}")
        }
        if (selected.isEmpty() && text.isBlank()) {
            appendLine("（用户未作答）")
        }
    }.trim()
}
