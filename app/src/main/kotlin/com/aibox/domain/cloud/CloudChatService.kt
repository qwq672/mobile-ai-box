package com.aibox.domain.cloud

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.aibox.data.repo.SettingsRepository
import com.aibox.domain.tool.DeviceInfo
import com.aibox.domain.tool.FunctionCall
import com.aibox.domain.tool.ToolContext
import com.aibox.domain.tool.ToolExecutor
import com.aibox.domain.tool.ToolRegistry
import com.aibox.infra.jni.NativeBridge
import com.aibox.util.log.LogSystem
import com.aibox.util.outcome.Outcome
import kotlinx.coroutines.flow.first
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

        val tools = runCatching { toolRegistry.getActiveToolsForLLM() }
            .onFailure { LogSystem.warn(TAG, "读取工具列表失败: ${it.message}") }
            .getOrDefault(emptyList())
        val context = ToolContext(
            sessionId = "cloud-${System.currentTimeMillis()}",
            deviceInfo = deviceInfo()
        )

        val history = messages.map { CloudMsg(it.role, it.content) }.toMutableList()
        var rounds = 0

        while (rounds < MAX_TOOL_ROUNDS) {
            val turn = client.chat(config, history, tools, onToken).getOrElse { e ->
                onStop("cloud_error: ${e.message ?: e.javaClass.simpleName}")
                return
            }

            if (turn.toolCalls.isEmpty()) {
                onStop("eos")
                return
            }

            // 把 assistant 的 tool_calls 原样回填，否则下一轮 provider 会拒绝
            history.add(CloudMsg("assistant", turn.content, toolCalls = turn.toolCalls))

            for (call in turn.toolCalls) {
                LogSystem.info(TAG, "tool call: ${call.name} args=${call.argumentsJson.take(200)}")
                onToken("\n\n[工具] ${call.name} …\n\n")
                val args = parseArguments(call.argumentsJson)
                val output = when (val r = toolExecutor.executeFunctionCall(FunctionCall(call.name, args), context)) {
                    is Outcome.Success -> r.value.output
                    is Outcome.Failure -> "ERROR: ${r.reason}"
                }
                history.add(CloudMsg("tool", output, toolCallId = call.id))
            }
            rounds++
        }

        LogSystem.warn(TAG, "达到工具调用轮次上限 $MAX_TOOL_ROUNDS，强制结束")
        onStop("max_tool_rounds")
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
    }
}
