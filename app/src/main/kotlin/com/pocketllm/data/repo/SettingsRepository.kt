package com.pocketllm.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pocketllm.domain.inference.BackendType
import com.pocketllm.domain.inference.InferenceConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val BACKEND         = stringPreferencesKey("backend")
        val N_GPU_LAYERS    = intPreferencesKey("n_gpu_layers")
        val CPU_THREADS     = intPreferencesKey("cpu_threads")
        val ANTI_ROLLBACK   = booleanPreferencesKey("anti_rollback")
        val PHYSICAL_BATCH  = intPreferencesKey("physical_batch")
        val BATCH           = intPreferencesKey("batch")
        val KV_QUANT        = stringPreferencesKey("kv_quant")
        val CONTEXT_LEN     = intPreferencesKey("context_len")
        val TEMPERATURE     = stringPreferencesKey("temperature")
        val TOP_K           = intPreferencesKey("top_k")
        val TOP_P           = stringPreferencesKey("top_p")
        val REPEAT_PENALTY  = stringPreferencesKey("repeat_penalty")
        val AUTO_LOAD       = booleanPreferencesKey("auto_load")
        val SYSTEM_PROMPT   = stringPreferencesKey("system_prompt")
        val DOWNLOAD_SOURCE = stringPreferencesKey("dl_source")
        val LANGUAGE        = stringPreferencesKey("lang")
        val LAST_MODEL_ID   = intPreferencesKey("last_model_id")
        // 外观 & 日志
        val DYNAMIC_COLOR   = booleanPreferencesKey("dynamic_color")
        val DARK_THEME      = stringPreferencesKey("dark_theme") // "system" / "light" / "dark"
        val LOGGING_ENABLED = booleanPreferencesKey("logging_enabled")
        // 思考模式开关（默认 true）
        val THINKING_MODE   = booleanPreferencesKey("thinking_mode")
    }

    val config: Flow<InferenceConfig> = context.dataStore.data.map { p ->
        InferenceConfig(
            backend = BackendType.fromId(p[Keys.BACKEND]),
            nGpuLayers = p[Keys.N_GPU_LAYERS] ?: 0,
            cpuThreads = p[Keys.CPU_THREADS] ?: 0,
            antiRollback = p[Keys.ANTI_ROLLBACK] ?: false,
            physicalBatch = p[Keys.PHYSICAL_BATCH] ?: 512,
            batch = p[Keys.BATCH] ?: 2048,
            kvQuant = p[Keys.KV_QUANT] ?: "q8_0",
            contextLength = p[Keys.CONTEXT_LEN] ?: 4096,
            temperature = (p[Keys.TEMPERATURE] ?: "0.8").toFloat(),
            topK = p[Keys.TOP_K] ?: 40,
            topP = (p[Keys.TOP_P] ?: "0.95").toFloat(),
            repeatPenalty = (p[Keys.REPEAT_PENALTY] ?: "1.1").toFloat(),
            autoLoadModel = p[Keys.AUTO_LOAD] ?: false,
            systemPrompt = p[Keys.SYSTEM_PROMPT] ?: ""
        )
    }

    val downloadSource: Flow<String> = context.dataStore.data.map { it[Keys.DOWNLOAD_SOURCE] ?: "hf_mirror" }
    val language: Flow<String> = context.dataStore.data.map { it[Keys.LANGUAGE] ?: "zh" }
    val lastModelId: Flow<Long> = context.dataStore.data.map { (it[Keys.LAST_MODEL_ID] ?: -1L).toLong() }
    val thinkingMode: Flow<Boolean> = context.dataStore.data.map { it[Keys.THINKING_MODE] ?: true }

    /** Material You 取色 */
    val dynamicColor: Flow<Boolean> = context.dataStore.data.map { it[Keys.DYNAMIC_COLOR] ?: true }
    /** "system" / "light" / "dark" */
    val darkTheme: Flow<String> = context.dataStore.data.map { it[Keys.DARK_THEME] ?: "system" }
    /** 日志开关 */
    val loggingEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.LOGGING_ENABLED] ?: true }

    suspend fun save(c: InferenceConfig) {
        context.dataStore.edit { p ->
            p[Keys.BACKEND] = c.backend.id
            p[Keys.N_GPU_LAYERS] = c.nGpuLayers
            p[Keys.CPU_THREADS] = c.cpuThreads
            p[Keys.ANTI_ROLLBACK] = c.antiRollback
            p[Keys.PHYSICAL_BATCH] = c.physicalBatch
            p[Keys.BATCH] = c.batch
            p[Keys.KV_QUANT] = c.kvQuant
            p[Keys.CONTEXT_LEN] = c.contextLength
            p[Keys.TEMPERATURE] = c.temperature.toString()
            p[Keys.TOP_K] = c.topK
            p[Keys.TOP_P] = c.topP.toString()
            p[Keys.REPEAT_PENALTY] = c.repeatPenalty.toString()
            p[Keys.AUTO_LOAD] = c.autoLoadModel
            p[Keys.SYSTEM_PROMPT] = c.systemPrompt
        }
    }

    suspend fun setDownloadSource(src: String) =
        context.dataStore.edit { it[Keys.DOWNLOAD_SOURCE] = src }

    suspend fun setLanguage(lang: String) =
        context.dataStore.edit { it[Keys.LANGUAGE] = lang }

    suspend fun setLastModel(id: Long) =
        context.dataStore.edit { it[Keys.LAST_MODEL_ID] = id.toInt() }

    suspend fun setDynamicColor(on: Boolean) =
        context.dataStore.edit { it[Keys.DYNAMIC_COLOR] = on }

    suspend fun setDarkTheme(mode: String) =
        context.dataStore.edit { it[Keys.DARK_THEME] = mode }

    suspend fun setLoggingEnabled(on: Boolean) =
        context.dataStore.edit { it[Keys.LOGGING_ENABLED] = on }

    suspend fun setThinkingMode(on: Boolean) =
        context.dataStore.edit { it[Keys.THINKING_MODE] = on }
}
