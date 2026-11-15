package com.aibox.data.model

import kotlinx.serialization.Serializable

/**
 * 一条助手消息内部的时间线段。
 *
 * 一条回复可以交错包含多个段：思考 → 工具调用 → 正文 → 再思考 → 正文……
 * 用 [kind] 区分类型，避免 sealed class 在 JSON 持久化时需要多态配置。
 *
 * - [KIND_TEXT]：正文（Markdown）
 * - [KIND_THINK]：思考过程（可折叠）
 * - [KIND_TOOL]：一次工具调用（含参数 / 输出 / 搜索结果）
 */
@Serializable
data class MsgPart(
    val kind: String,
    val text: String = "",
    val toolId: String = "",
    val toolName: String = "",
    val args: String = "",
    val running: Boolean = false,
    val success: Boolean = true,
    val output: String = "",
    val hits: List<ToolHit> = emptyList()
) {
    companion object {
        const val KIND_TEXT = "text"
        const val KIND_THINK = "think"
        const val KIND_TOOL = "tool"

        fun text(value: String) = MsgPart(KIND_TEXT, text = value)
        fun think(value: String) = MsgPart(KIND_THINK, text = value)
        fun tool(
            toolId: String,
            toolName: String,
            args: String,
            running: Boolean = false,
            success: Boolean = true,
            output: String = "",
            hits: List<ToolHit> = emptyList()
        ) = MsgPart(
            kind = KIND_TOOL,
            toolId = toolId,
            toolName = toolName.ifBlank { toolId },
            args = args,
            running = running,
            success = success,
            output = output,
            hits = hits
        )
    }
}

/** 工具返回的一条搜索结果（联网搜索 / URL 阅读等）。 */
@Serializable
data class ToolHit(
    val title: String,
    val url: String,
    val snippet: String = ""
)
