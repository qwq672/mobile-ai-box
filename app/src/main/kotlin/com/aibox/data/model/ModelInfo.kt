package com.aibox.data.model

import kotlinx.serialization.Serializable

/**
 * 已导入或已下载的模型信息。
 * 既有「实际文件」又有「元数据」。
 */
@Serializable
data class ModelInfo(
    val id: Long = 0,
    val name: String,            // 用户可见名
    val filePath: String,        // 本地绝对路径
    val sizeMb: Long,
    val quant: String? = null,   // q4_k_m / q5_k_m / ...
    val params: String? = null,  // 1B / 3B / 7B / 14B
    val arch: String? = null,    // llama / qwen / gemma / phi
    val addedAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = 0,
    val isFavorite: Boolean = false
)

/**
 * 一条消息附件（为多模态本地 / 云端模型准备）。
 *
 * - [kind] = "image"：图片，[uri] 指向应用私有目录里的文件（abs path）或 content:// URI
 * - [kind] = "text"：文本类文件，正文已抽到 [text]
 */
@Serializable
data class ChatAttachment(
    val kind: String,
    val uri: String = "",
    val name: String = "",
    val mime: String = "",
    val text: String = "",
    val sizeBytes: Long = 0L
)

/** 一条聊天消息 */
@Serializable
data class ChatMessage(
    val id: Long = 0,
    val sessionId: Long,
    val role: String,   // user / assistant / system
    val content: String,
    val ts: Long = System.currentTimeMillis(),
    val tokensPerSecond: Float = 0f,
    val attachments: List<ChatAttachment> = emptyList(),
    /** 结构化时间线（思考 / 工具 / 正文交错）；为空时由 [content] 解析，兼容旧数据。 */
    val parts: List<MsgPart> = emptyList(),
    /**
     * 消息版本历史（重新生成 / 编辑前的内容）。
     * 非空时 [versions] 的每一项都是一版正文，当前展示 [versionIndex] 对应的那一版。
     */
    val versions: List<String> = emptyList(),
    val versionIndex: Int = 0
)

/** 一次会话 */
@Serializable
data class ChatSession(
    val id: Long = 0,
    val title: String,
    val modelId: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)
