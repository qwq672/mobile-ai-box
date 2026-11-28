package com.aibox.ui.chat

import android.app.ActivityManager
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibox.AIBoxApp
import com.aibox.data.attach.AttachmentStore
import com.aibox.data.db.effectiveSystemPrompt
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
import com.aibox.domain.tool.UiInteractionBridge
import com.aibox.domain.tool.builtin.SearchResultItem
import com.aibox.infra.jni.NativeBridge
import com.aibox.util.outcome.Outcome
import com.aibox.ui.tool.PendingToolConfirmation
import com.aibox.ui.tool.ToolInvocation
import com.aibox.ui.tool.ToolInvocationViewModel
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 告诉模型回复会经过 Markdown 扩展渲染。
 *
 * 刻意压成一行：小模型上下文宝贵，长篇能力说明反而会提高复读 / 幻觉概率。
 * 同时对 choices 做了硬约束：只在真正需要二选一时用，且不能代替正文。
 */
private const val UI_CAPABILITY_HINT = "回复用 Markdown。"

/**
 * Beta：choices 选项卡片的提示词。
 *
 * 默认不注入：离线小模型容易把工具参数、列表当正文输出，
 * 表现就是每轮弹出一堆 A/B/C。开启后也保持强约束。
 */
private const val CHOICES_BETA_HINT =
    "（Beta）只有在确实需要用户在 2~4 个方案中做选择时，才输出 ```aibox:choices 代码块：" +
        "每行一条、用用户口吻、最多 4 条；不要每轮都给选项，不要用选项代替回答。"

/**
 * 直观表达 Beta 提示词。仅在设置开启时注入 system prompt 尾部。
 * 强约束：只在确实有助理解时才用可视化，不用图代替文字；结构卡是轻量原生标记，
 * 思维导图优先用 ```mermaid 的 mindmap 语法（自研渲染可用），其余 mermaid 图本机不支持。
 */
private const val RICH_ANSWER_HINT =
    "（直观表达）可用轻量可视化让回答更清楚，但只在有助理解时用，别每轮都塞，也不要用图代替文字：\n" +
        "- 概念释义：把值得解释的词写成 {{?术语|一句话释义}}，App 会渲染成可点击的虚线词，点击就地看释义；释义要短。\n" +
        "- 步骤/对比/利弊：分别用 ```aibox:steps（每行一步）、```aibox:compare（形如 A | B 的两列）、```aibox:proscons（+ 优点 / - 缺点）代码块。\n" +
        "- 数值比例：写成 {{bar:70}} 会渲染成小进度条（0-100）。\n" +
        "- 层级关系优先用 ```mermaid 的 mindmap 语法；其它类型 mermaid 图本机不支持，请改用上面的结构卡或表格。"

/**
 * 流式打字机缓冲。
 *
 * token 到达节奏由后端决定：云端 / CPU 很快，NPU 上 7~15 tok/s 意味着每 70~150ms 才来一个 token，
 * 逐 token 直推 UI 会一顿一顿。这里把 token 先攒进 [target]，再由一条独立协程按固定节奏匀速
 * 往外吐字符（默认 20ms / 3 字 ≈ 150 字/s，快于任何端侧出字速度，故只会“铺平”不会显著滞后）。
 * [emit] 在 ViewModel 的 Main 作用域执行，安全更新 `_ui`。
 */
private class StreamTypewriter(
    private val scope: CoroutineScope,
    private val emit: (String) -> Unit,
    private val tickMs: Long = 20L,
    private val charsPerTick: Int = 3
) {
    @Volatile
    var target: String = ""
    private var shown = 0
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                val t = target
                if (shown < t.length) {
                    shown = (shown + charsPerTick).coerceAtMost(t.length)
                    emit(t.substring(0, shown))
                }
                delay(tickMs)
            }
        }
    }

    /**
     * 新的一轮生成开始。[alreadyShown] 是已完成轮次的前缀长度——必须跳过它，
     * 否则多轮工具调用时 target 含旧文本，会从 0 重新“播放”一遍旧内容，造成明显延迟。
     */
    fun reset(alreadyShown: Int) {
        shown = alreadyShown
        target = ""
    }

    /** 一轮生成结束：立刻把剩余字符补到完整，避免中间态残留 / 回退。 */
    fun flush() {
        val t = target
        if (shown < t.length) {
            shown = t.length
            emit(t)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

class ChatViewModel : ViewModel() {

    private val container get() = AIBoxApp.instance.container
    private val engine: InferenceEngine get() = container.inferenceEngine
    private val settings: SettingsRepository get() = container.settingsRepository
    private val chatRepo: ChatRepository get() = container.chatRepository

    /**
     * 推理链路专用作用域：应用级，脱离 ViewModel 生命周期。
     * 页面被回收（切后台久置 / 导航销毁）时 viewModelScope 会取消，
     * 导致正在生成的回复"中途停止"。用本作用域可让本轮生成 + 落库跑完。
     */
    private val inferenceScope get() = container.inferenceScope

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

    /** 当前启用的工具集合（输入区三态 chip 用）。 */
    val enabledToolIds: StateFlow<Set<String>> = container.toolSettingsRepository.enabledToolsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** 输入区「联网」三态循环：关 → 阅读 → 搜索 → 关。 */
    fun cycleNetworkMode() {
        val current = com.aibox.domain.tool.NetworkMode.fromEnabled(enabledToolIds.value)
        setNetworkMode(current.next())
    }

    /** 直接设置联网三态（加号菜单用）。 */
    fun setNetworkMode(mode: com.aibox.domain.tool.NetworkMode) {
        viewModelScope.launch {
            runCatching { container.toolSettingsRepository.setToolsEnabled(mode.applyTo(enabledToolIds.value)) }
                .onSuccess { LogSystem.info("aibox.tool", "network mode -> ${mode.name} (${mode.label})") }
                .onFailure { LogSystem.warn("aibox.tool", "setNetworkMode failed: ${it.message}") }
        }
    }

    /**
     * 按「用户消息版本」过滤出当前可见的消息。
     *
     * 用户重发后，旧回复仍然保留在列表中（作为旧版本的分支），
     * 但只有与当前 [ChatMessage.versionIndex] 匹配的助手回复会被展示，
     * 也只会被送入下一轮上下文。
     */
    fun visibleMessages(src: List<ChatMessage> = _ui.value.messages): List<ChatMessage> {
        val out = ArrayList<ChatMessage>(src.size)
        var userVersion = -1
        for (m in src) {
            when {
                m.role == "user" -> {
                    userVersion = m.versionIndex
                    out.add(m)
                }
                m.role == "assistant" && m.parentUserVersion >= 0 -> {
                    if (m.parentUserVersion == userVersion) out.add(m)
                }
                else -> out.add(m)
            }
        }
        return out
    }

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
        /** 机身表面体感温度；-1 = 读不到，UI 回退到 thermalCelsius */
        val skinCelsius: Double = -1.0,
        val errorMessage: String? = null,
        /** html 围栏即时预览开关（来自设置，默认关闭） */
        val htmlPreview: Boolean = false,
        val hasModel: Boolean = false,
        val modelName: String = "",
        /** 输入框回车行为：true = 回车发送，false = 回车换行。 */
        val enterToSend: Boolean = false,
        /** 减弱动态效果：true = token 直出，关闭打字机缓冲 / 光标 / 过渡动画。 */
        val reduceMotion: Boolean = false,
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
        /** Beta：是否启用 aibox:choices 选项卡片（默认关闭） */
        val choicesBeta: Boolean = false,
        /** Beta：是否启用直观表达（结构卡/释义卡，默认关闭） */
        val richAnswer: Boolean = false,
        // B6 工具调用 UI: 活跃工具调用列表 + 当前待确认（若有）
        val toolInvocations: List<ToolInvocation> = emptyList(),
        val pendingToolConfirmation: PendingToolConfirmation? = null,
        /** user_choice 工具待用户回答；null 表示没有 */
        val pendingUserChoice: com.aibox.ui.tool.PendingUserChoice? = null,
        // ---- 角色扮演 ----
        /** 会话模式："CHAT" / "AGENT" / "ROLEPLAY" */
        val mode: String = "CHAT",
        val characterName: String = "",
        val characterEmoji: String = "",
        /** 角色设定（注入 system prompt 的主体）。 */
        val characterSetting: String = "",
        /** 角色开场白（空会话时展示）。 */
        val characterGreeting: String = "",
        val personaName: String = "",
        val personaDescription: String = ""
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    @Volatile private var systemPrompt: String = ""

    /** 思考开关统一由档位决定，避免「关了还发 /think」的状态不一致。 */
    private val thinkingEnabled: Boolean
        get() = _ui.value.thinkingLevel != "off"

    init {
        // 把前台 UI 回调注册进桥，让云端工具循环（CloudChatService）也能弹
        // user_choice 选择面板 / 高级工具确认面板——此前云端路径无 callback，
        // 调用 user_choice 必然报「当前没有可用的选择界面」。
        UiInteractionBridge.choiceHandler = { request ->
            toolInvocationViewModel.awaitCloudChoice(request)
        }
        UiInteractionBridge.confirmHandler = { toolId, params ->
            toolInvocationViewModel.awaitCloudConfirm(toolId, params)
        }
        viewModelScope.launch {
            settings.config.collectLatest { systemPrompt = it.systemPrompt }
        }
        viewModelScope.launch {
            settings.thinkingLevel.collectLatest { level ->
                _ui.value = _ui.value.copy(thinkingLevel = level)
            }
        }
        viewModelScope.launch {
            settings.choicesBeta.collectLatest { on ->
                _ui.value = _ui.value.copy(choicesBeta = on)
            }
        }
        viewModelScope.launch {
            settings.richAnswer.collectLatest { on ->
                _ui.value = _ui.value.copy(richAnswer = on)
            }
        }
        viewModelScope.launch {
            settings.htmlPreview.collectLatest { on ->
                _ui.value = _ui.value.copy(htmlPreview = on)
            }
        }
        viewModelScope.launch {
            settings.chatDocStyle.collectLatest { on ->
                _ui.value = _ui.value.copy(docStyle = on)
            }
        }
        viewModelScope.launch {
            settings.enterToSend.collectLatest { on ->
                _ui.value = _ui.value.copy(enterToSend = on)
            }
        }
        viewModelScope.launch {
            settings.reduceMotion.collectLatest { on ->
                _ui.value = _ui.value.copy(reduceMotion = on)
            }
        }
        viewModelScope.launch {
            container.thermalMonitor.celsius.collectLatest { c ->
                _ui.value = _ui.value.copy(thermalCelsius = c)
            }
        }
        viewModelScope.launch {
            container.thermalMonitor.skinCelsius.collectLatest { c ->
                _ui.value = _ui.value.copy(skinCelsius = c)
            }
        }
        viewModelScope.launch {
            engine.stats.collectLatest { s ->
                _ui.value = _ui.value.copy(
                    tokensPerSecond = s.tokensPerSecond,
                    thermalPercent = s.thermalPercent,
                    // 云端模式不显示本地 CPU/GPU 后端标签
                    backendName = if (engine.isCloudActive()) "云端" else s.backend.displayNameZh,
                    contextUsed = s.contextUsed,
                    contextMax = s.contextMax
                )
            }
        }
        viewModelScope.launch {
            engine.state.collectLatest { st ->
                val path = st.modelPath
                val cloud = path?.startsWith("cloud") == true
                _ui.value = _ui.value.copy(
                    hasModel = st.loaded,
                    backendName = if (cloud) "云端" else _ui.value.backendName,
                    modelName = if (cloud) {
                        path.orEmpty().substringAfter("cloud:", "").ifBlank { "云端模型" }
                    } else {
                        path?.substringAfterLast('/') ?: ""
                    },
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
        viewModelScope.launch {
            toolInvocationViewModel.pendingChoice.collectLatest { pending ->
                _ui.value = _ui.value.copy(pendingUserChoice = pending)
            }
        }
    }

    fun loadSession(sessionId: Long) {
        // 切换会话时清掉上一个会话遗留的工具卡片
        toolInvocationViewModel.clearHistory()
        viewModelScope.launch {
            if (sessionId == 0L) {
                _ui.value = _ui.value.copy(messages = emptyList(), sessionId = 0, sessionTitle = "")
                return@launch
            }
            val session = chatRepo.getSession(sessionId)
            val msgs = chatRepo.getMessages(sessionId)
            // 角色扮演会话：把当时绑定的角色 / 身份一起恢复，保证历史会话也能继续演
            val mode = session?.mode ?: "CHAT"
            val cid = session?.characterId
            val pid = session?.personaId
            val character = if (mode == MODE_ROLEPLAY && cid != null) {
                container.roleplayRepository.getCharacter(cid)
            } else null
            val persona = if (mode == MODE_ROLEPLAY && pid != null) {
                container.roleplayRepository.getPersona(pid)
            } else null
            _ui.value = _ui.value.copy(
                sessionId = sessionId,
                sessionTitle = session?.title ?: "",
                messages = msgs,
                mode = mode,
                characterName = character?.name.orEmpty(),
                characterEmoji = character?.emoji.orEmpty(),
                characterSetting = character?.effectiveSystemPrompt().orEmpty(),
                characterGreeting = character?.greeting.orEmpty(),
                personaName = persona?.name.orEmpty(),
                personaDescription = persona?.description.orEmpty()
            )
            LogSystem.info(
                "aibox.ui.chat",
                "ChatVM: loadSession id=$sessionId msgs=${msgs.size} mode=$mode"
            )
        }
    }

    /**
     * 以角色扮演模式新建会话。
     *
     * 会话创建时就固定 `characterId` / `personaId`，之后即使角色被编辑或删除，
     * 这条历史会话仍能按创建时的设定继续（删除角色则退化为普通对话）。
     */
    fun startRoleplay(characterId: Long, personaId: Long) {
        toolInvocationViewModel.clearHistory()
        viewModelScope.launch {
            val repo = container.roleplayRepository
            val c = repo.getCharacter(characterId)
            if (c == null) {
                _ui.value = _ui.value.copy(errorMessage = "角色不存在，可能已被删除")
                return@launch
            }
            val p = if (personaId > 0) repo.getPersona(personaId) else null
            val id = chatRepo.createSession(
                title = c.name,
                modelPath = engine.state.value.modelPath,
                mode = MODE_ROLEPLAY,
                characterId = c.id,
                personaId = p?.id
            )
            repo.touchCharacter(c.id)
            p?.let { repo.touchPersona(it.id) }
            _ui.value = _ui.value.copy(
                sessionId = id,
                sessionTitle = c.name,
                messages = emptyList(),
                streaming = false,
                currentStream = "",
                streamParts = emptyList(),
                errorMessage = null,
                mode = MODE_ROLEPLAY,
                characterName = c.name,
                characterEmoji = c.emoji,
                characterSetting = c.effectiveSystemPrompt(),
                characterGreeting = c.greeting,
                personaName = p?.name.orEmpty(),
                personaDescription = p?.description.orEmpty()
            )
            LogSystem.info(
                "aibox.ui.chat",
                "ChatVM: startRoleplay cid=$characterId pid=$personaId sid=$id"
            )
        }
    }

    /** 退出角色扮演，回到空白普通会话；历史会话仍保留在历史列表里。 */
    fun resetToChat() {
        toolInvocationViewModel.clearHistory()
        _ui.value = _ui.value.copy(
            sessionId = 0,
            sessionTitle = "",
            messages = emptyList(),
            streaming = false,
            currentStream = "",
            streamParts = emptyList(),
            errorMessage = null,
            mode = "CHAT",
            characterName = "",
            characterEmoji = "",
            characterSetting = "",
            characterGreeting = "",
            personaName = "",
            personaDescription = ""
        )
    }

    fun newSession(onCreated: (Long) -> Unit = {}) {
        toolInvocationViewModel.clearHistory()
        viewModelScope.launch {
            val modelPath = engine.state.value.modelPath
            val title = "新话题 - ${System.currentTimeMillis() % 100000}"
            val id = chatRepo.createSession(title, modelPath)
            _ui.value = _ui.value.copy(
                sessionId = id,
                sessionTitle = title,
                messages = emptyList(),
                mode = "CHAT",
                characterName = "",
                characterEmoji = "",
                characterSetting = "",
                characterGreeting = "",
                personaName = "",
                personaDescription = ""
            )
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
                val defaultTitle = text.take(20).ifBlank { attachments.firstOrNull()?.name ?: "新话题" }
                val isCloud = runCatching { engine.isCloudActive() }.getOrDefault(false)
                sid = chatRepo.createSession(defaultTitle, modelPath)
                _ui.value = _ui.value.copy(sessionId = sid, sessionTitle = defaultTitle)
                // 云端 + 全局开关时才尝试 AI 命名：仅首次建会话触发，失败降级，
                // 且只在标题仍等于默认值（用户没手动改名）时写回，绝不覆盖已有标题。
                if (isCloud && runCatching { settings.aiAutoTitle.first() }.getOrDefault(true)) {
                    maybeAutoTitle(sid, defaultTitle, text)
                }
                continueSend(text, sid, attachments)
            }
        } else {
            continueSend(text, sid, attachments)
        }
    }

    /**
     * 云端首次建会话后用模型生成标题。
     *
     * 在独立协程里异步跑，不阻塞本轮回复；拿到候选后再次核对：
     * 会话确实还叫我们刚写入的默认标题才覆盖——这样用户若在这几百毫秒里
     * 手动改了名，或历史会话被重新打开，都不会被 AI 悄悄改掉标题。
     */
    private fun maybeAutoTitle(sessionId: Long, defaultTitle: String, firstUserText: String) {
        inferenceScope.launch {
            runCatching { container.cloudChatService.generateTitle(firstUserText) }
                .onFailure { LogSystem.warn("aibox.chat", "AI 自动标题失败，保留默认标题：${it.message}") }
                .getOrNull()
                ?.let { generated ->
                    runCatching {
                        val current = chatRepo.getSession(sessionId)?.title
                        if (current == defaultTitle && generated.isNotBlank()) {
                            chatRepo.updateSessionTitle(sessionId, generated)
                            if (_ui.value.sessionId == sessionId) {
                                _ui.value = _ui.value.copy(sessionTitle = generated)
                            }
                            LogSystem.info("aibox.chat", "AI 自动标题：$defaultTitle → $generated")
                        }
                    }.onFailure { LogSystem.warn("aibox.chat", "写回 AI 标题失败：${it.message}") }
                }
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

        // 新一轮开始：清掉上一轮遗留的工具调用卡片（进行中的会保留）
        toolInvocationViewModel.clearHistory()

        inferenceScope.launch {
            // 用户消息先落库并等待完成，再做推理。旧实现用独立 launch fire-and-forget，
            // 进程在 launch 执行前被回收时这条消息就永久丢失（"对话随时间消失"的主因之一）。
            // 现在与推理在同一协程顺序执行：写入完成后才 generate，保证用户消息必已持久化。
            // 用 inferenceScope（应用级）而非 viewModelScope：切后台导致页面销毁时，
            // 本轮生成不会被取消，回复生成完后继续落库，不丢。
            runCatching { chatRepo.addMessage(sid, msg) }
                .onSuccess { newId -> setPersistedId(msg.ts, msg.role, msg.content, newId) }
                .onFailure { LogSystem.error("aibox.ui.chat", "ChatVM: persist user msg failed", it) }

            val gen = generate(sid, visibleMessages())
            // 空回复不落库、不渲染：之前会生成一条 content="" 的助手消息，
            // 在气泡模式下表现为“空气泡”，用户只能继续发「？」追问。
            if (gen.text.isBlank()) {
                _ui.value = _ui.value.copy(
                    streaming = false,
                    currentStream = "",
                    streamParts = emptyList(),
                    errorMessage = gen.error
                        ?: "模型本轮没有产生可见文本（可能立即结束，或只调用了工具）。可以重试一次。"
                )
                LogSystem.warn("aibox.ui.chat", "ChatViewModel: empty assistant output, skipped")
                return@launch
            }
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
            // 助手消息直接 await 落库（去掉嵌套 launch）：进程在推理结束后被回收时，
            // 这条回复也已写入 DB。失败只记日志、不回滚 UI，避免影响已展示的内容。
            runCatching { chatRepo.addMessage(sid, assistant) }
                .onSuccess { newId -> setPersistedId(assistant.ts, assistant.role, assistant.content, newId) }
                .onFailure { LogSystem.error("aibox.ui.chat", "ChatVM: persist assistant msg failed", it) }
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
        // 角色扮演时不注入工具协议：工具 JSON / 能力说明会直接破坏人设。
        val roleplay = _ui.value.mode == MODE_ROLEPLAY
        val isCloud = runCatching { engine.isCloudActive() }.getOrDefault(false)
        val tools = if (isCloud || roleplay) emptyList() else runCatching {
            container.toolRegistry.getActiveToolsForLLM()
        }.onFailure { LogSystem.warn("aibox.ui.chat", "读取工具列表失败: ${it.message}") }
            .getOrDefault(emptyList())
        val toolPrompt = if (roleplay) "" else buildToolPrompt(tools)

        val conv = history.toMutableList()
        val parts = mutableListOf<MsgPart>()
        val rawAll = StringBuilder()
        var firstTokMs = 0L
        var tokCount = 0
        var error: String? = null
        var rounds = 0

        // 当前轮正文在整段显示文本里的起始下标；emit 用它把「已完成轮次」与「本轮正在生成的正文」切开，
        // 只对后者重新 parseTimeline，避免把上一轮的工具卡/思考块重复解析。
        var baseLen = 0
        val reduceMotion = _ui.value.reduceMotion
        // 打字机缓冲：非减弱动态时启用。token 从推理线程写入 target（@Volatile），
        // emit 在 Main 作用域按固定节奏把「已揭示前缀」推给 UI，把 NPU 上顿挫的出字铺平。
        // 云端直出：网络往返本身已有延迟，再叠 20ms/3 字的打字机会让界面状态明显落后于实际输出；
        // 且云端常常整段返回，打字机逐字吐反而更慢。本地 NPU 才需要缓冲抹平顿挫。
        val typewriter: StreamTypewriter? = if (!reduceMotion && !isCloud) {
            StreamTypewriter(
                scope = inferenceScope,
                emit = { revealed ->
                    val current = if (revealed.length > baseLen) revealed.substring(baseLen) else ""
                    _ui.value = _ui.value.copy(
                        currentStream = revealed,
                        // 工具卡只由 parts 里手动维护的 toolPart 表达（带准确状态/结果）。
                        // 打字机逐字 revealed 时对 aibox:tool 块的二次解析会产出重复的、
                        // 无结果的 tool part，与之竞争覆盖导致状态来回跳，这里直接滤掉。
                        // 本地路径的工具卡由下方手动 toolPart 维护，这里过滤掉二次解析的重复；
                        // 云端路径的工具卡是 CloudChatService 通过 marker 主动推的，必须保留，
                        // 否则「回答过程中看不到工具调用」。
                        streamParts = parts + parseTimeline(current)
                            .let { if (isCloud) it else it.filterNot { p -> p.kind == MsgPart.KIND_TOOL } }
                    )
                }
            )
        } else null
        typewriter?.start()

        try {
            while (rounds <= MAX_TOOL_ROUNDS) {
                val turn = StringBuilder()
                val roundBase = rawAll.toString()
                baseLen = roundBase.length
                typewriter?.reset(baseLen)
                // 刷新空闲计时：省电档靠 idleUnloadTimeout 在"真的没人用"时卸载模型省电。
                // 若推理路径不刷新 lastActivityAt，用户即使正在聊天，也会被误判为空闲，
                // 模型被卸载后下次发消息又要重新 mmap/预热 —— 既变慢又更耗电，与省电目标相反。
                // 每轮（含工具调用的多轮）都刷新一次。
                runCatching { container.optimizationManager.recordActivity() }
                engine.completionChat(
                    messages = buildChatMessages(conv, toolPrompt),
                    thinkingMode = thinkingEnabled,
                    onToken = { tok ->
                        if (firstTokMs == 0L) firstTokMs = System.currentTimeMillis()
                        tokCount++
                        turn.append(tok)
                        if (typewriter != null) {
                            // 打字机模式：只更新目标缓冲，UI 由独立协程匀速推进。
                            // 注意 target 是「已揭示前缀」的全量来源，必须包含已完成轮次前缀。
                            typewriter.target = roundBase + turn.toString()
                        } else {
                            // 云端 / 减弱动态效果：token 直出。
                            // 不再按时间合批（原来 ~33ms 才推一次）：工具执行那几秒没有新 token，
                            // 合批窗口会把「最后一批正文 + 工具标记」吞掉，界面停在旧快照
                            // （"我先取一次…"），直到整轮结束才一次性补全 —— 这就是吞字/卡顿的根因。
                            // 改为每个 token 都刷新到最新，重组风暴由渲染层的 conflate 节流兜底。
                            _ui.value = _ui.value.copy(
                                currentStream = roundBase + turn,
                                streamParts = parts + parseTimeline(turn.toString())
                                    .let { if (isCloud) it else it.filterNot { p -> p.kind == MsgPart.KIND_TOOL } }
                            )
                        }
                    },
                    onStop = { reason ->
                        if (reason == "decode_failed" || reason == "callback_error" || reason.startsWith("exception")) {
                            error = "推理中断：$reason"
                        }
                    }
                )
                // 本轮生成结束：先把打字机缓冲区剩余字符一次性推完，确保 shown 追上 target，
                // 否则随后写入的完整状态会被协程残留的中间态覆盖，出现文字回退/闪烁。
                typewriter?.flush()
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
                // 让 Compose 真正渲染出「正在调用」这一帧再执行工具。
                // generate 跑在 Main.immediate，若同步执行工具（计算器/日期/GPS 等），
                // Main 被独占到 running=false 落到 _ui 为止，中间态永远不会被重组观察到——
                // 表现出来就是「第一次工具卡在正在调用，直到回答结束才一次性全刷出来」。
                // yield 把主线程让给一帧合成；工具体本身切到 IO 执行，避免慢工具继续占 Main。
                kotlinx.coroutines.yield()
                val outcome = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    toolInvocationViewModel.executeAwait(
                        toolId = call.name,
                        params = call.arguments,
                        sessionId = "chat-$sid",
                        deviceInfo = buildDeviceInfo()
                    )
                }
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
        } finally {
            // 无论正常结束、工具多轮 break 还是异常，都要停掉打字机协程，避免泄漏 / 残留覆盖。
            typewriter?.stop()
        }

        val text = parts.filter { it.kind == MsgPart.KIND_TEXT && it.text.isNotBlank() }
            .joinToString("\n\n") { it.text.trim() }
            .trim()
            .ifBlank {
                // 兜底：只有没有正文 part 时才回退到原始输出，并且去掉思考块与工具代码块，
                // 否则「只调用了工具」的回合会把 JSON 当成正文塞进气泡。
                rawAll.toString()
                    .replace(Regex("<think>[\\s\\S]*?</think>"), "")
                    .replace(Regex("```aibox:tool[\\s\\S]*?```"), "")
                    .replace(Regex("```aibox:tool[\\s\\S]*$"), "")
                    .trim()
            }
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
        // 只对「当前可见分支」的最后一条助手回复重新生成，
        // 否则会把其他用户版本下的隐藏回复也带进上下文
        val visible = visibleMessages()
        val lastVisibleIndex = visible.indexOfLast { it.role == "assistant" }
        if (lastVisibleIndex < 0) return
        val last = visible[lastVisibleIndex]
        val history = visible.take(lastVisibleIndex)
        val remaining = _ui.value.messages.filterNot { it.id == last.id && it.ts == last.ts }
        // 先移除旧回复，只显示新生成的流式内容；完成后把新版本追加回去（旧内容在 versions 里）
        _ui.value = _ui.value.copy(
            messages = remaining,
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

    /**
     * 从指定的助手消息分叉出一个新分支（聊天分支功能）。
     *
     * 与 [regenerate] 的区别：regenerate 只能重生成最后一条；本方法可对会话里
     * 【任意一条助手消息】重生成。旧回复作为 [ChatMessage.versions] 的一版保留，
     * 新回复追加为最后一版，消息下方版本切换器（‹ n/m ›）即可在各分支间切换。
     * 上下文只取该消息之前的可见消息，不包含它之后的后续对话——这正是“从此处
     * 重新分支”的语义。用户消息分支由 [editMessage] + [switchVersion] 负责。
     */
    fun regenerateAt(target: ChatMessage) {
        if (_ui.value.streaming) return
        if (target.role != "assistant") return
        val full = _ui.value.messages
        val targetIdx = full.indexOfFirst { it.id == target.id && it.ts == target.ts }
        if (targetIdx < 0) return

        // 只在该助手消息之前的可见消息作为上下文，重建一个分叉点
        val visible = visibleMessages(full)
        val vIdx = visible.indexOfFirst { it.id == target.id && it.ts == target.ts }
        val history = if (vIdx >= 0) visible.take(vIdx) else visible.take(targetIdx)
        // 从列表里移除这条旧回复（连同其后属于同一轮的重复回复），流式展示新回复
        val remaining = full.filterNot { it.id == target.id && it.ts == target.ts }
        _ui.value = _ui.value.copy(
            messages = remaining,
            streaming = true,
            currentStream = "",
            streamParts = emptyList(),
            errorMessage = null
        )
        LogSystem.info("aibox.ui.chat", "ChatVM: regenerateAt id=${target.id}")

        viewModelScope.launch {
            val gen = generate(_ui.value.sessionId, history)
            if (gen.text.isBlank()) {
                // 生成失败：把原消息放回，避免分支点凭空消失
                _ui.value = _ui.value.copy(
                    messages = full,
                    streaming = false,
                    errorMessage = gen.error ?: "模型本轮没有产生可见文本，分支已保留原样。"
                )
                return@launch
            }
            val base = if (target.versions.isEmpty()) listOf(target.content) else target.versions
            val versions = (base + gen.text).distinct()
            val updated = target.copy(
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
            // 不要清空 parts：这些时间线段（思考 / 工具调用结果）是用户历史的一部分。
            // 旧实现置空 parts 再 persistUpdate 写回 DB，会把该消息的工具调用卡片永久抹掉，
            // 表现为"工具调用历史随时间消失"。保留原 parts，即使与切换后的正文略有出入，
            // 也远好于不可逆的数据丢失。
            parts = message.parts
        )
        replaceMessage(message, updated)
        persistUpdate(updated)
    }

    /**
     * 编辑用户消息：旧内容保留为用户消息的历史版本，并以编辑后的内容重新发送。
     *
     * 版本关系：
     * - 用户消息 2/2 = 重新发送后的内容；
     * - 助手回复 2/2 = 这条重发内容下的第 2 次重新生成（由 [regenerate] 管理）；
     * - 不同用户版本各自保留自己的助手回复分支，切回旧版本时显示旧分支。
     *
     * 编辑最后一条用户消息时保留旧回复分支；编辑更早的消息时仍会截断后续内容
     * （后续对话依赖旧版提问，无法复用）。
     */
    fun editMessage(message: ChatMessage, newText: String) {
        if (_ui.value.streaming) return
        val text = newText.trim()
        if (text.isEmpty() || text == message.content) return
        val sid = _ui.value.sessionId
        if (sid == 0L) return
        val full = _ui.value.messages
        val idx = full.indexOfFirst { it.id == message.id && it.ts == message.ts }
        if (idx < 0) return

        val visible = visibleMessages(full)
        val isLastUser = visible.lastOrNull { it.role == "user" }?.let {
            it.id == message.id && it.ts == message.ts
        } == true

        val oldVersion = if (message.versions.isEmpty()) 0 else message.versionIndex
        val base = if (message.versions.isEmpty()) listOf(message.content) else message.versions
        val versions = base + text
        val newVersion = versions.lastIndex
        val updated = message.copy(
            content = text,
            versions = versions,
            versionIndex = newVersion,
            parts = emptyList()
        )

        val history: List<ChatMessage>
        if (isLastUser) {
            // 当前分支的旧助手回复 → 绑定到旧用户版本，先隐藏但保留
            var i = idx + 1
            while (i < full.size && full[i].role == "assistant") {
                val oldReply = full[i]
                val tagged = oldReply.copy(parentUserVersion = oldVersion)
                if (oldReply.parentUserVersion != oldVersion) {
                    replaceMessage(oldReply, tagged)
                    persistUpdate(tagged)
                }
                i++
            }
            val vIdx = visible.indexOfFirst { it.id == message.id && it.ts == message.ts }
            history = (if (vIdx >= 0) visible.take(vIdx) else visible) + updated
            _ui.value = _ui.value.copy(messages = _ui.value.messages.map {
                if (it.id == message.id && it.ts == message.ts) updated else it
            })
        } else {
            // 编辑更早的消息：后续内容全部失效，截断后重发
            full.drop(idx + 1).forEach { old ->
                if (old.id > 0) {
                    viewModelScope.launch {
                        runCatching { chatRepo.deleteMessage(old.id) }
                            .onFailure { LogSystem.warn("aibox.ui.chat", "delete tail msg failed: ${it.message}") }
                    }
                }
            }
            history = full.take(idx) + updated
            _ui.value = _ui.value.copy(messages = history)
        }

        toolInvocationViewModel.clearHistory()
        _ui.value = _ui.value.copy(
            streaming = true,
            currentStream = "",
            streamParts = emptyList(),
            errorMessage = null
        )

        viewModelScope.launch {
            runCatching {
                if (updated.id > 0) chatRepo.updateMessage(updated)
                else chatRepo.addMessage(sid, updated)
            }.onFailure { LogSystem.error("aibox.ui.chat", "ChatVM: persist edited msg failed", it) }

            val gen = generate(sid, history)
            if (gen.text.isBlank()) {
                _ui.value = _ui.value.copy(
                    streaming = false,
                    currentStream = "",
                    streamParts = emptyList(),
                    errorMessage = gen.error
                        ?: "模型本轮没有产生可见文本。可以重试一次。"
                )
                return@launch
            }
            val assistant = ChatMessage(
                role = "assistant",
                content = gen.text,
                sessionId = sid,
                tokensPerSecond = gen.tps,
                parts = gen.parts,
                versions = listOf(gen.text),
                versionIndex = 0,
                parentUserVersion = newVersion
            )
            _ui.value = _ui.value.copy(
                messages = _ui.value.messages + assistant,
                streaming = false,
                currentStream = "",
                streamParts = emptyList(),
                errorMessage = gen.error
            )
            runCatching { chatRepo.addMessage(sid, assistant) }
                .onSuccess { newId -> setPersistedId(assistant.ts, assistant.role, assistant.content, newId) }
                .onFailure { LogSystem.error("aibox.ui.chat", "ChatVM: persist edited assistant failed", it) }
        }
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
                    .onSuccess { newId -> setPersistedId(message.ts, message.role, message.content, newId) }
                    .onFailure { LogSystem.error("aibox.ui.chat", "ChatVM: insert msg failed", it) }
            }
        }
    }

    /**
     * 消息落库后回填 id，后续编辑 / 切换版本才能 update 到同一行。
     * 同时匹配 content，降低「同一毫秒、同一角色连发两条」时回填错行的概率。
     * 只回填第一条匹配的 id=0 消息，避免两条消息被赋成同一个数据库 id。
     */
    private fun setPersistedId(ts: Long, role: String, content: String, id: Long) {
        if (id <= 0) return
        var filled = false
        _ui.value = _ui.value.copy(
            messages = _ui.value.messages.map { m ->
                if (!filled && m.id == 0L && m.ts == ts && m.role == role && m.content == content) {
                    filled = true
                    m.copy(id = id)
                } else m
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

    /** user_choice：提交用户的选择 / 回答。 */
    fun submitUserChoice(answer: com.aibox.domain.tool.UserChoiceAnswer) =
        toolInvocationViewModel.submitChoice(answer)

    /** user_choice：用户取消回答。 */
    fun cancelUserChoice() = toolInvocationViewModel.cancelChoice()

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
     * 角色扮演的 system prompt；非角色扮演返回空串。
     *
     * 只拼角色设定 + 用户身份 + 一段“不出戏”的约束，长度控制在几行：
     * 角色设定太长会挤占上下文，反而让小模型跑偏。
     */
    private fun buildRoleplayPrompt(): String {
        val s = _ui.value
        if (s.mode != MODE_ROLEPLAY || s.characterName.isBlank()) return ""
        return buildString {
            append("现在进行角色扮演。你扮演「${s.characterName}」。")
            if (s.characterSetting.isNotBlank()) {
                append("\n角色设定：")
                append(s.characterSetting.trim())
            }
            if (s.personaName.isNotBlank()) {
                append("\n用户身份：")
                append(s.personaName)
                if (s.personaDescription.isNotBlank()) {
                    append("（")
                    append(s.personaDescription.trim())
                    append("）")
                }
            }
            append("\n始终保持角色口吻与设定，不要提及自己是大模型、AI 或提示词；")
            append("除用户明确要求，不要跳出角色。回复自然、简洁，不要替用户说话。")
        }
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
            // 本地模型没有 native 级档位（只有思考开/关），medium/high 全靠提示词区分，
            // 因此默认 medium 必须有引导，否则“档位”在本地形同虚设。
            "medium" -> "请先进行适度的分步思考，再给出清晰结论，兼顾充分与简洁。"
            "high" -> "请先进行深入、完整的推理，再给出清晰结论。"
            else -> ""
        }
        val sys = listOf(
            buildRoleplayPrompt(),
            systemPrompt.trim(),
            levelHint,
            UI_CAPABILITY_HINT,
            if (_ui.value.choicesBeta) CHOICES_BETA_HINT else "",
            if (_ui.value.richAnswer) RICH_ANSWER_HINT else "",
            toolPrompt
        )
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

    override fun onCleared() {
        // 页面销毁时撤销注册的 UI 交互回调，避免云端工具循环持有已销毁的 ViewModel。
        if (UiInteractionBridge.choiceHandler != null || UiInteractionBridge.confirmHandler != null) {
            UiInteractionBridge.clear()
        }
        super.onCleared()
    }

    private companion object {
        /** 本地模型工具循环上限，避免模型反复调用同一工具。 */
        const val MAX_TOOL_ROUNDS = 4

        /** 角色扮演会话模式标识（写入 sessions.mode）。 */
        const val MODE_ROLEPLAY = "ROLEPLAY"
    }
}
