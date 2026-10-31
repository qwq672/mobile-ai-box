package com.aibox.feature.image

import com.aibox.core.inference.InferenceResult

/** 图像生成任务 */
public data class ImageGenJob(
    public val id: String,
    public val prompt: String,
    public val negativePrompt: String = "",
    public val width: Int = 512,
    public val height: Int = 512,
    public val steps: Int = 20,
    public val seed: Long = -1L, // -1 = random
    public val backendId: String? = null,
)

public sealed interface ImageUiState {
    public data object Idle : ImageUiState
    public data class Generating(val step: Int, val totalSteps: Int, val previewPath: String? = null) : ImageUiState
    public data class Completed(val imagePath: String, val meta: InferenceResult) : ImageUiState
    public data class Error(val message: String) : ImageUiState
}
