package com.pocketllm.data.model

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

/** 一条聊天消息 */
@Serializable
data class ChatMessage(
    val id: Long = 0,
    val sessionId: Long,
    val role: String,   // user / assistant / system
    val content: String,
    val ts: Long = System.currentTimeMillis(),
    val tokensPerSecond: Float = 0f
)

/** 一次会话 */
@Serializable
data class ChatSession(
    val id: Long = 0,
    val title: String,
    val modelId: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)
