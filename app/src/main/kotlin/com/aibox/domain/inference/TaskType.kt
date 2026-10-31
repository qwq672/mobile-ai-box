package com.aibox.domain.inference

/**
 * 推理任务类型枚举（v2）。
 *
 * - 覆盖文本 / 图像 / 音频 / 多模态四类任务。
 * - 与 [com.aibox.domain.inference.BackendCapabilities.supportedTasks] 配合，
 *   用于 [BackendManagerV2] 在多后端间路由请求。
 *
 * 设计原则：每个枚举值描述"做什么"，不描述"用什么后端做"。
 * 后端能力声明由 [BackendCapabilities] 负责，路由由 [BackendManagerV2] 负责。
 */
enum class TaskType {
    // === 文本 ===
    /** 文本生成（LLM 续写 / 对话）。 */
    TEXT_GENERATION,
    /** 文本分类（情感 / 意图 / 垂类）。 */
    TEXT_CLASSIFICATION,
    /** 文本向量化（embedding）。 */
    EMBEDDING,

    // === 图像 ===
    /** 图像生成（diffusion / 文生图）。 */
    IMAGE_GENERATION,
    /** 图像编辑（inpaint / instruction edit）。 */
    IMAGE_EDITING,
    /** 图像理解（VLM 看图说话、OCR、检测）。 */
    IMAGE_UNDERSTANDING,

    // === 音频 ===
    /** 音频转写（ASR / 语音转文字）。 */
    AUDIO_TRANSCRIPTION,
    /** 音频生成（TTS / 语音合成）。 */
    AUDIO_GENERATION,

    // === 多模态 ===
    /** 多模态理解（图文音联合输入，输出文本 / 结构化结果）。 */
    MULTIMODAL_UNDERSTANDING,
    /** 多模态生成（任意模态输入，多模态输出，如带配图的回答）。 */
    MULTIMODAL_GENERATION
}

/**
 * 输入 / 输出模态枚举（v2）。
 *
 * 与 [TaskType] 正交：一个任务通常 consume 一组输入模态、produce 一组输出模态。
 * 用于 [BackendCapabilities] 声明后端能处理的输入 / 输出模态集合。
 */
enum class Modality {
    TEXT,
    IMAGE,
    AUDIO,
    VIDEO
}
