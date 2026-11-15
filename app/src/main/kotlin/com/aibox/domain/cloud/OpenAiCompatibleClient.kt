package com.aibox.domain.cloud

import com.aibox.domain.tool.ToolFunctionSchema
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
        .readTimeout(180, TimeUnit.SECONDS)
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
        onToken: (String) -> Unit
    ): Result<CloudTurn> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.baseUrl.trimEnd('/') + "/chat/completions"
            val body = buildJsonObject {
                put("model", config.model)
                put("stream", true)
                put("temperature", config.temperature.toDouble())
                if (config.maxTokens > 0) put("max_tokens", config.maxTokens)
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
                    delta["content"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotEmpty() }
                        ?.let {
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
        put("content", m.content)
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

    private class ToolCallAccumulator {
        var id: String = ""
        var name: String = ""
        val args = StringBuilder()
    }

    private companion object {
        const val TAG = "aibox.cloud"
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
