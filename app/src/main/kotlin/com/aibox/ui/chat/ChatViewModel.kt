package com.aibox.ui.chat

import android.app.ActivityManager
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibox.AIBoxApp
import com.aibox.data.model.ChatMessage
import com.aibox.data.repo.ChatRepository
import com.aibox.data.repo.SettingsRepository
import com.aibox.domain.inference.InferenceEngine
import com.aibox.domain.shortcut.PresetConfig
import com.aibox.domain.shortcut.Shortcut
import com.aibox.domain.shortcut.ShortcutTargetType
import com.aibox.domain.tool.DeviceInfo
import com.aibox.infra.jni.NativeBridge
import com.aibox.ui.tool.PendingToolConfirmation
import com.aibox.ui.tool.ToolInvocation
import com.aibox.ui.tool.ToolInvocationViewModel
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ChatViewModel : ViewModel() {

    private val container get() = AIBoxApp.instance.container
    private val engine: InferenceEngine get() = container.inferenceEngine
    private val settings: SettingsRepository get() = container.settingsRepository
    private val chatRepo: ChatRepository get() = container.chatRepository

    /**
     * 工具调用流程管理器（B6 工具调用 UI）。
     *
     * 由 ChatViewModel 持有，用 [viewModelScope] 作协程作用域；
     * UI（[ChatScreen]）通过 [ui.toolInvocations] / [ui.pendingToolConfirmation] 订阅状态。
     */
    val toolInvocationViewModel: ToolInvocationViewModel = ToolInvocationViewModel(
        toolExecutor = container.toolExecutor,
        toolRegistry = container.toolRegistry,
        toolSettingsRepository = container.toolSettingsRepository,
        scope = viewModelScope
    )

    data class UiState(
        val messages: List<ChatMessage> = emptyList(),
        val streaming: Boolean = false,
        val currentStream: String = "",
        val backendName: String = "CPU",
        val tokensPerSecond: Float = 0f,
        val thermalPercent: Int = 0,
        val errorMessage: String? = null,
        val hasModel: Boolean = false,
        val modelName: String = "",
        val contextUsed: Int = 0,
        val contextMax: Int = 0,
        val sessionId: Long = 0,
        val sessionTitle: String = "",
        // B6 工具调用 UI: 活跃工具调用列表 + 当前待确认（若有）
        val toolInvocations: List<ToolInvocation> = emptyList(),
        val pendingToolConfirmation: PendingToolConfirmation? = null
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    @Volatile private var systemPrompt: String = ""
    @Volatile private var thinkingMode: Boolean = true

    init {
        viewModelScope.launch {
            settings.config.collectLatest { systemPrompt = it.systemPrompt }
        }
        viewModelScope.launch {
            settings.thinkingMode.collectLatest { thinkingMode = it }
        }
        viewModelScope.launch {
            engine.stats.collectLatest { s ->
                _ui.value = _ui.value.copy(
                    tokensPerSecond = s.tokensPerSecond,
                    thermalPercent = s.thermalPercent,
                    backendName = s.backend.displayNameZh,
                    contextUsed = s.contextUsed,
                    contextMax = s.contextMax
                )
            }
        }
        viewModelScope.launch {
            engine.state.collectLatest { st ->
                _ui.value = _ui.value.copy(
                    hasModel = st.loaded,
                    modelName = st.modelPath?.substringAfterLast('/') ?: "",
                    errorMessage = st.error
                )
            }
        }
        // B6 工具调用 UI: 订阅 toolInvocationViewModel 的状态，传播到 UiState
        viewModelScope.launch {
            toolInvocationViewModel.invocations.collectLatest { invocations ->
                _ui.value = _ui.value.copy(toolInvocations = invocations)
            }
        }
        viewModelScope.launch {
            toolInvocationViewModel.pendingConfirmation.collectLatest { pending ->
                _ui.value = _ui.value.copy(pendingToolConfirmation = pending)
            }
        }
    }

    fun loadSession(sessionId: Long) {
        viewModelScope.launch {
            if (sessionId == 0L) {
                _ui.value = _ui.value.copy(messages = emptyList(), sessionId = 0, sessionTitle = "")
                return@launch
            }
            val session = chatRepo.getSession(sessionId)
            val msgs = chatRepo.getMessages(sessionId)
            _ui.value = _ui.value.copy(
                sessionId = sessionId,
                sessionTitle = session?.title ?: "",
                messages = msgs
            )
            LogSystem.info("aibox.ui.chat", "ChatVM: loadSession id=$sessionId msgs=${msgs.size}")
        }
    }

    fun newSession(onCreated: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val modelPath = engine.state.value.modelPath
            val title = "新话题 - ${System.currentTimeMillis() % 100000}"
            val id = chatRepo.createSession(title, modelPath)
            _ui.value = _ui.value.copy(sessionId = id, sessionTitle = title, messages = emptyList())
            LogSystem.info("aibox.ui.chat", "ChatVM: newSession id=$id")
            onCreated(id)
        }
    }

    fun send(text: String) {
        if (_ui.value.streaming) return
        if (text.isBlank()) return
        if (!_ui.value.hasModel) {
            _ui.value = _ui.value.copy(errorMessage = "尚未加载模型，请先到「模型」页加载")
            return
        }

        var sid = _ui.value.sessionId
        if (sid == 0L) {
            viewModelScope.launch {
                val modelPath = engine.state.value.modelPath
                val title = text.take(20).ifBlank { "新话题" }
                sid = chatRepo.createSession(title, modelPath)
                _ui.value = _ui.value.copy(sessionId = sid, sessionTitle = title)
                continueSend(text, sid)
            }
        } else {
            continueSend(text, sid)
        }
    }

    private fun continueSend(text: String, sid: Long) {
        val msg = ChatMessage(role = "user", content = text, sessionId = sid)
        _ui.value = _ui.value.copy(
            messages = _ui.value.messages + msg,
            streaming = true,
            currentStream = "",
            errorMessage = null
        )
        LogSystem.info("aibox.ui.chat", "ChatViewModel: send ${text.length} chars (sid=$sid)")

        viewModelScope.launch {
            runCatching { chatRepo.addMessage(sid, msg) }
                .onFailure { LogSystem.error("aibox.ui.chat", "ChatVM: persist user msg failed", it) }
        }

        viewModelScope.launch {
            val collected = StringBuilder()
            try {
                // 构造 messages 数组，用模型内置 chat template
                val messages = buildChatMessages(_ui.value.messages)
                engine.completionChat(
                    messages = messages,
                    thinkingMode = thinkingMode,
                    onToken = { tok ->
                        collected.append(tok)
                        _ui.value = _ui.value.copy(currentStream = collected.toString())
                    },
                    onStop = { reason ->
                        val tps = engine.stats.value.tokensPerSecond
                        val assistant = ChatMessage(
                            role = "assistant",
                            content = collected.toString(),
                            sessionId = sid,
                            tokensPerSecond = tps
                        )
                        val err = if (reason == "decode_failed" || reason == "callback_error" || reason.startsWith("exception"))
                            "推理中断：$reason" else null
                        _ui.value = _ui.value.copy(
                            messages = _ui.value.messages + assistant,
                            streaming = false,
                            currentStream = "",
                            errorMessage = err
                        )
                        LogSystem.info("aibox.ui.chat", "ChatViewModel: completion stopped reason=$reason tps=$tps")
                        viewModelScope.launch {
                            runCatching { chatRepo.addMessage(sid, assistant) }
                                .onFailure { LogSystem.error("aibox.ui.chat", "ChatVM: persist assistant msg failed", it) }
                        }
                    }
                )
            } catch (t: Throwable) {
                LogSystem.error("aibox.ui.chat", "ChatViewModel: completion threw", t)
                _ui.value = _ui.value.copy(
                    streaming = false,
                    currentStream = "",
                    errorMessage = "推理异常: ${t.message}"
                )
            }
        }
    }

    fun stop() {
        engine.interrupt()
    }

    fun clearMessages() {
        if (_ui.value.streaming) return
        _ui.value = _ui.value.copy(messages = emptyList(), errorMessage = null)
    }

    fun deleteMessage(m: ChatMessage) {
        if (_ui.value.streaming) return
        _ui.value = _ui.value.copy(messages = _ui.value.messages.filterNot { it.ts == m.ts && it.role == m.role })
        if (m.id > 0) {
            viewModelScope.launch {
                runCatching { chatRepo.deleteMessage(m.id) }
                    .onFailure { LogSystem.error("aibox.ui.chat", "ChatVM: delete msg failed", it) }
            }
        }
    }

    fun regenerate() {
        if (_ui.value.streaming) return
        val msgs = _ui.value.messages
        if (msgs.isEmpty()) return
        val last = msgs.last()
        if (last.role != "assistant") return
        val withoutLast = msgs.dropLast(1)
        _ui.value = _ui.value.copy(
            messages = withoutLast,
            streaming = true,
            currentStream = "",
            errorMessage = null
        )
        LogSystem.info("aibox.ui.chat", "ChatViewModel: regenerate")
        viewModelScope.launch {
            val collected = StringBuilder()
            try {
                val messages = buildChatMessages(withoutLast)
                engine.completionChat(
                    messages = messages,
                    thinkingMode = thinkingMode,
                    onToken = { tok ->
                        collected.append(tok)
                        _ui.value = _ui.value.copy(currentStream = collected.toString())
                    },
                    onStop = { reason ->
                        val tps = engine.stats.value.tokensPerSecond
                        val assistant = ChatMessage(
                            role = "assistant",
                            content = collected.toString(),
                            sessionId = _ui.value.sessionId,
                            tokensPerSecond = tps
                        )
                        _ui.value = _ui.value.copy(
                            messages = _ui.value.messages + assistant,
                            streaming = false,
                            currentStream = ""
                        )
                        LogSystem.info("aibox.ui.chat", "ChatViewModel: regenerate stopped reason=$reason tps=$tps")
                        if (_ui.value.sessionId != 0L) {
                            viewModelScope.launch {
                                runCatching { chatRepo.addMessage(_ui.value.sessionId, assistant) }
                                    .onFailure { LogSystem.error("aibox.ui.chat", "ChatVM: persist regenerate msg failed", it) }
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                LogSystem.error("aibox.ui.chat", "ChatViewModel: regenerate threw", t)
                _ui.value = _ui.value.copy(
                    streaming = false,
                    currentStream = "",
                    errorMessage = "重新生成异常: ${t.message}"
                )
            }
        }
    }

    fun dismissError() {
        _ui.value = _ui.value.copy(errorMessage = null)
    }

    // ===================================================
    // B6 工具调用 UI: 暴露给 ChatScreen 的工具调用操作
    // ===================================================

    /**
     * 提交一次工具调用（供未来 AgentRunner 集成；当前也可由 UI 测试入口调起）。
     *
     * 流程：构造 [DeviceInfo] 快照 → 调 [ToolInvocationViewModel.submit] → 自动 launch 执行。
     * 高级工具会触发 confirmCallback → [ToolInvocationViewModel.pendingConfirmation] 更新 → UI 弹 dialog。
     *
     * @param toolId 工具 id（与 [com.aibox.domain.tool.Tool.id] 一致）
     * @param params 参数 map（LLM function call 解析后传入；当前由调用方构造）
     * @return 创建的 invocation id；找不到工具时返回 null（执行流程仍会创建 FAILED invocation，便于在 UI 反馈）
     */
    fun tryInvokeTool(toolId: String, params: Map<String, Any>): String? {
        val sessionId = "chat-${_ui.value.sessionId}"
        val deviceInfo = buildDeviceInfo()
        val id = toolInvocationViewModel.submit(
            toolId = toolId,
            params = params,
            sessionId = sessionId,
            deviceInfo = deviceInfo
        )
        LogSystem.info("aibox.ui.chat", "tryInvokeTool: $toolId → invocation $id")
        return id
    }

    /** 用户点"确认执行"：转发到 [ToolInvocationViewModel.confirm]。 */
    fun confirmToolInvocation() = toolInvocationViewModel.confirm()

    /** 用户点"拒绝"：转发到 [ToolInvocationViewModel.deny]。 */
    fun denyToolInvocation() = toolInvocationViewModel.deny()

    /** 用户点"始终允许此工具"：转发到 [ToolInvocationViewModel.alwaysAllow]（加入自动确认白名单）。 */
    fun alwaysAllowTool() = toolInvocationViewModel.alwaysAllow()

    /** 用户点"重试"：转发到 [ToolInvocationViewModel.retry]。 */
    fun retryToolInvocation(invocationId: String) = toolInvocationViewModel.retry(invocationId)

    /** 清空已完成（SUCCESS / FAILED）的工具调用历史；保留正在执行中的。 */
    fun clearToolInvocations() = toolInvocationViewModel.clearHistory()

    /**
     * 构造一个聊天快捷方式草稿（E 主页 Hybrid：ChatScreen "保存为快捷方式" 菜单）。
     *
     * 从当前 ViewModel 状态提取：
     * - [systemPrompt]：当前 system prompt（空表示用全局 Settings 的 systemPrompt）
     * - 副标题：当前已加载模型名（modelPath 的 basename）
     * - targetType：CHAT
     * - modelId：null（= "使用当前激活模型"）
     *
     * 调用方应把此草稿通过 [com.aibox.ui.shortcut.ShortcutEditorLauncher] 传给
     * [com.aibox.ui.shortcut.ShortcutEditorScreen] 预填。
     */
    fun buildShortcutDraft(): Shortcut {
        val preset = PresetConfig(
            systemPrompt = systemPrompt.ifBlank { null }
        )
        val activeModelName = engine.state.value.modelPath?.substringAfterLast('/')
        LogSystem.info("aibox.ui.chat", "buildShortcutDraft: systemPrompt.len=${systemPrompt.length} model=$activeModelName")
        return Shortcut(
            id = "",
            title = "",
            subtitle = activeModelName,
            icon = ShortcutTargetType.defaultIcon(ShortcutTargetType.CHAT),
            targetType = ShortcutTargetType.CHAT,
            presetConfig = preset,
            modelId = null
        )
    }

    /**
     * 构造 [DeviceInfo] 快照（最小可用版本：Build + 内存 + 电量）。
     *
     * 用于工具调用上下文。完整 DeviceInfo 由未来 AgentRunner 在调用前统一构造（含 RAM / 电量等），
     * 这里仅做最小化填充让基础工具能跑（如 device_info_basic 会用此快照对比实时读取）。
     */
    private fun buildDeviceInfo(): DeviceInfo {
        val appContext = container.appContext
        var totalRamMb: Long = 0
        var availRamMb: Long = 0
        try {
            val am = appContext.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            totalRamMb = (mi?.totalMem ?: 0L) / (1024 * 1024)
            availRamMb = (mi?.availMem ?: 0L) / (1024 * 1024)
        } catch (t: Throwable) {
            LogSystem.warn("aibox.ui.chat", "buildDeviceInfo: memory read failed: ${t.message}")
            totalRamMb = 0
            availRamMb = 0
        }
        val (batteryLevel, isCharging) = try {
            val intent = appContext.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val pct = if (scale > 0) (level * 100) / scale else -1
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            pct to (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
        } catch (t: Throwable) {
            LogSystem.warn("aibox.ui.chat", "buildDeviceInfo: battery read failed: ${t.message}")
            -1 to false
        }
        return DeviceInfo(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = Build.VERSION.RELEASE ?: "unknown",
            sdkVersion = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.toList(),
            totalRamMb = totalRamMb,
            availableRamMb = availRamMb,
            batteryLevel = batteryLevel,
            isCharging = isCharging
        )
    }

    /**
     * 把对话历史转换成 NativeBridge.ChatMsg 数组给 native 端用模型内置 chat template 格式化。
     * - 如果 systemPrompt 非空，加一条 system message
     * - 然后 user/assistant 消息按顺序加入
     * - thinkingMode 由 native 端在 system message 末尾追加 /think 标志
     */
    private fun buildChatMessages(msgs: List<ChatMessage>): List<NativeBridge.ChatMsg> {
        val list = mutableListOf<NativeBridge.ChatMsg>()
        if (systemPrompt.isNotBlank()) {
            list.add(NativeBridge.ChatMsg(role = "system", content = systemPrompt.trim()))
        }
        for (m in msgs) {
            list.add(NativeBridge.ChatMsg(role = m.role, content = m.content))
        }
        return list
    }
}
