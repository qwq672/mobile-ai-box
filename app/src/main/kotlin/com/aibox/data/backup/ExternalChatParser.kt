package com.aibox.data.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 第三方导出对话解析器（ChatGPT / DeepSeek）。
 *
 * 两家官方导出的 conversations.json 都是「顶层数组 + 每个会话一棵 mapping 树」：
 * - ChatGPT：node.message.author.role + node.message.content.parts[] + create_time(浮点秒)
 * - DeepSeek：node.message.fragments[{type: REQUEST/RESPONSE, content}] + inserted_at(ISO)
 *
 * 分支处理：从 root 沿「子树最深」的主路径走，取用户实际继续的那条线，避免把被放弃的
 * 重生成分支混进对话。解析全程用 JsonElement 动态取值，容忍字段缺失/格式漂移。
 */
object ExternalChatParser {

    enum class Source(val label: String) {
        CHATGPT("ChatGPT"), DEEPSEEK("DeepSeek")
    }

    data class ParsedMessage(val role: String, val content: String, val ts: Long)
    data class ParsedConversation(val title: String, val messages: List<ParsedMessage>)

    data class Result(
        val conversations: List<ParsedConversation>,
        val warnings: List<String> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String, source: Source): Result {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrElse {
            return Result(emptyList(), listOf("JSON 解析失败：${it.message ?: "格式错误"}"))
        }
        val array = root as? JsonArray
            ?: return Result(emptyList(), listOf("顶层不是对话数组（应为 conversations.json）"))
        val warnings = mutableListOf<String>()
        val out = mutableListOf<ParsedConversation>()
        array.forEachIndexed { idx, elem ->
            val conv = elem as? JsonObject ?: run {
                warnings.add("第 ${idx + 1} 项不是对象，已跳过")
                return@forEachIndexed
            }
            val title = (conv["title"] as? JsonPrimitive)?.contentOrNull ?: "导入会话 ${idx + 1}"
            val mapping = conv["mapping"] as? JsonObject
            if (mapping == null) {
                warnings.add("会话「$title」缺少 mapping，已跳过")
                return@forEachIndexed
            }
            val msgs = when (source) {
                Source.CHATGPT -> parseChatGpt(mapping)
                Source.DEEPSEEK -> parseDeepSeek(mapping)
            }.filter { it.content.isNotBlank() && it.role in setOf("user", "assistant") }
            if (msgs.isEmpty()) {
                warnings.add("会话「$title」未提取到有效消息")
            } else {
                out += ParsedConversation(title, msgs)
            }
        }
        return Result(out, warnings)
    }

    // ---------- ChatGPT ----------
    private fun parseChatGpt(mapping: JsonObject): List<ParsedMessage> {
        val order = linearize(mapping) { node ->
            val message = node["message"] as? JsonObject ?: return@linearize null
            val author = (message["author"] as? JsonObject)?.get("role")?.str() ?: return@linearize null
            val parts = (message["content"] as? JsonObject)?.get("parts") as? JsonArray
            val text = parts?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.filter { it.isNotBlank() }
                ?.joinToString("\n")
                ?: return@linearize null
            val role = when (author) {
                "user" -> "user"
                "assistant" -> "assistant"
                "system" -> "user" // 系统提示并入用户侧，避免丢失信息
                else -> return@linearize null
            }
            ParsedMessage(role, text, (message["create_time"] as? JsonPrimitive)?.contentOrNull
                ?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0L)
        }
        return order
    }

    // ---------- DeepSeek ----------
    private fun parseDeepSeek(mapping: JsonObject): List<ParsedMessage> {
        // 一个 node 的 fragments 通常是同一角色的一条或数段（思考链 + 正文）。
        // 合并为一条消息返回，避免副作用收集导致 linearize 结果重复。
        return linearize(mapping) { node ->
            val message = node["message"] as? JsonObject ?: return@linearize null
            val frags = message["fragments"] as? JsonArray ?: return@linearize null
            val ts = (message["inserted_at"] as? JsonPrimitive)?.contentOrNull?.let { isoToMillis(it) } ?: 0L
            val texts = mutableListOf<String>()
            var role: String? = null
            frags.forEach { f ->
                val fo = f as? JsonObject ?: return@forEach
                val type = (fo["type"] as? JsonPrimitive)?.contentOrNull ?: ""
                val content = (fo["content"] as? JsonPrimitive)?.contentOrNull ?: ""
                if (content.isBlank()) return@forEach
                val r = if (type.equals("REQUEST", true)) "user" else "assistant"
                if (role == null) role = r
                texts += content
            }
            if (role == null || texts.isEmpty()) null
            else ParsedMessage(role!!, texts.joinToString("\n"), ts)
        }
    }

    /**
     * 从 root 沿最长子树取主路径，对每个节点调用 [extract]，按访问顺序返回提取结果。
     * 用记忆化递归算子树深度，避免 O(n²) 退化。
     */
    private fun linearize(
        mapping: JsonObject,
        extract: (JsonObject) -> ParsedMessage?,
    ): List<ParsedMessage> {
        val depthCache = HashMap<String, Int>()
        fun depth(id: String, visiting: Set<String> = emptySet()): Int {
            depthCache[id]?.let { return it }
            if (id in visiting) return 0 // 环保护
            val node = mapping[id] as? JsonObject ?: return 0
            val children = (node["children"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
            if (children.isEmpty()) return 1.also { depthCache[id] = it }
            val next = visiting + id
            val d = 1 + (children.maxOf { depth(it, next) })
            depthCache[id] = d
            return d
        }

        val result = mutableListOf<ParsedMessage>()
        var current = if (mapping["root"] is JsonObject) {
            "root"
        } else {
            // 某些导出没有 "root"，取第一个无父节点
            mapping.keys.firstOrNull { id ->
                val parent = (mapping[id] as? JsonObject)?.get("parent")
                parent == null || parent is JsonNull
            } ?: mapping.keys.firstOrNull() ?: return emptyList()
        }
        val seen = HashSet<String>()
        while (true) {
            if (!seen.add(current)) break
            val node = mapping[current] as? JsonObject ?: break
            extract(node)?.let { result += it }
            val children = (node["children"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
            if (children.isEmpty()) break
            current = children.maxBy { depth(it) }
        }
        return result
    }

    private fun isoToMillis(iso: String): Long = runCatching {
        java.time.Instant.parse(iso).toEpochMilli()
    }.getOrDefault(0L)

    private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.contentOrNull
}
