package com.pocketllm.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val filePath: String,
    val sizeMb: Long,
    val quant: String?,
    val params: String?,
    val arch: String?,
    val addedAt: Long,
    val lastUsedAt: Long,
    val isFavorite: Boolean
)

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val modelId: Long?,
    val createdAt: Long,
    val lastUsedAt: Long = 0,
    val messageCount: Int = 0,
    val modelPath: String? = null
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: String,
    val content: String,
    val ts: Long,
    val tokensPerSecond: Float
)
