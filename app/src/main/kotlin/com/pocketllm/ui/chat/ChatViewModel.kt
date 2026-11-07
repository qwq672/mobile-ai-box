package com.pocketllm.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketllm.PocketLLMApp
import com.pocketllm.data.model.ChatMessage
import com.pocketllm.data.repo.ChatRepository
import com.pocketllm.data.repo.SettingsRepository
import com.pocketllm.domain.inference.InferenceEngine
import com.pocketllm.infra.jni.NativeBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.pocketllm.util.logi
import com.pocketllm.util.loge

class ChatViewModel : ViewModel() {

    private val container get() = PocketLLMApp.instance.container
    private val engine: InferenceEngine get() = container.inferenceEngine
    private val settings: SettingsRepository get() = container.settingsRepository
    private val chatRepo: ChatRepository get() = container.chatRepository

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
        val sessionTitle: String = ""
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
            logi("ChatVM: loadSession id=$sessionId msgs=${msgs.size}")
        }
    }

    fun newSession(onCreated: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val modelPath = engine.state.value.modelPath
            val title = "新话题 - ${System.currentTimeMillis() % 100000}"
            val id = chatRepo.createSession(title, modelPath)
            _ui.value = _ui.value.copy(sessionId = id, sessionTitle = title, messages = emptyList())
            logi("ChatVM: newSession id=$id")
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
        logi("ChatViewModel: send ${text.length} chars (sid=$sid)")

        viewModelScope.launch {
            runCatching { chatRepo.addMessage(sid, msg) }
                .onFailure { loge("ChatVM: persist user msg failed", it) }
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
                        logi("ChatViewModel: completion stopped reason=$reason tps=$tps")
                        viewModelScope.launch {
                            runCatching { chatRepo.addMessage(sid, assistant) }
                                .onFailure { loge("ChatVM: persist assistant msg failed", it) }
                        }
                    }
                )
            } catch (t: Throwable) {
                loge("ChatViewModel: completion threw", t)
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
                    .onFailure { loge("ChatVM: delete msg failed", it) }
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
        logi("ChatViewModel: regenerate")
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
                        logi("ChatViewModel: regenerate stopped reason=$reason tps=$tps")
                        if (_ui.value.sessionId != 0L) {
                            viewModelScope.launch {
                                runCatching { chatRepo.addMessage(_ui.value.sessionId, assistant) }
                                    .onFailure { loge("ChatVM: persist regenerate msg failed", it) }
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                loge("ChatViewModel: regenerate threw", t)
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
