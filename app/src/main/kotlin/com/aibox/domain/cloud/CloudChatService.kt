package com.aibox.domain.cloud

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.aibox.data.model.MsgPart
import com.aibox.data.model.ToolHit
import com.aibox.data.repo.SettingsRepository
import com.aibox.domain.tool.DeviceInfo
import com.aibox.domain.tool.FunctionCall
import com.aibox.domain.tool.ToolContext
import com.aibox.domain.tool.ToolExecutor
import com.aibox.domain.tool.ToolRegistry
import com.aibox.domain.tool.UiInteractionBridge
import com.aibox.domain.tool.builtin.SearchResultItem
import com.aibox.infra.jni.NativeBridge
import com.aibox.ui.chat.encodeToolPartMarker
import com.aibox.util.log.LogSystem
import com.aibox.util.outcome.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 云端对话服务。
 *
 * 把「一次云端推理」抽象成和本地 [com.aibox.domain.inference.InferenceBackend] 一样的
 * `onToken / onStop` 流式接口，并在这里跑完整的 tool-calling 循环：
 *
 * 1. 把 [ToolRegistry] 里启用的工具 schema 一并发过去
 * 2. 模型返回 `tool_calls` → [ToolExecutor] 执行 → 结果以 `role=tool` 回填
 * 3. 继续下一轮，直到模型不再要工具（或达到 [MAX_TOOL_ROUNDS]）
 *
 * 这套循环就是后面本地模型工具调用的基础：本地模型只要产出同样的
 * `tool_calls` 结构，就能复用整个流程。
 */
class CloudChatService(
    private val settings: SettingsRepository,
    private val client: OpenAiCompatibleClient,
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    private val appContext: Context
) {
    @Volatile
    var enabled: Boolean = false
        private set

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) client.cancel()
        LogSystem.info(TAG, "cloud mode ${if (on) "enabled" else "disabled"}")
    }

    fun cancel() = client.cancel()

    /** 云端模式是否真的可以发请求（开关打开 + baseUrl/model 齐全）。 */
    suspend fun isUsable(): Boolean = enabled && settings.cloudConfig.first().usable

    /**
     * 执行一轮对话（含工具循环）。
     *
     * @param onToken 增量文本回调
     * @param onStop  结束回调；reason 与本地后端保持一致（"eos" / "cloud_error: ..."）
     */
    suspend fun chat(
        messages: List<NativeBridge.ChatMsg>,
        onToken: (String) -> Unit,
        onStop: (String) -> Unit
    ) {
        val config = settings.cloudConfig.first()
        if (!config.usable) {
            onStop("cloud_not_configured")
            return
        }
        val thinkingLevel = runCatching { settings.thinkingLevel.first() }.getOrDefault("medium")

        val tools = runCatching { toolRegistry.getActiveToolsForLLM() }
            .onFailure { LogSystem.warn(TAG, "读取工具列表失败: ${it.message}") }
            .getOrDefault(emptyList())
        // 交互式工具（user_choice）与需要确认的高级工具，必须能在前台弹面板。
        // domain 层拿不到 ViewModel，改从 UiInteractionBridge 取 UI 层注册的回调；
        // 未注册（后台执行）时保持默认失败，绝不挂起。
        val uiChoice = UiInteractionBridge.choiceHandler
        val uiConfirm = UiInteractionBridge.confirmHandler
            ?: { _: String, _: Map<String, Any> -> false }
        val context = ToolContext(
            sessionId = "cloud-${System.currentTimeMillis()}",
            deviceInfo = deviceInfo(),
            confirmCallback = uiConfirm,
            choiceCallback = uiChoice
        )

        // 附件：图片转 data URL（多模态），文本附件已在 Kotlin 侧拼进 content
        val history = withContext(Dispatchers.IO) {
            messages.map { CloudMsg(it.role, it.content, images = it.images.mapNotNull(::toDataUrl)) }
        }.toMutableList()
        applyThinkingHint(history, thinkingLevel)
        var rounds = 0

        while (rounds < MAX_TOOL_ROUNDS) {
            val turn = client.chat(config, history, tools, onToken, reasoningEffort = thinkingLevel)
                .getOrElse { e ->
                    onStop("cloud_error: ${e.message ?: e.javaClass.simpleName}")
                    return
                }

            if (turn.toolCalls.isEmpty()) {
                // finish_reason=length 表示输出（思考+正文）撞上上限被强制截断，
                // 常见表现是“只看到思考、正文没写完/没出来”。明确告知用户，
                // 而不是伪装成正常 eos——否则用户以为模型自己停了。
                if (turn.finishReason.equals("length", ignoreCase = true)) {
                    onToken("\n\n> ⚠️ 输出达到上限被截断（思考或正文过长）。可在设置中调高 max_tokens 或调低思考档位后重试。")
                    onStop("length_truncated")
                } else {
                    onStop("eos")
                }
                return
            }

            // 把 assistant 的 tool_calls 原样回填，否则下一轮 provider 会拒绝
            history.add(CloudMsg("assistant", turn.content, toolCalls = turn.toolCalls))

            for (call in turn.toolCalls) {
                LogSystem.info(TAG, "tool call: ${call.name} args=${call.argumentsJson.take(200)}")
                val tool = toolRegistry.getTool(call.name)
                val args = parseArguments(call.argumentsJson)
                val running = MsgPart.tool(
                    toolId = call.name,
                    toolName = tool?.displayName ?: call.name,
                    args = call.argumentsJson,
                    running = true
                )
                // 用结构化标记推给 UI：消息时间线里渲染成「图标 + 暗色文字」的工具行
                onToken("\n\n" + encodeToolPartMarker(running) + "\n\n")

                val outcome = toolExecutor.executeFunctionCall(FunctionCall(call.name, args), context)
                val output: String
                var success: Boolean
                var hits: List<ToolHit> = emptyList()
                when (outcome) {
                    is Outcome.Success -> {
                        success = outcome.value.success
                        output = outcome.value.output.ifBlank { outcome.value.error.orEmpty() }
                        hits = (outcome.value.structuredData as? List<*>)
                            ?.filterIsInstance<SearchResultItem>()
                            ?.map { ToolHit(it.title, it.url, it.snippet) }
                            ?: emptyList()
                    }
                    is Outcome.Failure -> {
                        success = false
                        output = "ERROR: ${outcome.reason}"
                    }
                }
                onToken(encodeToolPartMarker(running.copy(
                    running = false,
                    success = success,
                    // UI 只展示摘要；完整输出仍通过 history 回给模型
                    output = output.take(4000),
                    hits = hits
                )) + "\n\n")
                history.add(CloudMsg("tool", output, toolCallId = call.id))
            }
            rounds++
        }

        LogSystem.warn(TAG, "达到工具调用轮次上限 $MAX_TOOL_ROUNDS，强制结束")
        onStop("max_tool_rounds")
    }

    // ---------- 思考档位 & 多模态 ----------

    /**
     * 把思考强度写进 system message，作为跨 provider 通用的兜底
     * （reasoning_effort 只对部分模型下发）。
     */
    private fun applyThinkingHint(history: MutableList<CloudMsg>, level: String) {
        val hint = when (level) {
            "off" -> "请直接给出答案，不要展示推理过程。"
            "low" -> "请先做简短思考（1~3 句）再给出结论，保持简洁。"
            // medium 是默认档，绝不能留空——此前为空导致默认档既无参数（多数模型）
            // 也无提示词，用户感知就是“思考档位没作用”。
            "medium" -> "请先进行适度的分步思考，再给出清晰结论，兼顾充分与简洁。"
            "high" -> "请先进行深入、完整的推理，再给出清晰结论。"
            else -> null
        }
        if (hint == null) return
        val idx = history.indexOfFirst { it.role == "system" }
        if (idx >= 0) {
            history[idx] = history[idx].copy(
                content = history[idx].content.trimEnd() + "\n\n" + hint
            )
        } else {
            history.add(0, CloudMsg(role = "system", content = hint))
        }
    }

    /** 本地文件 / content URI → data URL；已是 data:/http(s) 直接返回。 */
    private fun toDataUrl(pathOrUri: String): String? {
        if (pathOrUri.isBlank()) return null
        if (pathOrUri.startsWith("data:") || pathOrUri.startsWith("http")) return pathOrUri
        val bytes = runCatching {
            val file = java.io.File(pathOrUri)
            if (file.exists()) {
                if (file.length() > MAX_IMAGE_BYTES) return null
                file.readBytes()
            } else {
                val uri = android.net.Uri.parse(pathOrUri)
                appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
        }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
        val mime = when {
            pathOrUri.endsWith(".png", true) -> "image/png"
            pathOrUri.endsWith(".webp", true) -> "image/webp"
            pathOrUri.endsWith(".gif", true) -> "image/gif"
            else -> "image/jpeg"
        }
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        return "data:$mime;base64,$b64"
    }

    // ---------- 参数 & 设备信息 ----------

    private fun parseArguments(json: String): Map<String, Any> {
        if (json.isBlank()) return emptyMap()
        val obj = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return emptyMap()
        return obj.mapValues { (_, v) -> toAny(v) }
    }

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

    @Suppress("DEPRECATION")
    private fun deviceInfo(): DeviceInfo {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = runCatching {
            val intent = appContext.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        }.getOrDefault(false)

        return DeviceInfo(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            androidVersion = Build.VERSION.RELEASE ?: "",
            sdkVersion = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.toList(),
            totalRamMb = mi.totalMem / (1024 * 1024),
            availableRamMb = mi.availMem / (1024 * 1024),
            batteryLevel = level,
            isCharging = charging
        )
    }

    private companion object {
        const val TAG = "aibox.cloud"
        const val MAX_TOOL_ROUNDS = 4
        const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
    }
}
