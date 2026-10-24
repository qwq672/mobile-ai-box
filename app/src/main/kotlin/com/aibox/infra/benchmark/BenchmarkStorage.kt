package com.aibox.infra.benchmark

import android.content.Context
import com.aibox.data.db.AppDatabase
import com.aibox.data.db.BenchmarkResultDao
import com.aibox.data.db.BenchmarkResultEntity
import com.aibox.domain.inference.BackendDescriptor
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 基准测试结果持久化。
 *
 * 把 [BenchmarkResult] 写入 Room [BenchmarkResultEntity] 表，提供按 modelPath / backendName /
 * 时间范围查询的 API。所有方法都是 suspend（除 observe 系列）且零异常（失败仅记日志）。
 *
 * 用法：
 * ```
 * val storage = BenchmarkStorage(appContext)
 * storage.save(benchmarkResult, backendName = "llamaCpp", modelFormat = "gguf", descriptor = backendDescriptor)
 *
 * storage.observeAll().collect { list -> ... }
 * storage.observeByModel("/sdcard/.../qwen2.5-0.5b.gguf").collect { ... }
 * ```
 *
 * @param context 应用上下文
 */
class BenchmarkStorage(context: Context) {

    private val tag = "aibox.benchmark.storage"
    private val dao: BenchmarkResultDao = AppDatabase.get(context).benchmarkResultDao()

    /**
     * 保存一份 [BenchmarkResult]。
     *
     * @param result 基准测试结果
     * @param backendName 后端名称（[InferenceBackendV2.getName]）
     * @param modelFormat 模型格式字符串（如 "gguf"），来自 [BackendDescriptor.describeCapabilities].supportedModelFormats 的第一项；
     *   若调用方不知道可传 null
     * @param descriptor 该后端的描述符，用于日志诊断；null 时仅记 backendName
     * @return 写入后的 rowId；失败返回 -1
     */
    suspend fun save(
        result: BenchmarkResult,
        backendName: String,
        modelFormat: String? = null,
        descriptor: BackendDescriptor? = null
    ): Long {
        val entity = toEntity(result, backendName, modelFormat, descriptor)
        return try {
            val rowId = dao.insert(entity)
            LogSystem.info(
                tag,
                "Saved benchmark result: id=$rowId backend=$backendName model=${result.spec.modelPath} tgTps=${"%.2f".format(result.tgTps)} failed=${result.failed}"
            )
            rowId
        } catch (t: Throwable) {
            LogSystem.error(tag, "save failed: ${t.message}", t)
            -1L
        }
    }

    /** 全部记录，按时间倒序。 */
    fun observeAll(): Flow<List<BenchmarkResultEntity>> = dao.observeAll()

    /** 按模型路径过滤。 */
    fun observeByModel(modelPath: String): Flow<List<BenchmarkResultEntity>> =
        dao.observeByModel(modelPath)

    /** 按后端名过滤。 */
    fun observeByBackend(backendName: String): Flow<List<BenchmarkResultEntity>> =
        dao.observeByBackend(backendName)

    /** 按时间范围（毫秒）过滤。 */
    fun observeByTimeRange(from: Long, to: Long): Flow<List<BenchmarkResultEntity>> =
        dao.observeByTimeRange(from, to)

    /** 单次按 ID 查询。 */
    suspend fun getById(id: Long): BenchmarkResultEntity? = try {
        dao.getById(id)
    } catch (t: Throwable) {
        LogSystem.error(tag, "getById failed: ${t.message}", t); null
    }

    /** 取最近 N 条。 */
    suspend fun recent(limit: Int): List<BenchmarkResultEntity> = try {
        dao.recent(limit)
    } catch (t: Throwable) {
        LogSystem.error(tag, "recent failed: ${t.message}", t); emptyList()
    }

    /** 删除单条。 */
    suspend fun deleteById(id: Long): Boolean = try {
        dao.deleteById(id); true
    } catch (t: Throwable) {
        LogSystem.error(tag, "deleteById failed: ${t.message}", t); false
    }

    /** 清空全部（用于"清空历史"按钮）。 */
    suspend fun deleteAll(): Boolean = try {
        dao.deleteAll(); true
    } catch (t: Throwable) {
        LogSystem.error(tag, "deleteAll failed: ${t.message}", t); false
    }

    // ----------------- mapping -----------------

    /** 把 [BenchmarkResult] 转成可入库的 [BenchmarkResultEntity]。 */
    private fun toEntity(
        result: BenchmarkResult,
        backendName: String,
        modelFormat: String?,
        descriptor: BackendDescriptor?
    ): BenchmarkResultEntity {
        val spec = result.spec
        val env = result.environment
        // modelFormat 兜底：从 descriptor 能力声明里取首个格式
        val resolvedFormat = modelFormat ?: descriptor?.describeCapabilities()?.supportedModelFormats?.firstOrNull()
        return BenchmarkResultEntity(
            id = 0L, // autoGenerate
            modelPath = spec.modelPath,
            backendName = backendName,
            modelFormat = resolvedFormat,
            specPpSize = spec.ppSize,
            specTgSize = spec.tgSize,
            specRepCount = spec.repCount,
            specThreads = spec.config.threads,
            specKvQuant = spec.config.kvQuant.name,
            ppTps = result.ppTps,
            tgTps = result.tgTps,
            ttftMs = result.ttftMs,
            totalTimeMs = result.totalTimeMs,
            peakMemoryBytes = result.peakMemoryBytes,
            energyMilliJoules = result.energyMilliJoules,
            deviceSoC = env.deviceSoC,
            thermalState = env.thermalState.name,
            backgroundCpuUsage = env.backgroundCpuUsage,
            batteryLevel = env.batteryLevel,
            chargingState = env.chargingState,
            totalRamMb = env.totalRamMb,
            freeRamMb = env.freeRamMb,
            logFilePath = result.logFilePath,
            failed = result.failed,
            errorMessage = result.errorMessage,
            timestamp = result.timestamp
        )
    }

    /**
     * 把数据库实体"反向"还原成 [BenchmarkResult]（仅核心字段；logFilePath 等次要字段已保留）。
     *
     * 用于 UI 渲染历史结果。注：[BenchmarkResult.spec] 的 [BenchmarkSpec.config] 还原时只填 threads / kvQuant
     * 两个核心字段，其它配置项（temperature / topP / contextLength 等）回退到 [InferenceConfigV2] 默认。
     */
    fun toBenchmarkResult(e: BenchmarkResultEntity): BenchmarkResult {
        // 兼容历史 / 脏数据：kvQuant 反序列化失败时回退到默认 F16
        val kvQuant = try {
            com.aibox.domain.inference.QuantType.valueOf(e.specKvQuant)
        } catch (_: Throwable) {
            com.aibox.domain.inference.InferenceConfigV2().kvQuant
        }
        val config = com.aibox.domain.inference.InferenceConfigV2(
            threads = e.specThreads,
            kvQuant = kvQuant,
            maxTokens = e.specTgSize,
            contextLength = com.aibox.domain.inference.InferenceConfigV2().contextLength
        )
        val spec = BenchmarkSpec(
            modelPath = e.modelPath,
            config = config,
            ppSize = e.specPpSize,
            tgSize = e.specTgSize,
            repCount = e.specRepCount
        )
        val env = EnvironmentSnapshot(
            deviceSoC = e.deviceSoC,
            cpuFreqCurrent = 0L, // 不持久化（每次跑测都不同）
            thermalState = ThermalState.valueOf(e.thermalState),
            backgroundCpuUsage = e.backgroundCpuUsage,
            chargingState = e.chargingState,
            batteryLevel = e.batteryLevel,
            totalRamMb = e.totalRamMb,
            freeRamMb = e.freeRamMb
        )
        return BenchmarkResult(
            spec = spec,
            ppTps = e.ppTps,
            tgTps = e.tgTps,
            ttftMs = e.ttftMs,
            totalTimeMs = e.totalTimeMs,
            peakMemoryBytes = e.peakMemoryBytes,
            energyMilliJoules = e.energyMilliJoules,
            environment = env,
            logFilePath = e.logFilePath,
            timestamp = e.timestamp,
            failed = e.failed,
            errorMessage = e.errorMessage
        )
    }
}
