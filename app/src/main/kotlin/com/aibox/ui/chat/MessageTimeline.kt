package com.aibox.ui.chat

import android.util.Base64
import com.aibox.data.model.MsgPart
import com.aibox.domain.tool.ToolFunctionSchema
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 助手消息的时间线解析。
 *
 * 一条回复可能交错出现多段内容（这也是「先思考、再调工具、再回答」的基础）：
 * - `<!--think-->…<!--/think-->`：云端 reasoning_content（由客户端注入）
 * - `<think>…</think>` / `Thinking Process:`：本地模型的原生思考块
 * - `<!--tool:BASE64-->`：客户端 / 云端工具调用与结果（Base64 包 JSON，避免与正文冲突）
 * - ```aibox:tool 代码块 / `<tool_call>`：本地模型按提示词输出的工具调用
 *
 * 解析结果直接用于渲染，因此流式过程中反复调用也不会崩（未闭合的块按「进行中」处理）。
 */
fun parseTimeline(raw: String): List<MsgPart> {
    if (raw.isEmpty()) return emptyList()
    val out = mutableListOf<MsgPart>()
    val buf = StringBuilder()

    fun flushText() {
        val s = buf.toString().trim()
        if (s.isNotEmpty()) out.add(MsgPart.text(s))
        buf.setLength(0)
    }

    // 没有显式标记时，兼容本地模型开头的 "Thinking Process:" / " Thinking"
    val leading = raw.trimStart()
    if (!raw.contains("<!--think-->") && !raw.contains("<think>")) {
        if (leading.startsWith("Thinking Process:") || leading.startsWith(" Thinking")) {
            val isProcess = leading.startsWith("Thinking Process:")
            val prefixLen = if (isProcess) "Thinking Process:".length else " Thinking".length
            val start = raw.indexOf(leading) + prefixLen
            val found = findThinkEnd(raw, start)
            if (found != null) {
                val think = raw.substring(start, found.first).trim()
                val body = raw.substring(found.first + found.second).trim()
                return buildList {
                    if (think.isNotEmpty()) add(MsgPart.think(think))
                    if (body.isNotEmpty()) add(MsgPart.text(body))
                }
            }
            // "Thinking Process:" 却没有结束标记时，别把整篇正文当思考
            if (isProcess) return listOf(MsgPart.text(leading.removePrefix("Thinking Process:").trim()))
            return listOf(MsgPart.think(raw.substring(start).trim()))
        }
    }

    var i = 0
    while (i < raw.length) {
        when {
            raw.startsWith("<!--tool:", i) -> {
                val end = raw.indexOf("-->", i)
                if (end < 0) {
                    i = raw.length
                } else {
                    flushText()
                    decodeToolMarker(raw.substring(i + 8, end))?.let { addTool(out, it) }
                    i = end + 3
                }
            }

            raw.startsWith(CLOUD_THINK_OPEN, i) -> {
                flushText()
                val end = raw.indexOf(CLOUD_THINK_CLOSE, i + CLOUD_THINK_OPEN.length)
                val body = if (end < 0) raw.substring(i + CLOUD_THINK_OPEN.length)
                else raw.substring(i + CLOUD_THINK_OPEN.length, end)
                out.add(MsgPart.think(body.trim()))
                i = if (end < 0) raw.length else end + CLOUD_THINK_CLOSE.length
            }

            raw.startsWith(CLOUD_THINK_CLOSE, i) -> i += CLOUD_THINK_CLOSE.length

            raw.startsWith("<think>", i) -> {
                flushText()
                val found = findThinkEnd(raw, i + 7)
                val body = if (found == null) raw.substring(i + 7) else raw.substring(i + 7, found.first)
                out.add(MsgPart.think(body.trim()))
                i = if (found == null) raw.length else found.first + found.second
            }

            raw.startsWith("</think>", i) -> i += 8

            raw.startsWith("<tool_call>", i) -> {
                val end = raw.indexOf("</tool_call>", i)
                val body = if (end < 0) raw.substring(i + 11) else raw.substring(i + 11, end)
                flushText()
                decodeToolCall(body)?.let {
                    addTool(
                        out,
                        MsgPart.tool(
                            toolId = it.name,
                            toolName = it.name,
                            args = it.argumentsJson,
                            running = end < 0
                        )
                    )
                }
                i = if (end < 0) raw.length else end + 12
            }

            raw.startsWith(TOOL_FENCE, i) -> {
                val end = raw.indexOf("```", i + TOOL_FENCE.length)
                val body = if (end < 0) raw.substring(i + TOOL_FENCE.length) else raw.substring(i + TOOL_FENCE.length, end)
                flushText()
                decodeToolCall(body)?.let {
                    addTool(
                        out,
                        MsgPart.tool(
                            toolId = it.name,
                            toolName = it.name,
                            args = it.argumentsJson,
                            running = end < 0
                        )
                    )
                }
                i = if (end < 0) raw.length else end + 3
            }

            else -> {
                buf.append(raw[i])
                i++
            }
        }
    }
    flushText()
    return out
}

/** 从模型输出里取出第一个工具调用（本地模型文本协议）。没有则返回 null。 */
fun extractToolCall(raw: String): ToolCall? {
    val fence = raw.indexOf(TOOL_FENCE)
    if (fence >= 0) {
        val end = raw.indexOf("```", fence + TOOL_FENCE.length)
        val body = if (end < 0) raw.substring(fence + TOOL_FENCE.length) else raw.substring(fence + TOOL_FENCE.length, end)
        decodeToolCall(body)?.let { return it }
    }
    val tag = raw.indexOf("<tool_call>")
    if (tag >= 0) {
        val end = raw.indexOf("</tool_call>", tag)
        val body = if (end < 0) raw.substring(tag + 11) else raw.substring(tag + 11, end)
        decodeToolCall(body)?.let { return it }
    }
    return null
}

/** 工具调用（名称 + 参数）。 */
data class ToolCall(
    val name: String,
    val argumentsJson: String,
    val arguments: Map<String, Any>
)

/**
 * 把消息里的客户端标记去掉，用于回传给模型的对话历史。
 * 模型不需要看到 `<!--think-->` / `<!--tool:...-->` 这类内部标记。
 */
fun stripTimelineMarkers(content: String): String =
    content.replace(TIMELINE_MARKER_REGEX, "").trim()

/** 云端 / 工具结果共用的标记编码（Base64 避免正文里出现 `-->` 冲突）。 */
fun encodeToolPartMarker(part: MsgPart): String {
    val json = markerJson.encodeToString(part)
    val b64 = Base64.encodeToString(json.toByteArray(), Base64.NO_WRAP)
    return "<!--tool:$b64-->"
}

/**
 * 给本地模型注入工具说明（云端走原生 function calling，不需要这段）。
 *
 * 每个工具只占一行（名称 + 参数键 + 截断后的说明），不再内联完整 JSON Schema：
 * 小模型上下文有限，长 schema 会显著提高复读与胡编工具参数的概率。
 */
fun buildToolPrompt(tools: List<ToolFunctionSchema>): String {
    if (tools.isEmpty()) return ""
    val lines = tools.joinToString("\n") { t ->
        val params = parameterKeys(t.parameters)
        val desc = t.description.lineSequence().firstOrNull()?.trim()?.take(60).orEmpty()
        if (params.isBlank()) "- ${t.name}：$desc" else "- ${t.name}（参数：$params）：$desc"
    }
    return """可调用工具（按需使用，一次只调用一个）：
$lines

调用格式：单独输出一个代码块，内容必须是合法 JSON：
```aibox:tool
{"name":"工具名","arguments":{...}}
```
执行结果会以「工具执行结果」消息返回，不要编造结果。"""
}

/** 从 JSON Schema 里取参数名列表（只取键名，不展开类型 / 描述）。 */
private fun parameterKeys(schema: String): String = runCatching {
    markerJson.parseToJsonElement(schema).jsonObject["properties"]
        ?.jsonObject?.keys?.joinToString(", ")
        ?: ""
}.getOrDefault("")

// ---------------- 内部实现 ----------------

private const val CLOUD_THINK_OPEN = "<!--think-->"
private const val CLOUD_THINK_CLOSE = "<!--/think-->"
private const val TOOL_FENCE = "```aibox:tool"

private val TIMELINE_MARKER_REGEX =
    Regex("<!--/?think-->|<!--tool:[A-Za-z0-9+/=]+-->")

private val markerJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private fun findThinkEnd(src: String, from: Int): Pair<Int, Int>? {
    var best: Pair<Int, Int>? = null
    for (mark in THINK_CLOSE_MARKS) {
        val idx = src.indexOf(mark, from)
        if (idx >= 0 && (best == null || idx < best.first)) best = idx to mark.length
    }
    return best
}

private val THINK_CLOSE_MARKS = listOf(
    "</think>",
    "<｜end▁of▁thinking｜>",
    "<|end_of_thinking|>",
    "<|/think|>"
)

/** 连续出现「进行中 → 完成」的同名工具标记时合并成一条。 */
private fun addTool(out: MutableList<MsgPart>, part: MsgPart) {
    val last = out.lastOrNull()
    if (last != null && last.kind == MsgPart.KIND_TOOL &&
        last.toolId == part.toolId && last.running && !part.running
    ) {
        out[out.lastIndex] = part
        return
    }
    out.add(part)
}

private fun decodeToolMarker(b64: String): MsgPart? = runCatching {
    val json = String(Base64.decode(b64, Base64.NO_WRAP))
    markerJson.decodeFromString<MsgPart>(json)
}.getOrNull()

private fun decodeToolCall(body: String): ToolCall? {
    val text = body.trim().removePrefix("json").trim()
    if (text.isEmpty() || !text.startsWith("{")) return null
    val obj = runCatching { markerJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    val name = (obj["name"] ?: obj["tool"] ?: obj["function"])?.let { primitiveText(it) }
        ?.takeIf { it.isNotBlank() } ?: return null
    val argsEl = obj["arguments"] ?: obj["parameters"] ?: obj["params"]
    val argsObj = when (argsEl) {
        is JsonObject -> argsEl
        is JsonPrimitive -> runCatching {
            markerJson.parseToJsonElement(argsEl.content).jsonObject
        }.getOrNull()
        else -> null
    }
    return ToolCall(
        name = name,
        argumentsJson = argsObj?.toString() ?: "{}",
        arguments = argsObj?.mapValues { (_, v) -> toAny(v) } ?: emptyMap()
    )
}

private fun primitiveText(el: JsonElement): String? = (el as? JsonPrimitive)?.content

private fun toAny(el: JsonElement): Any = when (el) {
    is JsonObject -> el.mapValues { (_, v) -> toAny(v) }
    is JsonArray -> el.map { toAny(it) }
    is JsonPrimitive -> when {
        el.isString -> el.content
        el.booleanOrNull != null -> el.booleanOrNull!!
        el.doubleOrNull != null -> el.doubleOrNull!!
        else -> el.content
    }
}
