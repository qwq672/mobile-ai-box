package com.aibox.data.db

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
