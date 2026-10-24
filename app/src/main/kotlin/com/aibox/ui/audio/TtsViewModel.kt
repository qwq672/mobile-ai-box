package com.aibox.ui.audio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibox.domain.audio.AudioProviderRegistry
import com.aibox.domain.audio.TtsConfig
import com.aibox.domain.audio.TtsProgress
import com.aibox.domain.audio.TtsSession
import com.aibox.util.log.LogSystem
import com.aibox.util.outcome.Outcome
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 语音合成页 ViewModel（D2）。
 *
 * 职责：
 * - 持有当前选中的 [com.aibox.domain.audio.TtsProvider]（默认 system）。
 * - 暴露 [UiState]：text / language / speechRate / pitch / isPlaying / error。
 * - [play] / [stop] 控制合成会话；stop 幂等。
 * - 收集 [TtsSession.progress] 自动更新 isPlaying；Done / Failed 事件 reset 状态。
 *
 * 错误约定：
 * - 文本空 → ui.error = "请输入要合成的文本"。
 * - 设备不可用 → ui.error = "当前设备不支持语音合成"。
 * - [TtsProvider.startSynthesis] 失败 → ui.error = reason（如 "TTS 引擎未就绪，请稍后重试"）。
 */
class TtsViewModel(
    private val registry: AudioProviderRegistry
) : ViewModel() {

    data class UiState(
        val providerId: String = "",
        val text: String = "",
        val language: String = "zh-CN",
        val speechRate: Float = 1.0f,
        val pitch: Float = 1.0f,
        val isPlaying: Boolean = false,
        val available: Boolean = true,
        val error: String? = null
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var session: TtsSession? = null
    private var collectJob: Job? = null

    init {
        val p = registry.getDefaultTtsProvider()
        if (p != null) {
            _ui.value = _ui.value.copy(providerId = p.id, available = p.isAvailable())
        } else {
            _ui.value = _ui.value.copy(available = false, error = "无系统 TTS 提供者")
        }
    }

    fun setText(v: String) { _ui.value = _ui.value.copy(text = v) }
    fun setLanguage(v: String) { _ui.value = _ui.value.copy(language = v) }
    fun setSpeechRate(v: Float) { _ui.value = _ui.value.copy(speechRate = v) }
    fun setPitch(v: Float) { _ui.value = _ui.value.copy(pitch = v) }

    fun play() {
        if (_ui.value.isPlaying) return
        val p = registry.getDefaultTtsProvider() ?: run {
            _ui.value = _ui.value.copy(error = "无系统 TTS 提供者")
            return
        }
        val text = _ui.value.text.trim()
        if (text.isEmpty()) {
            _ui.value = _ui.value.copy(error = "请输入要合成的文本")
            return
        }
        if (!p.isAvailable()) {
            _ui.value = _ui.value.copy(error = "当前设备不支持语音合成")
            return
        }
        _ui.value = _ui.value.copy(isPlaying = true, error = null)
        viewModelScope.launch {
            val cfg = TtsConfig(
                text = text,
                language = _ui.value.language,
                speechRate = _ui.value.speechRate,
                pitch = _ui.value.pitch
            )
            when (val r = p.startSynthesis(cfg)) {
                is Outcome.Success -> {
                    session = r.value
                    collectJob = viewModelScope.launch {
                        try {
                            r.value.progress.collect { progress ->
                                when (progress) {
                                    is TtsProgress.Started -> {}
                                    is TtsProgress.Done -> _ui.value = _ui.value.copy(isPlaying = false)
                                    is TtsProgress.Failed -> _ui.value = _ui.value.copy(
                                        isPlaying = false,
                                        error = progress.reason
                                    )
                                }
                            }
                        } finally {
                            _ui.value = _ui.value.copy(isPlaying = false)
                            session = null
                        }
                    }
                }
                is Outcome.Failure -> {
                    LogSystem.error(TAG, "startSynthesis failed: ${r.reason}", r.cause)
                    _ui.value = _ui.value.copy(isPlaying = false, error = r.reason)
                }
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        session?.stop()
        session = null
        _ui.value = _ui.value.copy(isPlaying = false)
    }

    fun clearError() {
        _ui.value = _ui.value.copy(error = null)
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    companion object {
        private const val TAG = "aibox.ui.audio.tts"
    }
}
