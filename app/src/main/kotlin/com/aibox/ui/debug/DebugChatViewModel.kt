package com.aibox.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibox.AIBoxApp
import com.aibox.data.repo.SettingsRepository
import com.aibox.domain.inference.InferenceEngine
import com.aibox.infra.jni.NativeBridge
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 调试聊天的一条消息（只存在内存里，不写数据库）。
 *
 * @property role user / assistant
 * @property content 原文（不做 Markdown 渲染，避免掩盖模型真实输出）
 * @property meta 助手消息的元信息：后端、t/s、token 数、stop reason、耗时
 */
data class DebugMsg(
    val role: String,
    val content: String,
    val meta: String = ""
)

/**
 * 纯净聊天状态。
 *
 * @property thinking 是否向模型传 thinkingMode（默认关，减少思考段干扰）
 * @property multiTurn 是否携带历史；关掉后每轮只发当前这一句
 * @property rawMode true = 走原始补全（完全不走 chat template），false = chat 模板
 * @property samplerInfo 加载时的采样参数快照（只展示）
 */
data class DebugUiState(
    val messages: List<DebugMsg> = emptyList(),
    val streaming: Boolean = false,
    val current: String = "",
    val thinking: Boolean = false,
    val multiTurn: Boolean = true,
    val rawMode: Boolean = false,
    val hasModel: Boolean = false,
    val modelName: String = "",
    val backendName: String = "CPU",
    val samplerInfo: String = "",
    val error: String? = null
)

/**
 * 纯净聊天 ViewModel。
 *
 * 刻意 **不注入任何东西**：
 * - 没有 system message（连用户的系统提示词也不带）
 * - 没有 UI 能力说明 / choices 协议
 * - 没有工具清单与工具调用循环
 * - 没有角色扮演设定
 *
 * 目的：给模型一个“裸”的对话环境，用来判断复读/幻觉来自模型本身，
 * 还是来自正式聊天页每轮附加的提示词与工具协议。
 */
class DebugChatViewModel : ViewModel() {

    private val container get() = AIBoxApp.instance.container
    private val engine: InferenceEngine get() = container.inferenceEngine
    private val settings: SettingsRepository get() = container.settingsRepository

    private val _ui = MutableStateFlow(DebugUiState())
    val ui: StateFlow<DebugUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            engine.state.collectLatest { st ->
                _ui.value = _ui.value.copy(
                    hasModel = st.loaded,
                    modelName = st.modelPath?.substringAfterLast('/') ?: ""
                )
            }
        }
        viewModelScope.launch {
            engine.stats.collectLatest { s ->
                _ui.value = _ui.value.copy(backendName = s.backend.displayNameZh)
            }
        }
        viewModelScope.launch {
            settings.config.collectLatest { c ->
                _ui.value = _ui.value.copy(
                    samplerInfo = "temp=${c.temperature} · top_k=${c.topK} · " +
                        "top_p=${c.topP} · repeat=${c.repeatPenalty} · kv=${c.kvQuant}（加载时生效）"
                )
            }
        }
    }

    fun setThinking(on: Boolean) {
        _ui.value = _ui.value.copy(thinking = on)
    }

    fun setMultiTurn(on: Boolean) {
        _ui.value = _ui.value.copy(multiTurn = on)
    }

    fun setRawMode(on: Boolean) {
        _ui.value = _ui.value.copy(rawMode = on)
    }

    fun dismissError() {
        _ui.value = _ui.value.copy(error = null)
    }

    fun clear() {
        if (_ui.value.streaming) return
        _ui.value = _ui.value.copy(messages = emptyList(), current = "", error = null)
    }

    fun stop() = engine.interrupt()

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _ui.value.streaming) return
        if (!_ui.value.hasModel) {
            _ui.value = _ui.value.copy(error = "尚未加载模型，请先到「模型」页加载")
            return
        }
        // 纯净测试必须走本地：云端模式下 completionChat 会被路由到云端，结果没有参考意义
        if (!_ui.value.rawMode && engine.isCloudActive()) {
            _ui.value = _ui.value.copy(
                error = "云端模式已开启：纯净聊天会走云端。请先在设置里关闭云端，或改开「原始补全」"
            )
            return
        }

        val thinking = _ui.value.thinking
        val multiTurn = _ui.value.multiTurn
        val rawMode = _ui.value.rawMode
        val backend = _ui.value.backendName

        _ui.value = _ui.value.copy(
            messages = _ui.value.messages + DebugMsg("user", prompt),
            current = "",
            streaming = true,
            error = null
        )

        viewModelScope.launch {
            val started = System.currentTimeMillis()
            var firstTokenAt = 0L
            var tokenCount = 0
            var lastUiPush = 0L
            var stopReason = ""
            val sb = StringBuilder()

            val onToken: (String) -> Unit = { piece ->
                if (firstTokenAt == 0L) firstTokenAt = System.currentTimeMillis()
                tokenCount++
                sb.append(piece)
                val now = System.currentTimeMillis()
                // 调试页也做合批，不然逐 token 重组会干扰发热/速度观察
                if (now - lastUiPush >= 33L) {
                    lastUiPush = now
                    _ui.value = _ui.value.copy(current = sb.toString())
                }
            }
            val onStop: (String) -> Unit = { reason -> stopReason = reason }

            try {
                if (rawMode) {
                    // 原始补全：不套 chat template，只把用户原话喂给模型
                    engine.completion(prompt, onToken, onStop)
                } else {
                    val context = if (multiTurn) {
                        _ui.value.messages.map { NativeBridge.ChatMsg(it.role, it.content) }
                    } else {
                        listOf(NativeBridge.ChatMsg("user", prompt))
                    }
                    engine.completionChat(context, thinking, onToken, onStop)
                }
            } catch (t: Throwable) {
                LogSystem.error("aibox.debug.chat", "debug send threw", t)
                stopReason = "exception: ${t.message ?: t.javaClass.simpleName}"
            }

            val ended = System.currentTimeMillis()
            val elapsed = (ended - started).coerceAtLeast(1L)
            val genMs = if (firstTokenAt > 0L) (ended - firstTokenAt).coerceAtLeast(1L) else 0L
            val tps = if (tokenCount > 0 && genMs > 0) tokenCount * 1000f / genMs else 0f
            val meta = buildString {
                append(backend)
                if (tps > 0f) append(" · %.1f t/s".format(tps))
                append(" · $tokenCount 段 · ${sb.length} 字 · ${elapsed}ms")
                if (stopReason.isNotBlank()) append(" · stop=$stopReason")
            }
            val failed = stopReason.contains("failed") ||
                stopReason == "no_model" ||
                stopReason.startsWith("exception")
            _ui.value = _ui.value.copy(
                messages = _ui.value.messages + DebugMsg(
                    role = "assistant",
                    content = sb.toString().ifEmpty { "（空输出）" },
                    meta = meta
                ),
                current = "",
                streaming = false,
                error = if (failed) "生成失败：$stopReason" else null
            )
        }
    }
}
