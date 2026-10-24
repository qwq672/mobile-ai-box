package com.aibox.domain.optimization

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 优化设置独立 DataStore（P3）。
 *
 * 与 [com.aibox.data.repo.SettingsRepository] 用同一个 DataStore 文件名空间不同，
 * 这里另起一个 `optimization` DataStore 文件，避免与现有 17 个 key 共享同一文件
 * 造成 namespace 污染，也便于以后单独导出 / 清空优化配置。
 *
 * API 与现有 [com.aibox.data.repo.SettingsRepository] 风格对齐：
 * - [settingsFlow]：Observable [OptimizationSettings] Flow，UI 用 collectAsStateWithLifecycle 收集。
 * - [update]：原子读取-修改-写回；并发安全（DataStore.edit 内部串行化）。
 * - [set]：覆盖写整份设置；用于"应用预置 / 重置默认"场景。
 * - [current]：一次性快照（suspend），便于 [OptimizationManager.onAppStart] 在 App 启动时拿初始值。
 *
 * 设计原则：
 * - 每个字段都用独立的 [Boolean] / [Long] key 存储，便于以后单独 export / migrate；
 *   不打包成 JSON 字符串，避免反序列化开销与字段缺省时丢默认值。
 * - 缺省值与 [OptimizationSettings.DEFAULT] 严格一致；老用户首次升级时所有 key 缺省，
 *   map 出来的 [OptimizationSettings] 即为默认档，无需 migration。
 */
private val Context.optimizationDataStore by preferencesDataStore("optimization")

class OptimizationSettingsRepository(private val context: Context) {

    private object Keys {
        // TTFT
        val PRELOAD_MODEL                = booleanPreferencesKey("preload_model")
        val WARMUP_AFTER_LOAD            = booleanPreferencesKey("warmup_after_load")
        val PREALLOCATE_KV               = booleanPreferencesKey("preallocate_kv")
        val PREBUILD_SAMPLER             = booleanPreferencesKey("prebuild_sampler")
        val STREAM_FIRST_TOKEN_IMMEDIATE = booleanPreferencesKey("stream_first_token_immediate")
        // TG
        val FORCE_FLASH_ATTENTION        = booleanPreferencesKey("force_flash_attention")
        val AGGRESSIVE_GRAPH_OPT          = booleanPreferencesKey("aggressive_graph_opt")
        val CONTINUOUS_KV_LAYOUT         = booleanPreferencesKey("continuous_kv_layout")
        val REUSE_SAMPLER                = booleanPreferencesKey("reuse_sampler")
        val ZERO_COPY_STREAM             = booleanPreferencesKey("zero_copy_stream")
        val ENABLE_GRAPH_CACHE           = booleanPreferencesKey("enable_graph_cache")
        // 能耗
        val THERMAL_AWARE_THROTTLE       = booleanPreferencesKey("thermal_aware_throttle")
        val IDLE_UNLOAD_TIMEOUT          = booleanPreferencesKey("idle_unload_timeout")
        val IDLE_UNLOAD_TIMEOUT_MS       = longPreferencesKey("idle_unload_timeout_ms")
        val LOW_POWER_ON_BATTERY         = booleanPreferencesKey("low_power_on_battery")
    }

    /**
     * Observable settings flow.
     *
     * UI 直接 collect 这个 flow 即可自动响应外部 update（如点击预置按钮）。
     */
    val settingsFlow: Flow<OptimizationSettings> =
        context.optimizationDataStore.data.map { p ->
            OptimizationSettings(
                // TTFT
                preloadModel                = p[Keys.PRELOAD_MODEL]                ?: true,
                warmupAfterLoad            = p[Keys.WARMUP_AFTER_LOAD]            ?: true,
                preallocateKV              = p[Keys.PREALLOCATE_KV]               ?: true,
                prebuildSampler            = p[Keys.PREBUILD_SAMPLER]             ?: true,
                streamFirstTokenImmediately = p[Keys.STREAM_FIRST_TOKEN_IMMEDIATE] ?: true,
                // TG
                forceFlashAttention        = p[Keys.FORCE_FLASH_ATTENTION]        ?: true,
                aggressiveGraphOpt         = p[Keys.AGGRESSIVE_GRAPH_OPT]         ?: true,
                continuousKVLayout         = p[Keys.CONTINUOUS_KV_LAYOUT]         ?: true,
                reuseSampler               = p[Keys.REUSE_SAMPLER]                ?: true,
                zeroCopyStream             = p[Keys.ZERO_COPY_STREAM]             ?: true,
                enableGraphCache           = p[Keys.ENABLE_GRAPH_CACHE]           ?: true,
                // 能耗
                thermalAwareThrottle       = p[Keys.THERMAL_AWARE_THROTTLE]       ?: true,
                idleUnloadTimeout          = p[Keys.IDLE_UNLOAD_TIMEOUT]          ?: true,
                idleUnloadTimeoutMs        = p[Keys.IDLE_UNLOAD_TIMEOUT_MS]       ?: 10 * 60 * 1000,
                lowPowerOnBattery          = p[Keys.LOW_POWER_ON_BATTERY]         ?: false
            )
        }

    /** 一次性快照，供 [OptimizationManager.onAppStart] 在启动时使用。 */
    suspend fun current(): OptimizationSettings = settingsFlow.first()

    /**
     * 原子读取-修改-写回；并发安全（DataStore.edit 内部串行化）。
     *
     * 用法：`repo.update { it.copy(forceFlashAttention = false) }`
     */
    suspend fun update(transform: (OptimizationSettings) -> OptimizationSettings) {
        context.optimizationDataStore.edit { p ->
            // 读当前 → 转换 → 写回；缺省值与 OptimizationSettings.DEFAULT 一致
            val current = OptimizationSettings(
                preloadModel                = p[Keys.PRELOAD_MODEL]                ?: true,
                warmupAfterLoad            = p[Keys.WARMUP_AFTER_LOAD]            ?: true,
                preallocateKV              = p[Keys.PREALLOCATE_KV]               ?: true,
                prebuildSampler            = p[Keys.PREBUILD_SAMPLER]             ?: true,
                streamFirstTokenImmediately = p[Keys.STREAM_FIRST_TOKEN_IMMEDIATE] ?: true,
                forceFlashAttention        = p[Keys.FORCE_FLASH_ATTENTION]        ?: true,
                aggressiveGraphOpt         = p[Keys.AGGRESSIVE_GRAPH_OPT]         ?: true,
                continuousKVLayout         = p[Keys.CONTINUOUS_KV_LAYOUT]         ?: true,
                reuseSampler               = p[Keys.REUSE_SAMPLER]                ?: true,
                zeroCopyStream             = p[Keys.ZERO_COPY_STREAM]             ?: true,
                enableGraphCache           = p[Keys.ENABLE_GRAPH_CACHE]           ?: true,
                thermalAwareThrottle       = p[Keys.THERMAL_AWARE_THROTTLE]       ?: true,
                idleUnloadTimeout          = p[Keys.IDLE_UNLOAD_TIMEOUT]          ?: true,
                idleUnloadTimeoutMs        = p[Keys.IDLE_UNLOAD_TIMEOUT_MS]       ?: 10 * 60 * 1000,
                lowPowerOnBattery          = p[Keys.LOW_POWER_ON_BATTERY]         ?: false
            )
            val next = transform(current)
            // 写回所有字段（包括未变的；这样保证下次读出来的就是 next 而不是缺省）
            p[Keys.PRELOAD_MODEL]                = next.preloadModel
            p[Keys.WARMUP_AFTER_LOAD]            = next.warmupAfterLoad
            p[Keys.PREALLOCATE_KV]               = next.preallocateKV
            p[Keys.PREBUILD_SAMPLER]             = next.prebuildSampler
            p[Keys.STREAM_FIRST_TOKEN_IMMEDIATE] = next.streamFirstTokenImmediately
            p[Keys.FORCE_FLASH_ATTENTION]        = next.forceFlashAttention
            p[Keys.AGGRESSIVE_GRAPH_OPT]         = next.aggressiveGraphOpt
            p[Keys.CONTINUOUS_KV_LAYOUT]         = next.continuousKVLayout
            p[Keys.REUSE_SAMPLER]                = next.reuseSampler
            p[Keys.ZERO_COPY_STREAM]             = next.zeroCopyStream
            p[Keys.ENABLE_GRAPH_CACHE]           = next.enableGraphCache
            p[Keys.THERMAL_AWARE_THROTTLE]       = next.thermalAwareThrottle
            p[Keys.IDLE_UNLOAD_TIMEOUT]          = next.idleUnloadTimeout
            p[Keys.IDLE_UNLOAD_TIMEOUT_MS]       = next.idleUnloadTimeoutMs
            p[Keys.LOW_POWER_ON_BATTERY]         = next.lowPowerOnBattery
        }
    }

    /**
     * 覆盖写整份设置；用于"应用预置 / 重置默认"。
     *
     * 内部走 [update] 通道，保证 DataStore 串行化与 observer 通知。
     */
    suspend fun set(settings: OptimizationSettings) {
        update { settings }
    }
}
