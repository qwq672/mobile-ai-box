package com.aibox.data.db

import androidx.room.ColumnInfo
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
    val modelPath: String? = null,
    /** 会话模式："CHAT" / "AGENT" / "ROLEPLAY"。 */
    @ColumnInfo(defaultValue = "CHAT")
    val mode: String = "CHAT",
    /** 角色扮演绑定的 AI 角色 id；null = 非角色扮演。 */
    val characterId: Long? = null,
    /** 角色扮演绑定的用户身份 id；null = 未选身份。 */
    val personaId: Long? = null
)

/**
 * AI 角色（角色扮演）。
 *
 * 与 [PersonaEntity] 分开：一个是“模型扮演谁”，一个是“用户是谁”，
 * 会话记得同时绑定两者，换角色不会污染用户身份。
 */
@Entity(tableName = "characters")
data class CharacterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 头像 emoji（不引入图片资源，保持轻量）。 */
    val emoji: String = "🙂",
    /** 一句话简介，列表里展示。 */
    val description: String = "",
    /** 角色设定（system prompt 的主体）。 */
    val systemPrompt: String = "",
    /** 开场白 / 进入会话时的第一句话。 */
    val greeting: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = 0
)

/** 用户身份（角色扮演里的“我是谁”）。 */
@Entity(tableName = "personas")
data class PersonaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = 0
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: String,
    val content: String,
    val ts: Long,
    val tokensPerSecond: Float,
    /** 附件 JSON（[com.aibox.data.model.ChatAttachment] 列表），空串表示无附件。 */
    @ColumnInfo(defaultValue = "")
    val attachmentsJson: String = "",
    /** 结构化时间线 JSON（[com.aibox.data.model.MsgPart] 列表），空串表示按 content 解析。 */
    @ColumnInfo(defaultValue = "")
    val partsJson: String = "",
    /** 版本历史 JSON（字符串列表），空串表示只有一个版本。 */
    @ColumnInfo(defaultValue = "")
    val versionsJson: String = "",
    /** 当前展示的版本下标。 */
    @ColumnInfo(defaultValue = "0")
    val versionIndex: Int = 0,
    /** 助手回复所属的用户消息版本（-1 = 不区分）。 */
    @ColumnInfo(defaultValue = "-1")
    val parentUserVersion: Int = -1
)

/**
 * 基准测试结果记录（P2 benchmark system）。
 *
 * 持久化 [com.aibox.infra.benchmark.BenchmarkResult] 的核心字段，便于历史查询与跨设备对比。
 * 索引列：[modelPath] / [backendName] / [timestamp]，[BenchmarkResultDao] 提供按这三个维度的查询。
 *
 * 字段说明见 [com.aibox.infra.benchmark.BenchmarkResult] / [com.aibox.infra.benchmark.BenchmarkSpec] / [com.aibox.infra.benchmark.EnvironmentSnapshot]。
 */
@Entity(tableName = "benchmark_results")
data class BenchmarkResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val modelPath: String,
    val backendName: String,
    val modelFormat: String?,
    val specPpSize: Int,
    val specTgSize: Int,
    val specRepCount: Int,
    /** config.threads；null = 后端自动决定 */
    val specThreads: Int?,
    /** config.kvQuant.name，如 "F16" / "Q8_0" */
    val specKvQuant: String,
    val ppTps: Double,
    val tgTps: Double,
    val ttftMs: Long,
    val totalTimeMs: Long,
    val peakMemoryBytes: Long,
    /** 能耗估算；null = 读取失败 / 不支持 */
    val energyMilliJoules: Long?,
    val deviceSoC: String,
    /** ThermalState.name，如 "COOL" / "WARM" */
    val thermalState: String,
    val backgroundCpuUsage: Int,
    val batteryLevel: Int,
    val chargingState: Boolean,
    val totalRamMb: Int,
    val freeRamMb: Int,
    val logFilePath: String?,
    val failed: Boolean,
    val errorMessage: String?,
    val timestamp: Long
)
