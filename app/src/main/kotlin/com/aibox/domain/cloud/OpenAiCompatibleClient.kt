package com.aibox.domain.cloud

import com.aibox.domain.tool.ToolFunctionSchema
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容协议的流式客户端。
 *
 * 只做三件事：拼请求 JSON、解析 SSE、把 tool_calls 增量拼回完整调用。
 * 工具循环、取消、设备上下文在 [CloudChatService] 里。
 */
class OpenAiCompatibleClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // 深度思考模型（R1/o 系列）在吐正文前可能长时间只出 reasoning_content，
        // 甚至中间有较长静默。180s 读到超时会把“正在思考”误判为网络中断，
        // 表现为“思考到一半、正文还没出就被截止”。提高到 300s 覆盖绝大多数长思考。
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var activeCall: Call? = null

    /** 取消当前请求（用户点「停止」或切换模型时）。 */
    fun cancel() {
        runCatching { activeCall?.cancel() }
        activeCall = null
    }

    /**
     * 发一轮流式请求。
     *
     * @param onToken 每个增量文本回调
     * @return 本轮汇总（正文 + 工具调用 + finish_reason）
     */
    suspend fun chat(
        config: CloudConfig,
        messages: List<CloudMsg>,
        tools: List<ToolFunctionSchema>,
        onToken: (String) -> Unit,
        reasoningEffort: String? = null
    ): Result<CloudTurn> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.baseUrl.trimEnd('/') + "/chat/completions"
            val body = buildJsonObject {
                put("model", config.model)
                put("stream", true)
                put("temperature", config.temperature.toDouble())
                // 推理模型 / 开启思考的模型：思考链本身消耗大量输出 token，若沿用默认 2048
                // 常在“只思考、还没回答”阶段就撞上 max_tokens（finish_reason=length），正文没机会产出。
                // 判定条件不能再只看模型名——deepseek-chat 等模型开启 thinking 后同样会先长思考，
                // 因此“思考开着”本身就要求抬高上限。非思考场景沿用用户配置。
                val thinkingOn = reasoningEffort?.let { it.lowercase() != "off" } == true
                val effectiveMaxTokens = if (isReasoningModel(config.model) || thinkingOn) {
                    maxOf(config.maxTokens, REASONING_MAX_TOKENS)
                } else config.maxTokens
                if (effectiveMaxTokens > 0) put("max_tokens", effectiveMaxTokens)
                reasoningEffort?.lowercase()?.let { applyThinkingParams(config.model, it) }
                putJsonArray("messages") {
                    messages.forEach { m -> add(msgToJson(m)) }
                }
                if (tools.isNotEmpty()) {
                    putJsonArray("tools") {
                        tools.forEach { t ->
                            addJsonObject {
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", t.name)
                                    put("description", t.description)
                                    // 参数 schema 本身就是 JSON 字符串，这里必须作为对象嵌入
                                    put("parameters", Json.parseToJsonElement(t.parameters))
                                }
                            }
                        }
                    }
                    put("tool_choice", "auto")
                }
            }

            val reqBuilder = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .header("Accept", "text/event-stream")
            if (config.apiKey.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer ${config.apiKey}")
            }
            val call = client.newCall(reqBuilder.build())
            activeCall = call

            LogSystem.info(TAG, "cloud request: url=$url model=${config.model} msgs=${messages.size} tools=${tools.size}")
            val response = call.execute()
            try {
                if (!response.isSuccessful) {
                    val err = response.body?.string().orEmpty().take(500)
                    LogSystem.error(TAG, "cloud http ${response.code}: $err")
                    error("HTTP ${response.code}: $err")
                }
                val source = response.body?.source() ?: error("empty response body")

                val content = StringBuilder()
                val reasoning = StringBuilder()
                var reasoningOpen = false
                val toolCalls = linkedMapOf<Int, ToolCallAccumulator>()
                var finishReason = "stop"

                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty()) continue
                    if (payload == "[DONE]") break

                    val root = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue
                    // 部分 provider 会把错误塞在 data 里（对象形态）
                    (root["error"] as? kotlinx.serialization.json.JsonObject)
                        ?.get("message")?.jsonPrimitive?.contentOrNull?.let {
                            error("provider error: $it")
                        }
                    val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                    choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { finishReason = it }

                    val delta = choice["delta"]?.jsonObject ?: continue
                    // 思考内容：DeepSeek / vLLM / Ollama 等用 reasoning_content，部分网关用 reasoning
                    val reasoningDelta = delta["reasoning_content"]?.jsonPrimitive?.contentOrNull
                        ?: delta["reasoning"]?.jsonPrimitive?.contentOrNull
                    if (!reasoningDelta.isNullOrEmpty()) {
                        if (!reasoningOpen) {
                            reasoningOpen = true
                            onToken("<!--think-->")
                        }
                        reasoning.append(reasoningDelta)
                        onToken(reasoningDelta)
                    }
                    delta["content"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotEmpty() }
                        ?.let {
                            if (reasoningOpen) {
                                reasoningOpen = false
                                onToken("<!--/think-->\n\n")
                            }
                            content.append(it)
                            onToken(it)
                        }

                    delta["tool_calls"]?.jsonArray?.forEach { el ->
                        val tc = el.jsonObject
                        val index = tc["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                        val acc = toolCalls.getOrPut(index) { ToolCallAccumulator() }
                        tc["id"]?.jsonPrimitive?.contentOrNull?.let { acc.id = it }
                        val fn = tc["function"]?.jsonObject
                        fn?.get("name")?.jsonPrimitive?.contentOrNull?.let { acc.name = it }
                        fn?.get("arguments")?.jsonPrimitive?.contentOrNull?.let { acc.args.append(it) }
                    }
                }

                if (reasoningOpen) {
                    reasoningOpen = false
                    onToken("<!--/think-->")
                }

                val calls = toolCalls.toSortedMap().values
                    .filter { it.name.isNotBlank() }
                    .map { CloudToolCall(it.id.ifBlank { "call_${it.name}" }, it.name, it.args.toString()) }

                LogSystem.info(
                    TAG,
                    "cloud turn done: content=${content.length} chars, toolCalls=${calls.map { it.name }}, finish=$finishReason"
                )
                CloudTurn(content.toString(), calls, finishReason)
            } finally {
                runCatching { response.close() }
                activeCall = null
            }
        }.onFailure {
            val msg = it.message.orEmpty()
            if (msg.contains("Canceled", ignoreCase = true) || msg.contains("Socket closed", ignoreCase = true)) {
                LogSystem.info(TAG, "cloud request cancelled")
            } else {
                LogSystem.error(TAG, "cloud request failed: $msg", it)
            }
        }
    }

    private fun msgToJson(m: CloudMsg) = buildJsonObject {
        put("role", m.role)
        if (m.images.isEmpty()) {
            put("content", m.content)
        } else {
            // 多模态：content 为 parts 数组，文本 + 图片 data URL
            putJsonArray("content") {
                if (m.content.isNotBlank()) {
                    addJsonObject {
                        put("type", "text")
                        put("text", m.content)
                    }
                }
                m.images.forEach { dataUrl ->
                    addJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") { put("url", dataUrl) }
                    }
                }
            }
        }
        if (m.toolCallId != null) put("tool_call_id", m.toolCallId)
        if (m.toolCalls.isNotEmpty()) {
            putJsonArray("tool_calls") {
                m.toolCalls.forEach { c ->
                    addJsonObject {
                        put("id", c.id)
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", c.name)
                            put("arguments", c.argumentsJson)
                        }
                    }
                }
            }
        }
    }

    /**
     * 按供应商下发思考开关（不同家的字段完全不同）：
     * - DeepSeek：`thinking.type` 开关 + `reasoning_effort`（只认 low/high/max）
     * - 通义千问 / DashScope：`enable_thinking`
     * - OpenAI o 系列 / gpt-5：`reasoning_effort`
     * 其他模型不下发，避免第三方网关因未知字段报 400；此时只剩 system 提示词兜底。
     */
    private fun JsonObjectBuilder.applyThinkingParams(model: String, level: String) {
        val m = model.lowercase()
        val on = level != "off"
        when {
            m.contains("deepseek") -> {
                putJsonObject("thinking") { put("type", if (on) "enabled" else "disabled") }
                if (on) {
                    // DeepSeek 官方映射：medium → high；high 档给到 max 才有明显差别
                    put("reasoning_effort", when (level) {
                        "low" -> "low"
                        "high" -> "max"
                        else -> "high"
                    })
                }
                LogSystem.info(TAG, "thinking(deepseek): level=$level enabled=$on")
            }
            m.contains("qwen") || m.contains("qvq") || m.contains("qwq") -> {
                put("enable_thinking", on)
                // Qwen3 系列支持 thinking_budget 控制思考深度；低/中/高映射到递增预算。
                // 仅对含 qwen3 的模型下发，避免老模型因未知字段报 400。
                if (on && (m.contains("qwen3") || m.contains("qwq"))) {
                    put("thinking_budget", when (level) {
                        "low" -> 1024
                        "high" -> 8192
                        else -> 4096 // medium
                    })
                }
                LogSystem.info(TAG, "thinking(qwen): level=$level enabled=$on")
            }
            m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4") ||
                m.contains("gpt-5") -> {
                put("reasoning_effort", if (on) level else "minimal")
                LogSystem.info(TAG, "thinking(openai): level=$level enabled=$on")
            }
            else -> LogSystem.info(TAG, "thinking: model=$model 无已知开关，仅用提示词")
        }
    }

    /**
     * 是否为“会先输出长思考链再回答”的推理模型。
     * 这类模型必须放宽 max_tokens，否则思考阶段就把额度用尽，正文出不来。
     */
    private fun isReasoningModel(model: String): Boolean {
        val m = model.lowercase()
        return m.contains("reasoner") || m.contains("r1") ||
            m.contains("qwq") || m.contains("qvq") ||
            m.contains("thinking") || m.contains("-think") ||
            m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4") ||
            m.contains("gpt-5")
    }

    private class ToolCallAccumulator {
        var id: String = ""
        var name: String = ""
        val args = StringBuilder()
    }

    private companion object {
        const val TAG = "aibox.cloud"
        /** 推理模型思考 + 正文的最低输出额度。 */
        const val REASONING_MAX_TOKENS = 8192
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
