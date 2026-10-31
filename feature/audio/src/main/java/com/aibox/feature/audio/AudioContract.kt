package com.aibox.feature.audio

/** TTS 任务 */
public data class TtsJob(
    public val id: String,
    public val text: String,
    public val voiceId: String = "default",
    public val speed: Float = 1.0f,
    public val pitch: Float = 1.0f,
    public val backendId: String? = null,
)

/** STT 任务 */
public data class SttJob(
    public val id: String,
    public val audioUri: String,
    public val languageHint: String = "zh-CN",
    public val backendId: String? = null,
)

public sealed interface AudioUiState {
    public data object Idle : AudioUiState
    public data class TtsGenerating(val partialText: String, val partialPath: String? = null) : AudioUiState
    public data class TtsCompleted(val path: String, val durationMs: Long) : AudioUiState
    public data class SttTranscribing(val partialText: String) : AudioUiState
    public data class SttCompleted(val text: String) : AudioUiState
    public data class Error(val message: String) : AudioUiState
}
