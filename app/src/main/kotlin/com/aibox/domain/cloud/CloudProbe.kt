package com.aibox.domain.cloud

import com.aibox.util.log.LogSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 连接测试的失败分类（UI 直接展示 [label]）。 */
enum class CloudProbeErrorKind(val label: String) {
    DNS("DNS 解析失败"),
    TLS("TLS 握手失败"),
    TIMEOUT("请求超时"),
    NETWORK("网络不可达"),
    AUTH("认证失败"),
    NOT_FOUND("接口或模型不存在"),
    RATE_LIMIT("触发限流"),
    SERVER("服务端错误"),
    BAD_RESPONSE("响应格式错误"),
    OTHER("其他错误")
}

/** 一次失败：分类 + HTTP 状态码（可能没有，如 DNS/超时）+ 截断到 300 字的说明。 */
data class CloudProbeError(
    val kind: CloudProbeErrorKind,
    val httpCode: Int? = null,
    val message: String = ""
) {
    /** 单行展示，如 `认证失败 (HTTP 401) · Invalid API key`。 */
    val display: String
        get() = buildString {
            append(kind.label)
            if (httpCode != null) append(" (HTTP ").append(httpCode).append(')')
            if (message.isNotBlank()) append(" · ").append(message)
        }
}

/**
 * 连接测试结果。
 *
 * [models] 来自 `GET /models`，失败时为空（不影响第二步）。
 * [latencyMs] 是 `POST /chat/completions` 的实测耗时。
 * [error] 是对话可用性测试的失败原因；[modelsError] 只是模型列表拉取失败，可单独展示。
 */
data class CloudProbeResult(
    val models: List<String> = emptyList(),
    val latencyMs: Long = 0,
    val chatOk: Boolean = false,
    /** 探测出的接口格式（目前只识别 OpenAI Chat Completions）。 */
    val detectedProtocol: CloudProtocol? = null,
    val error: CloudProbeError? = null,
    val modelsError: CloudProbeError? = null
) {
    val success: Boolean get() = chatOk && error == null

    /** UI 单行摘要：成功 `连接成功 · 320 ms · N 个模型 · 对话可用`，失败为分类 + 原因。 */
    val summary: String
        get() = if (success) {
            buildString {
                append("连接成功 · ").append(latencyMs).append(" ms · ")
                append(models.size).append(" 个模型 · 对话可用")
                if (modelsError != null) append("（模型列表不可用）")
            }
        } else {
            (error ?: modelsError)?.display ?: "连接失败"
        }
}

/** [CloudProbe.listModels] 的失败包装，便于调用方直接取分类结果。 */
class CloudProbeFailure(val error: CloudProbeError) : Exception(error.display)

/**
 * 云端模型连接测试。
 *
 * 两步：先 `GET /models` 拿模型清单（失败不终止），再 `POST /chat/completions`
 * 发一条 `max_tokens = 1` 的非流式 `ping`，用真实回包确认配置可用。
 * 网络 IO 全部在 IO 线程，OkHttp `Call` 随协程取消而取消。
 */
object CloudProbe {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    suspend fun probe(config: CloudConfig): CloudProbeResult = withContext(Dispatchers.IO) {
        val base = config.baseUrl.trim().trimEnd('/')
        if (base.isBlank()) {
            return@withContext CloudProbeResult(
                error = CloudProbeError(CloudProbeErrorKind.OTHER, null, "Base URL 不能为空")
            )
        }

        // ---------- 第 1 步：模型列表（失败只记录，不终止） ----------
        var models: List<String> = emptyList()
        var modelsError: CloudProbeError? = null
        try {
            models = fetchModels(base, config.apiKey)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val failure = classify(t)
            modelsError = failure
            LogSystem.warn(TAG, "probe /models failed: ${failure.display}")
        }

        // ---------- 第 2 步：非流式对话，测真实可用性 ----------
        if (config.model.isBlank()) {
            return@withContext CloudProbeResult(
                models = models,
                modelsError = modelsError,
                error = CloudProbeError(CloudProbeErrorKind.OTHER, null, "模型名不能为空")
            )
        }

        var latencyMs = 0L
        var chatOk = false
        var error: CloudProbeError? = null
        try {
            val body = buildJsonObject {
                put("model", config.model)
                put("stream", false)
                put("max_tokens", 1)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        put("content", "ping")
                    }
                }
            }.toString()
            val req = Request.Builder()
                .url("$base/chat/completions")
                .post(body.toRequestBody(JSON_MEDIA))
                .header("Accept", "application/json")
                .apply { if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}") }
                .build()

            val started = System.nanoTime()
            val response = client.newCall(req).await()
            try {
                val text = response.body?.string().orEmpty()
                latencyMs = (System.nanoTime() - started) / 1_000_000
                if (!response.isSuccessful) {
                    error = httpError(response.code, text)
                } else {
                    val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                    when {
                        root == null -> error = CloudProbeError(
                            CloudProbeErrorKind.BAD_RESPONSE,
                            response.code,
                            "返回内容不是 JSON 对象：${text.trim().take(120)}"
                        )
                        root["error"] != null -> error = CloudProbeError(
                            CloudProbeErrorKind.OTHER,
                            response.code,
                            providerMessage(text)
                        )
                        else -> chatOk = true
                    }
                }
            } finally {
                runCatching { response.close() }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val failure = classify(t)
            error = failure
            LogSystem.warn(TAG, "probe chat failed: ${failure.display}")
        }

        CloudProbeResult(
            models = models,
            latencyMs = latencyMs,
            chatOk = chatOk,
            detectedProtocol = if (chatOk) CloudProtocol.OPENAI_CHAT else null,
            error = error,
            modelsError = modelsError
        )
    }

    // ---------- 请求 ----------

    /** 只拉取模型列表（设置页「获取列表」按钮）；失败抛 [CloudProbeFailure]。 */
    suspend fun listModels(config: CloudConfig): List<String> = withContext(Dispatchers.IO) {
        val base = config.baseUrl.trim().trimEnd('/')
        if (base.isBlank()) {
            throw CloudProbeFailure(CloudProbeError(CloudProbeErrorKind.OTHER, null, "Base URL 不能为空"))
        }
        try {
            fetchModels(base, config.apiKey)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            throw CloudProbeFailure(classify(t))
        }
    }

    private suspend fun fetchModels(base: String, apiKey: String): List<String> {
        val req = Request.Builder()
            .url("$base/models")
            .get()
            .header("Accept", "application/json")
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .build()
        val response = client.newCall(req).await()
        val code = response.code
        val text = try {
            response.body?.string().orEmpty()
        } finally {
            runCatching { response.close() }
        }
        if (code !in 200..299) throw HttpStatusException(code, text)
        return parseModels(text)
    }

    private fun parseModels(text: String): List<String> {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull()
            ?: throw BadResponseException("模型列表不是合法 JSON：${text.trim().take(120)}")
        val array: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["data"] as? JsonArray)
                ?: (root["models"] as? JsonArray)
                ?: throw BadResponseException("模型列表缺少 data 字段")
            else -> throw BadResponseException("模型列表格式未知")
        }
        return array.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull
                ?: (obj["name"] as? JsonPrimitive)?.contentOrNull
            id?.takeIf { it.isNotBlank() }
        }.distinct().sorted()
    }

    // ---------- 错误归类 ----------

    private fun httpError(code: Int, body: String): CloudProbeError {
        val kind = when {
            code == 401 || code == 403 -> CloudProbeErrorKind.AUTH
            code == 404 -> CloudProbeErrorKind.NOT_FOUND
            code == 429 -> CloudProbeErrorKind.RATE_LIMIT
            code in 500..599 -> CloudProbeErrorKind.SERVER
            else -> CloudProbeErrorKind.OTHER
        }
        return CloudProbeError(kind, code, providerMessage(body))
    }

    private fun classify(t: Throwable): CloudProbeError = when (t) {
        is HttpStatusException -> httpError(t.code, t.body)
        is UnknownHostException -> err(CloudProbeErrorKind.DNS, t)
        is SSLException -> err(CloudProbeErrorKind.TLS, t)
        is CertificateException -> err(CloudProbeErrorKind.TLS, t)
        is SocketTimeoutException -> err(CloudProbeErrorKind.TIMEOUT, t)
        is ConnectException, is NoRouteToHostException -> err(CloudProbeErrorKind.NETWORK, t)
        is BadResponseException -> err(CloudProbeErrorKind.BAD_RESPONSE, t)
        is IOException -> err(CloudProbeErrorKind.NETWORK, t)
        else -> err(CloudProbeErrorKind.OTHER, t)
    }

    private fun err(kind: CloudProbeErrorKind, t: Throwable) =
        CloudProbeError(kind, null, (t.message ?: t.javaClass.simpleName).take(300))

    /** 从 OpenAI 风格的错误体里取 `error.message`，取不到就用原始 body。 */
    private fun providerMessage(body: String): String {
        val obj = runCatching { Json.parseToJsonElement(body.trim()).jsonObject }.getOrNull()
        val message = when (val err = obj?.get("error")) {
            is JsonObject -> (err["message"] as? JsonPrimitive)?.contentOrNull
            is JsonPrimitive -> err.contentOrNull
            else -> null
        } ?: (obj?.get("message") as? JsonPrimitive)?.contentOrNull
        return (message ?: body).trim().take(300)
    }

    /** OkHttp 回调转挂起函数，协程取消时同步取消请求。 */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else runCatching { response.close() }
            }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }

    private class HttpStatusException(val code: Int, val body: String) :
        IOException("HTTP $code")

    private class BadResponseException(message: String) : IOException(message)

    private const val TAG = "aibox.cloud.probe"
}
