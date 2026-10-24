package com.aibox.litert.backend

/**
 * .tflite 模型类型识别结果。
 *
 * LiteRT-LM 当前支持两类模型:
 *  - [TEXT_ONLY]:纯文本 Gemma(2B / 7B Instruct)。
 *  - [MULTIMODAL]:Siglip 视觉编码器 + Gemma 文本解码器组合模型(看图说话)。
 *
 * 检测路径(在 [LiteRTBackend.detectModelType] 实现):
 *  1. 读取 .tflite 文件的 ModelMetadata(associated_files / name 字段)。
 *  2. 若含 `siglip` 或 `vision_encoder` subgraph,判为 MULTIMODAL。
 *  3. 否则 TEXT_ONLY。
 *
 * 骨架版直接返回 MULTIMODAL 占位,后续接入 LiteRT-LM SDK 后改为真实检测。
 */
enum class ModelType {
    /** 纯文本 Gemma(2B / 7B Instruct)。 */
    TEXT_ONLY,

    /** Siglip 视觉编码器 + Gemma 文本解码器组合模型。 */
    MULTIMODAL
}
