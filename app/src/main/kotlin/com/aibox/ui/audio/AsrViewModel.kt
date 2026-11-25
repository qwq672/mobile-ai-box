package com.aibox.ui.audio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibox.domain.audio.AsrConfig
import com.aibox.domain.audio.AsrSession
import com.aibox.domain.audio.AudioProviderRegistry
import com.aibox.util.log.LogSystem
import com.aibox.util.outcome.Outcome
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 语音识别页 ViewModel（D1）。
 *
 * 职责：
 * - 持有当前选中的 [com.aibox.domain.audio.AsrProvider]（默认 system）。
 * - 暴露 [UiState]：isListening / partialText / finalText / language / error。
 * - [start] / [stop] 控制识别会话；stop 幂等。
 * - 收集 [AsrSession.partialResults] 自动更新 finalText；Flow complete 时自动 reset isListening。
 *
 * 错误约定：
 * - provider 不存在 → ui.error = "无系统 ASR 提供者"。
 * - 设备不可用 → ui.error = "当前设备不支持语音识别"。
 * - [AsrProvider.startRecognition] 失败 → ui.error = reason。
 * - 识别中 onError → Flow complete，最后一次 partialText 保留为最终结果。
 */
class AsrViewModel(
    private val registry: AudioProviderRegistry
) : ViewModel() {

    data class UiState(
        val providerId: String = "",
        /** 当前选中的 provider 是否可用（模型/native 就绪）。 */
        val available: Boolean = true,
        val isListening: Boolean = false,
        val partialText: String = "",
        val finalText: String = "",
        val language: String = "zh-CN",
        val error: String? = null
    )

    /** 可选 provider 列表（id → 显示名），供设置页下拉选择。 */
    data class ProviderOption(val id: String, val displayName: String, val available: Boolean)
    private val _providers = MutableStateFlow<List<ProviderOption>>(emptyList())
    val providers: StateFlow<List<ProviderOption>> = _providers

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var session: AsrSession? = null
    private var collectJob: Job? = null

    init {
        refreshProviders()
    }

    /** 重新枚举 provider 列表，并保持当前选择有效。 */
    private fun refreshProviders() {
        val list = registry.listAsrProviders().map {
            ProviderOption(it.id, it.displayName, it.isAvailable())
        }
        _providers.value = list
        val current = _ui.value.providerId
        if (current.isEmpty() || list.none { it.id == current }) {
            val def = registry.getDefaultAsrProvider()
            _ui.value = _ui.value.copy(
                providerId = def?.id ?: "",
                available = def?.isAvailable() ?: false
            )
        }
    }

    /** 用户切换 provider；不可用时给出友好提示但允许选中（便于查看原因）。 */
    fun selectProvider(id: String) {
        stop()
        val p = registry.getAsrProvider(id)
        _ui.value = _ui.value.copy(
            providerId = id,
            available = p?.isAvailable() ?: false,
            error = if (p?.isAvailable() == false) providerUnavailableHint(id) else null
        )
    }

    private fun providerUnavailableHint(id: String): String = when (id) {
        "onnx" -> "离线 ONNX ASR 未就绪：需 -Paibox.asr.sherpa=true 构建并把模型放到 App 内部 files/sherpa-asr"
        else -> "该语音识别后端在当前设备不可用"
    }

    fun setLanguage(lang: String) {
        _ui.value = _ui.value.copy(language = lang)
    }

    fun start() {
        if (_ui.value.isListening) return
        val p = registry.getAsrProvider(_ui.value.providerId)
            ?: registry.getDefaultAsrProvider()
            ?: run {
                _ui.value = _ui.value.copy(error = "无系统 ASR 提供者")
                return
            }
        if (!p.isAvailable()) {
            _ui.value = _ui.value.copy(error = providerUnavailableHint(p.id))
            return
        }
        _ui.value = _ui.value.copy(
            isListening = true,
            partialText = "",
            finalText = "",
            error = null
        )
        viewModelScope.launch {
            val cfg = AsrConfig(language = _ui.value.language)
            when (val r = p.startRecognition(cfg)) {
                is Outcome.Success -> {
                    session = r.value
                    collectJob = viewModelScope.launch {
                        try {
                            r.value.partialResults.collect { partial ->
                                _ui.value = _ui.value.copy(partialText = partial, finalText = partial)
                            }
                        } finally {
                            _ui.value = _ui.value.copy(isListening = false)
                            session = null
                        }
                    }
                }
                is Outcome.Failure -> {
                    LogSystem.error(TAG, "startRecognition failed: ${r.reason}", r.cause)
                    _ui.value = _ui.value.copy(isListening = false, error = r.reason)
                }
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        session?.stop()
        session = null
        _ui.value = _ui.value.copy(isListening = false)
    }

    fun clearText() {
        _ui.value = _ui.value.copy(partialText = "", finalText = "")
    }

    fun clearError() {
        _ui.value = _ui.value.copy(error = null)
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    companion object {
        private const val TAG = "aibox.ui.audio.asr"
    }
}
