package com.aibox.ui.chat

import android.app.ActivityManager
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibox.AIBoxApp
import com.aibox.data.attach.AttachmentStore
import com.aibox.data.model.ChatAttachment
import com.aibox.data.model.ChatMessage
import com.aibox.data.model.MsgPart
import com.aibox.data.model.ToolHit
import com.aibox.data.repo.ChatRepository
import com.aibox.data.repo.SettingsRepository
import com.aibox.domain.inference.InferenceEngine
import com.aibox.domain.shortcut.PresetConfig
import com.aibox.domain.shortcut.Shortcut
import com.aibox.domain.shortcut.ShortcutTargetType
import com.aibox.domain.tool.DeviceInfo
import com.aibox.domain.tool.builtin.SearchResultItem
import com.aibox.infra.jni.NativeBridge
import com.aibox.util.outcome.Outcome
import com.aibox.ui.tool.PendingToolConfirmation
import com.aibox.ui.tool.ToolInvocation
import com.aibox.ui.tool.ToolInvocationViewModel
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 告诉模型回复会经过 Markdown 扩展渲染。
 *
 * 刻意压成一行：小模型上下文宝贵，长篇能力说明反而会提高复读 / 幻觉概率。
 */
private const val UI_CAPABILITY_HINT =
    "回复用 Markdown，数学用 \$...\$ 或 \$\$...\$\$；需要用户选择时输出 ```aibox:choices 代码块（每行一个选项）。"

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
        /** 流式过程中的结构化时间线（思考 / 工具 / 正文交错）。 */
        val streamParts: List<MsgPart> = emptyList(),
        val backendName: String = "CPU",
        val tokensPerSecond: Float = 0f,
        val thermalPercent: Int = 0,
        /** 真实摄氏度；-1 = 读不到（不要用百分比冒充） */
        val thermalCelsius: Double = -1.0,
        val errorMessage: String? = null,
        val hasModel: Boolean = false,
        val modelName: String = "",
        val contextUsed: Int = 0,
        val contextMax: Int = 0,
        val sessionId: Long = 0,
        val sessionTitle: String = "",
        /** 消息呈现：true = 文档流（助手无气泡），false = 双方气泡 */
        val docStyle: Boolean = true,
        /** 待发送附件（图片 / 文件），发送后清空 */
        val pendingAttachments: List<ChatAttachment> = emptyList(),
        /** 思考档位："off" / "low" / "medium" / "high" */
        val thinkingLevel: String = "medium",
        // B6 工具调用 UI: 活跃工具调用列表 + 当前待确认（若有）
        val toolInvocations: List<ToolInvocation> = emptyList(),
        val pendingToolConfirmation: PendingToolConfirmation? = null
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    @Volatile private var systemPrompt: String = ""

    /** 思考开关统一由档位决定，避免「关了还发 /think」的状态不一致。 */
    private val thinkingEnabled: Boolean
        get() = _ui.value.thinkingLevel != "off"

    init {
        viewModelScope.launch {
            settings.config.collectLatest { systemPrompt = it.systemPrompt }
        }
        viewModelScope.launch {
            settings.thinkingLevel.collectLatest { level ->
                _ui.value = _ui.value.copy(thinkingLevel = level)
            }
        }
        viewModelScope.launch {
            settings.chatDocStyle.collectLatest { on ->
                _ui.value = _ui.value.copy(docStyle = on)
            }
        }
        viewModelScope.launch {
            container.thermalMonitor.celsius.collectLatest { c ->
                _ui.value = _ui.value.copy(thermalCelsius = c)
            }
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
        val attachments = _ui.value.pendingAttachments
        if (text.isBlank() && attachments.isEmpty()) return
        if (!_ui.value.hasModel) {
            _ui.value = _ui.value.copy(errorMessage = "尚未加载模型，请先到「模型」页加载")
            return
        }

        var sid = _ui.value.sessionId
        if (sid == 0L) {
            viewModelScope.launch {
                val modelPath = engine.state.value.modelPath
                val title = text.take(20).ifBlank { attachments.firstOrNull()?.name ?: "新话题" }
                sid = chatRepo.createSession(title, modelPath)
                _ui.value = _ui.value.copy(sessionId = sid, sessionTitle = title)
                continueSend(text, sid, attachments)
            }
        } else {
            continueSend(text, sid, attachments)
        }
    }

    private fun continueSend(text: String, sid: Long, attachments: List<ChatAttachment> = emptyList()) {
        val msg = ChatMessage(role = "user", content = text, sessionId = sid, attachments = attachments)
        _ui.value = _ui.value.copy(
            messages = _ui.value.messages + msg,
            streaming = true,
            currentStream = "",
            streamParts = emptyList(),
            errorMessage = null,
            pendingAttachments = emptyList()
        )
        LogSystem.info("aibox.ui.chat", "ChatViewModel: send ${text.length} chars, ${attachments.size} attachments (sid=$sid)")

        viewModelScope.launch {
            runCatching { chatRepo.addMessage(sid, msg) }
                .onSuccess { newId -> setPersistedId(msg.ts, msg.role, newId) }
                .onFailure { LogSystem.error("aibox.ui.chat", "ChatVM: persist user msg failed", it) }
        }

        viewModelScope.launch {
            val gen = generate(sid, _ui.value.messages)
            val assistant = ChatMessage(
                role = "assistant",
                content = gen.text,
                sessionId = sid,
                tokensPerSecond = gen.tps,
                parts = gen.parts,
                versions = listOf(gen.text),
                versionIndex = 0
            )
            _ui.value = _ui.value.copy(
                messages = _ui.value.messages + assistant,
                streaming = false,
                currentStream = "",
                streamParts = emptyList(),
                errorMessage = gen.error
            )
            LogSystem.info("aibox.ui.chat", "ChatViewModel: turn done parts=${gen.parts.size} tps=${gen.tps}")
            viewModelScope.launch {
                runCatching { chatRepo.addMessage(sid, assistant) }
                    .onSuccess { newId -> setPersistedId(assistant.ts, assistant.role, newId) }
                    .onFailure { LogSystem.error("aibox.ui.chat", "ChatVM: persist assistant msg failed", it) }
            }
        }
    }

    /**
     * 一次完整的助手回合：多轮推理 + 工具循环，返回正文与结构化时间线。
     *
     * 本地模型没有原生 function calling，因此用文本协议（见 [buildToolPrompt]）：
     * 模型输出 `aibox:tool` 代码块 → 执行工具 → 结果作为下一条消息喂回去 → 继续生成。
     * 云端模型的工具循环在 [com.aibox.domain.cloud.CloudChatService] 内完成，这里不重复。
     */
    private suspend fun generate(sid: Long, history: List<ChatMessage>): Generated {
        val tools = if (engine.isCloudActive()) emptyList() else runCatching {
            container.toolRegistry.getActiveToolsForLLM()
        }.onFailure { LogSystem.warn("aibox.ui.chat", "读取工具列表失败: ${it.message}") }
            .getOrDefault(emptyList())
        val toolPrompt = buildToolPrompt(tools)

        val conv = history.toMutableList()
        val parts = mutableListOf<MsgPart>()
        val rawAll = StringBuilder()
        var firstTokMs = 0L
        var tokCount = 0
        var lastUiPushMs = 0L
        var error: String? = null
        var rounds = 0

        try {
            while (rounds <= MAX_TOOL_ROUNDS) {
                val turn = StringBuilder()
                engine.completionChat(
                    messages = buildChatMessages(conv, toolPrompt),
                    thinkingMode = thinkingEnabled,
                    onToken = { tok ->
                        if (firstTokMs == 0L) firstTokMs = System.currentTimeMillis()
                        tokCount++
                        turn.append(tok)
                        // 流式 UI 按 ~30fps 合批更新：逐 token 触发 Compose 重组 + Markdown
                        // 全量解析会把 CPU 一直顶在高位，明显增加发热。
                        val now = System.currentTimeMillis()
                        if (now - lastUiPushMs >= 33L) {
                            lastUiPushMs = now
                            _ui.value = _ui.value.copy(
                                currentStream = rawAll.toString() + turn,
                                streamParts = parts + parseTimeline(turn.toString())
                            )
                        }
                    },
                    onStop = { reason ->
                        if (reason == "decode_failed" || reason == "callback_error" || reason.startsWith("exception")) {
                            error = "推理中断：$reason"
                        }
                    }
                )
                val roundText = turn.toString()
                rawAll.append(roundText)
                val call = if (tools.isNotEmpty()) extractToolCall(roundText) else null
                val parsed = parseTimeline(roundText)
                // 收尾补一次完整状态，避免合批把最后不足 33ms 的 token 丢在界面上
                _ui.value = _ui.value.copy(
                    currentStream = rawAll.toString(),
                    streamParts = parts + parsed
                )

                if (call == null) {
                    parts += parsed
                    break
                }

                // 模型自己声明的工具调用块不重复展示；用统一的 tool part 表示执行状态
                parts += parsed.filterNot { it.kind == MsgPart.KIND_TOOL }
                val displayName = container.toolRegistry.getTool(call.name)?.displayName ?: call.name
                val toolPart = MsgPart.tool(call.name, displayName, call.argumentsJson, running = true)
                parts += toolPart
                _ui.value = _ui.value.copy(streamParts = parts.toList())

                val outcome = toolInvocationViewModel.executeAwait(
                    toolId = call.name,
                    params = call.arguments,
                    sessionId = "chat-$sid",
                    deviceInfo = buildDeviceInfo()
                )
                var success = false
                var output = ""
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
                        output = outcome.reason
                    }
                }
                parts[parts.lastIndex] = toolPart.copy(
                    running = false,
                    success = success,
                    output = output.take(4000),
                    hits = hits
                )
                _ui.value = _ui.value.copy(streamParts = parts.toList())

                // 回填本轮输出与工具结果，让模型继续基于真实结果作答
                conv += ChatMessage(role = "assistant", content = roundText, sessionId = sid)
                conv += ChatMessage(
                    role = "user",
                    content = "[工具 ${call.name} 执行结果]\n$output",
                    sessionId = sid
                )
                rounds++
            }
        } catch (t: Throwable) {
            LogSystem.error("aibox.ui.chat", "ChatViewModel: generate threw", t)
            error = "推理异常: ${t.message}"
        }

        val text = parts.filter { it.kind == MsgPart.KIND_TEXT && it.text.isNotBlank() }
            .joinToString("\n\n") { it.text.trim() }
            .trim()
            .ifBlank { rawAll.toString().trim() }
        val genMs = if (firstTokMs > 0) (System.currentTimeMillis() - firstTokMs).coerceAtLeast(1) else 0L
        val tps = if (tokCount > 0 && genMs > 0) tokCount * 1000f / genMs else 0f
        return Generated(text, parts, error, tps)
    }

    /** 一次生成回合的结果。 */
    private data class Generated(
        val text: String,
        val parts: List<MsgPart>,
        val error: String?,
        val tps: Float
    )

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

    /**
     * 重新生成最后一条助手回复。
     *
     * 旧内容不会丢：作为 [ChatMessage.versions] 的一版保留，新结果追加为最后一版，
     * 消息下方出现 `2/3` 这样的版本切换器（见 [switchVersion]）。
     */
    fun regenerate() {
        if (_ui.value.streaming) return
        val msgs = _ui.value.messages
        val lastIndex = msgs.indexOfLast { it.role == "assistant" }
        if (lastIndex < 0) return
        val last = msgs[lastIndex]
        val history = msgs.take(lastIndex)
        // 先移除旧回复，只显示新生成的流式内容；完成后把新版本追加回去（旧内容在 versions 里）
        _ui.value = _ui.value.copy(
            messages = history,
            streaming = true,
            currentStream = "",
            streamParts = emptyList(),
            errorMessage = null
        )
        LogSystem.info("aibox.ui.chat", "ChatViewModel: regenerate")
        viewModelScope.launch {
            val gen = generate(_ui.value.sessionId, history)
            val base = if (last.versions.isEmpty()) listOf(last.content) else last.versions
            val versions = base + gen.text
            val updated = last.copy(
                content = gen.text,
                parts = gen.parts,
                versions = versions,
                versionIndex = versions.lastIndex,
                tokensPerSecond = gen.tps
            )
            val list = _ui.value.messages.toMutableList()
            list.add(updated)
            _ui.value = _ui.value.copy(
                messages = list.toList(),
                streaming = false,
                currentStream = "",
                streamParts = emptyList(),
                errorMessage = gen.error
            )
            persistUpdate(updated)
        }
    }

    /** 切换消息的历史版本（重新生成 / 编辑前的版本）。 */
    fun switchVersion(message: ChatMessage, index: Int) {
        if (_ui.value.streaming) return
        if (index !in message.versions.indices) return
        val updated = message.copy(
            content = message.versions[index],
            versionIndex = index,
            // 版本切换后时间线需要按新正文重新解析
            parts = emptyList()
        )
        replaceMessage(message, updated)
        persistUpdate(updated)
    }

    /** 编辑用户消息：旧内容自动保留为一个版本，可随时切回。 */
    fun editMessage(message: ChatMessage, newText: String) {
        if (_ui.value.streaming) return
        val text = newText.trim()
        if (text.isEmpty() || text == message.content) return
        val base = if (message.versions.isEmpty()) listOf(message.content) else message.versions
        val versions = base + text
        val updated = message.copy(
            content = text,
            versions = versions,
            versionIndex = versions.lastIndex,
            parts = emptyList()
        )
        replaceMessage(message, updated)
        persistUpdate(updated)
    }

    private fun replaceMessage(old: ChatMessage, updated: ChatMessage) {
        val list = _ui.value.messages.map {
            if (it.id == old.id && it.ts == old.ts) updated else it
        }
        _ui.value = _ui.value.copy(messages = list)
    }

    private fun persistUpdate(message: ChatMessage) {
        viewModelScope.launch {
            if (message.id > 0) {
                runCatching { chatRepo.updateMessage(message) }
                    .onFailure { LogSystem.error("aibox.ui.chat", "ChatVM: update msg failed", it) }
            } else if (message.sessionId != 0L) {
                // 新会话里还没落库的消息（id=0）：补插入，保证版本历史能持久化
                runCatching { chatRepo.addMessage(message.sessionId, message) }
                    .onSuccess { newId -> setPersistedId(message.ts, message.role, newId) }
                    .onFailure { LogSystem.error("aibox.ui.chat", "ChatVM: insert msg failed", it) }
            }
        }
    }

    /** 消息落库后回填 id，后续编辑 / 切换版本才能 update 到同一行。 */
    private fun setPersistedId(ts: Long, role: String, id: Long) {
        if (id <= 0) return
        _ui.value = _ui.value.copy(
            messages = _ui.value.messages.map {
                if (it.ts == ts && it.role == role && it.id == 0L) it.copy(id = id) else it
            }
        )
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
     * - systemPrompt 非空时作为 system message（为空则不加，native 会把 /think
     *   或 /no_think 追加到最后一条 user message）
     * - 文本附件正文拼进 user content；图片通过 [NativeBridge.ChatMsg.images] 交给云端多模态
     * - 思考档位 off / low / high 追加提示词，作为本地与云端的通用兜底
     */
    private fun buildChatMessages(
        msgs: List<ChatMessage>,
        toolPrompt: String = ""
    ): List<NativeBridge.ChatMsg> {
        val list = mutableListOf<NativeBridge.ChatMsg>()
        val levelHint = when (_ui.value.thinkingLevel) {
            "off" -> "请直接给出答案，不要输出思考过程。"
            "low" -> "请先做简短思考（1~3 句）再给出结论，保持简洁。"
            "high" -> "请先进行深入、完整的推理，再给出清晰结论。"
            else -> ""
        }
        val sys = listOf(systemPrompt.trim(), levelHint, UI_CAPABILITY_HINT, toolPrompt)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        if (sys.isNotBlank()) {
            list.add(NativeBridge.ChatMsg(role = "system", content = sys))
        }
        for (m in msgs) {
            val textParts = m.attachments.filter { it.kind == "text" && it.text.isNotBlank() }
            val images = m.attachments.filter { it.kind == "image" }.map { it.uri }
            // 回传历史时去掉客户端内部标记（思考 / 工具结果），模型不需要看到
            val clean = stripTimelineMarkers(m.content)
            val content = if (textParts.isEmpty() && images.isEmpty()) clean else buildString {
                append(clean)
                textParts.forEach { a ->
                    if (isNotEmpty()) append("\n\n")
                    append("[附件：${a.name}]\n")
                    append(a.text)
                }
                if (images.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append("[用户附带了 ${images.size} 张图片]")
                }
            }
            list.add(NativeBridge.ChatMsg(role = m.role, content = content, images = images))
        }
        return list
    }

    // ---------------- 附件 ----------------

    fun addImageAttachment(uri: android.net.Uri) {
        viewModelScope.launch {
            when (val r = AttachmentStore.importImage(AIBoxApp.instance, uri)) {
                is AttachmentStore.Result.Ok ->
                    _ui.value = _ui.value.copy(pendingAttachments = _ui.value.pendingAttachments + r.attachment)
                is AttachmentStore.Result.Error ->
                    _ui.value = _ui.value.copy(errorMessage = r.message)
            }
        }
    }

    fun addFileAttachment(uri: android.net.Uri) {
        viewModelScope.launch {
            when (val r = AttachmentStore.importFile(AIBoxApp.instance, uri)) {
                is AttachmentStore.Result.Ok ->
                    _ui.value = _ui.value.copy(pendingAttachments = _ui.value.pendingAttachments + r.attachment)
                is AttachmentStore.Result.Error ->
                    _ui.value = _ui.value.copy(errorMessage = r.message)
            }
        }
    }

    fun removePendingAttachment(index: Int) {
        val list = _ui.value.pendingAttachments.toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            _ui.value = _ui.value.copy(pendingAttachments = list)
        }
    }

    fun clearPendingAttachments() {
        _ui.value = _ui.value.copy(pendingAttachments = emptyList())
    }

    /** 快捷切换思考档位（顶栏菜单里用）。 */
    fun setThinkingLevel(level: String) {
        viewModelScope.launch { settings.setThinkingLevel(level) }
    }

    private companion object {
        /** 本地模型工具循环上限，避免模型反复调用同一工具。 */
        const val MAX_TOOL_ROUNDS = 4
    }
}
