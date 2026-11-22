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
import com.aibox.domain.tool.ChoiceMode
import com.aibox.domain.tool.DeviceInfo
import com.aibox.domain.tool.FunctionCall
import com.aibox.domain.tool.ToolContext
import com.aibox.domain.tool.ToolExecutor
import com.aibox.domain.tool.ToolRegistry
import com.aibox.domain.tool.UiInteractionBridge
import com.aibox.domain.tool.UserChoiceRequest
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
     * 根据首条用户消息生成一句短标题（AI 自动命名会话）。
     *
     * 只发一次非流式请求，不进工具循环、不写任何会话/标题字段——是否采用、
     * 是否覆盖由调用方（ChatViewModel）决定，这样天然不会动到用户已命名的标题。
     * 失败返回 null，调用方降级为默认标题即可。
     */
    suspend fun generateTitle(firstUserText: String): String? {
        if (!isUsable()) return null
        val clean = firstUserText.trim().take(200)
        if (clean.isBlank()) return null
        val messages = listOf(
            CloudMsg(
                role = "system",
                content = TITLE_SYSTEM_PROMPT
            ),
            CloudMsg(role = "user", content = "给这段对话起标题：\n$clean")
        )
        val raw = client.chatOnce(settings.cloudConfig.first(), messages, maxTokens = 48)
            .getOrNull() ?: return null
        return sanitizeTitle(raw)
    }

    /** 把模型原始输出清洗成一句干净短标题；失败返回 null 让调用方降级。 */
    private fun sanitizeTitle(raw: String): String? {
        var s = raw.trim()
        // 推理型模型会先吐 <think>…</think> 思考过程，整段丢掉
        s = s.replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("<[a-z_]+>[\\s\\S]*?</[a-z_]+>", RegexOption.IGNORE_CASE), "")
        // 取第一行有效文本（标题就该是一行）
        s = s.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: return null
        // 去掉常见前缀：「标题：」「标题:」「Title:」以及列表符号
        s = s.replace(Regex("^(标题|题目|Title)\\s*[:：]\\s*", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("^[-*•\\d.、]\\s*"), "")
        // 去掉包裹引号
        s = s.trim().trim('"', '\'', '「', '」', '《', '》', '“', '”', '『', '』')
        // 拒绝把指令复述当标题：这类输出说明模型没理解任务，宁可降级
        if (s.contains("用户说", ignoreCase = true) ||
            s.contains("回答用户", ignoreCase = true) ||
            s.contains("需要作为标题", ignoreCase = true) ||
            s.contains("起标题", ignoreCase = true) ||
            s.startsWith("好的", ignoreCase = true) ||
            s.startsWith("以下是", ignoreCase = true)
        ) return null
        s = s.take(24).trim()
        return s.takeIf { it.length >= 2 }
    }

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
        // 桥里的 confirmHandler 返回 Boolean?，而 ToolContext 要求非空 Boolean；
        // 用显式类型的单一 lambda 收敛，避免 elvis 把 lambda 推断成普通函数类型。
        val confirmCb: suspend (String, Map<String, Any>) -> Boolean = { name, args ->
            uiConfirm?.invoke(name, args) ?: false
        }
        val context = ToolContext(
            sessionId = "cloud-${System.currentTimeMillis()}",
            deviceInfo = deviceInfo(),
            confirmCallback = confirmCb,
            choiceCallback = uiChoice
        )

        // 附件：图片转 data URL（多模态），文本附件已在 Kotlin 侧拼进 content
        val history = withContext(Dispatchers.IO) {
            messages.map { CloudMsg(it.role, it.content, images = it.images.mapNotNull(::toDataUrl)) }
        }.toMutableList()
        applyThinkingHint(history, thinkingLevel)
        var rounds = 0
        // 动态轮次上限：撞到 MAX_TOOL_ROUNDS 时询问用户是否继续；
        // 用户选继续则在本轮基础上再放宽 ROUNDS_PER_EXTEND 轮，最多累计 MAX_ROUNDS_HARD 轮防止无限循环。
        var roundLimit = MAX_TOOL_ROUNDS

        while (rounds < roundLimit) {
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

            // 撞到当前上限：询问用户是否继续检索，而不是直接强制结束。
            if (rounds >= roundLimit && rounds < MAX_ROUNDS_HARD) {
                val answer = runCatching {
                    uiChoice?.invoke(
                        UserChoiceRequest(
                            question = "已经连续调用 $rounds 轮工具仍未得出答案。要继续检索，还是用现有信息直接总结？",
                            mode = ChoiceMode.SINGLE,
                            options = listOf("继续检索", "就此总结"),
                            allowExtra = false
                        )
                    )
                }.onFailure { LogSystem.warn(TAG, "询问是否继续工具循环失败: ${it.message}") }
                    .getOrNull()

                val keepGoing = answer?.selected?.firstOrNull() == "继续检索"
                if (keepGoing) {
                    roundLimit = (roundLimit + ROUNDS_PER_EXTEND).coerceAtMost(MAX_ROUNDS_HARD)
                    LogSystem.info(TAG, "用户选择继续检索，轮次上限放宽到 $roundLimit")
                    // 追加一条提示，让模型明确知道可以继续、但尽快收敛
                    history.add(CloudMsg("user", "请基于已获取的信息继续检索，并在信息足够时尽快给出最终答案。"))
                } else {
                    // 用户选「就此总结」/ 取消 / 无 UI 回调（后台）——统一走强制总结，
                    // 注入提示让模型用现有结果收尾，绝不挂起等待。
                    LogSystem.info(TAG, "工具循环就此总结 (rounds=$rounds, answer=${answer?.selected})")
                    history.add(CloudMsg("user", "不要再调用工具，请基于已获取的信息直接给出最终答案。"))
                    val finalTurn = client.chat(config, history, emptyList(), onToken, reasoningEffort = thinkingLevel)
                        .getOrElse { e ->
                            onStop("cloud_error: ${e.message ?: e.javaClass.simpleName}")
                            return
                        }
                    if (finalTurn.finishReason.equals("length", ignoreCase = true)) {
                        onToken("\n\n> ⚠️ 输出达到上限被截断。请调低思考档位后重试。")
                        onStop("length_truncated")
                    } else {
                        onStop("eos")
                    }
                    return
                }
            }
        }

        LogSystem.warn(TAG, "达到工具调用轮次硬上限 $MAX_ROUNDS_HARD，强制总结")
        // 到硬上限时同样尝试用现有信息收尾，避免把中途自言自语当最终答案（吞回答）。
        history.add(CloudMsg("user", "不要再调用工具，请基于已获取的信息直接给出最终答案。"))
        val finalTurn = client.chat(config, history, emptyList(), onToken, reasoningEffort = thinkingLevel)
            .getOrElse { e ->
                onStop("cloud_error: ${e.message ?: e.javaClass.simpleName}")
                return
            }
        onStop(if (finalTurn.finishReason.equals("length", ignoreCase = true)) "length_truncated" else "eos")
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
        /** 用户每次选「继续检索」追加的轮数。 */
        const val ROUNDS_PER_EXTEND = 2
        /** 累计轮次硬上限，防止用户一直点继续导致无限循环。 */
        const val MAX_ROUNDS_HARD = 12
        const val MAX_IMAGE_BYTES = 16 * 1024 * 1024

        /**
         * 会话标题生成专用 system prompt。
         *
         * 要点：推理型模型容易把「给对话起标题」当成待分析问题，吐出
         * 「我们需要回答用户……用户说『X』……需要作为标题」这类思考过程。
         * 因此这里用 few-shot 把输出严格锚定成「一个名词短语」，并明确禁止分析/复述。
         */
        const val TITLE_SYSTEM_PROMPT =
            "你是一个对话标题生成器。你的唯一任务是把用户给的对话内容概括成一个简短标题。\n" +
            "规则：\n" +
            "1. 只输出标题本身，是一个名词短语，不超过 12 个汉字。\n" +
            "2. 严禁分析、解释、复述用户的话，严禁输出『用户说』『我们需要回答』『作为标题』等字样。\n" +
            "3. 严禁加引号、标点、『标题：』前缀或任何多余文字。\n" +
            "4. 不要回答用户的问题，只要给这段内容起个名字。\n" +
            "示例：\n" +
            "用户内容：帮我写一个冒泡排序的 Python 函数\n" +
            "标题：Python冒泡排序\n" +
            "用户内容：明天上海天气怎么样\n" +
            "标题：上海明日天气\n" +
            "用户内容：解释一下量子纠缠的原理\n" +
            "标题：量子纠缠原理"
    }
}
