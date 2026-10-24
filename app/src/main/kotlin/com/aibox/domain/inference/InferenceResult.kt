package com.aibox.domain.inference

/**
 * 通用推理结果（v2）。
 *
 * 与 [InferenceRequest] / [ModalityInput] 对称：一次 [InferenceRequest] 产生一个 [InferenceResult]
 * （非流式）或一组 [StreamChunk]（流式）。
 *
 * 后端按任务类型返回对应子类：
 * - 文本生成 / 分类 / 多模态理解 → [Text] 或 [Multimodal]
 * - 图像生成 / 编辑 → [Image]
 * - 音频生成 → [Audio]
 * - Embedding → [Embedding]
 * - 流式 → [StreamChunk]
 */
sealed class InferenceResult {
    /** 文本结果（生成 / 分类 / 理解任务的输出）。 */
    data class Text(val content: String) : InferenceResult()

    /** 图像结果（生成的图像 URI）。 */
    data class Image(val uri: String) : InferenceResult()

    /** 音频结果（合成的音频 URI）。 */
    data class Audio(val uri: String) : InferenceResult()

    /**
     * 多模态结果：包含若干 [ModalityOutput] 部分，按顺序展示。
     * 例如 VLM 回答 "这张图是 [img:graph.png]，描述如下：..."。
     */
    data class Multimodal(val parts: List<ModalityOutput>) : InferenceResult()

    /**
     * 向量嵌入结果。
     *
     * 注：data class 中 FloatArray 默认 equals 不比内容，这里显式重写以保证
     * `Embedding(a) == Embedding(a)` 在内容相同时返回 true。
     */
    data class Embedding(val vector: FloatArray) : InferenceResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Embedding) return false
            return vector.contentEquals(other.vector)
        }
        override fun hashCode(): Int = vector.contentHashCode()
    }

    /**
     * 流式输出的一块。
     *
     * @property partial 当前块的输出（可以是 Text / Image / Audio 子类）。
     * @property done 是否是最后一块；true 之后不应再有该 taskId 的 chunk。
     */
    data class StreamChunk(val partial: ModalityOutput, val done: Boolean) : InferenceResult()
}

/**
 * 多模态输出的一个组成部分（v2）。
 *
 * 用于 [InferenceResult.Multimodal] 的 parts 列表，以及 [InferenceResult.StreamChunk] 的 partial 字段。
 * 与 [ModalityInput] 对称但语义不同：ModalityInput 是请求侧"喂什么"，ModalityOutput 是响应侧"产出什么"。
 */
sealed class ModalityOutput {
    /** 文本输出片段（流式 token 或完整文本）。 */
    data class Text(val content: String) : ModalityOutput()

    /** 图像输出（生成 / 编辑后保存的图像 URI）。 */
    data class Image(val uri: String) : ModalityOutput()

    /** 音频输出（合成后保存的音频 URI）。 */
    data class Audio(val uri: String) : ModalityOutput()
}
