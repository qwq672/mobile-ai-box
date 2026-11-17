package com.aibox.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aibox.domain.cloud.CloudConfig
import com.aibox.domain.inference.BackendType
import com.aibox.domain.inference.InferenceConfig
import com.aibox.util.log.LogSystem
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
        val MIN_P           = stringPreferencesKey("min_p")
        val REPEAT_PENALTY  = stringPreferencesKey("repeat_penalty")
        val REPEAT_LAST_N   = intPreferencesKey("repeat_last_n")
        // 注：AUTO_LOAD key 已删除（G-settings-fix）
        // "模型预加载" 开关归 OptimizationSettings.preloadModel 统一管理
        // 详见 docs/ARCHITECTURE.md §配置分层
        val SYSTEM_PROMPT   = stringPreferencesKey("system_prompt")
        val DOWNLOAD_SOURCE = stringPreferencesKey("dl_source")
        val LANGUAGE        = stringPreferencesKey("lang")
        val LAST_MODEL_ID   = intPreferencesKey("last_model_id")
        // 外观 & 日志
        val DYNAMIC_COLOR   = booleanPreferencesKey("dynamic_color")
        val DARK_THEME      = stringPreferencesKey("dark_theme") // "system" / "light" / "dark"
        val LOGGING_ENABLED = booleanPreferencesKey("logging_enabled")
        // 自定义主色（"dynamic" = 跟随系统壁纸；其余为 #RRGGBB）
        val ACCENT_COLOR    = stringPreferencesKey("accent_color")
        // 全局圆角倍率（0.5 = 更方正，1.0 = 默认，1.6 = 更圆润）
        val CORNER_SCALE    = stringPreferencesKey("corner_scale")
        // 思考强度："off" / "low" / "medium" / "high"
        val THINKING_LEVEL  = stringPreferencesKey("thinking_level")
        // 思考模式开关（旧字段，保留兼容：thinking_level 非 off 时视为 true）
        val THINKING_MODE   = booleanPreferencesKey("thinking_mode")
        // B3 WebSearchTool：第三方搜索引擎 API key（用户在 Settings 配；空表示未配）
        val TAVILY_API_KEY  = stringPreferencesKey("tavily_api_key")
        val EXA_API_KEY     = stringPreferencesKey("exa_api_key")
        // 聊天消息呈现：true = 文档流（助手无气泡），false = 双方气泡
        val CHAT_DOC_STYLE  = booleanPreferencesKey("chat_doc_style")
        // 云端模型（OpenAI 兼容协议）
        val CLOUD_ENABLED   = booleanPreferencesKey("cloud_enabled")
        val CLOUD_BASE_URL  = stringPreferencesKey("cloud_base_url")
        val CLOUD_API_KEY   = stringPreferencesKey("cloud_api_key")
        val CLOUD_MODEL     = stringPreferencesKey("cloud_model")
        val CLOUD_VISION    = booleanPreferencesKey("cloud_supports_vision")
        val CLOUD_AUDIO     = booleanPreferencesKey("cloud_supports_audio")
        val CLOUD_WEB       = booleanPreferencesKey("cloud_builtin_web_search")
        val CLOUD_URL       = booleanPreferencesKey("cloud_builtin_url_reader")
        // 低电量提示：两级都可被用户永久关闭（默认开启）
        val WARN_LOW_BATTERY      = booleanPreferencesKey("warn_low_battery")
        val WARN_CRITICAL_BATTERY = booleanPreferencesKey("warn_critical_battery")
        // 首次启动欢迎页是否已完成
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        // Beta：aibox:choices 选项卡片（离线小模型下容易滥用，默认关闭）
        val CHOICES_BETA = booleanPreferencesKey("choices_beta")
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
            minP = (p[Keys.MIN_P] ?: "0.05").toFloat().coerceIn(0f, 1f),
            repeatPenalty = (p[Keys.REPEAT_PENALTY] ?: "1.1").toFloat(),
            repeatLastN = (p[Keys.REPEAT_LAST_N] ?: 256).coerceIn(32, 2048),
            systemPrompt = p[Keys.SYSTEM_PROMPT] ?: ""
        )
    }

    val downloadSource: Flow<String> = context.dataStore.data.map { it[Keys.DOWNLOAD_SOURCE] ?: "hf_mirror" }
    val language: Flow<String> = context.dataStore.data.map { it[Keys.LANGUAGE] ?: "zh" }
    val lastModelId: Flow<Long> = context.dataStore.data.map { (it[Keys.LAST_MODEL_ID] ?: -1L).toLong() }
    /** 思考强度："off" / "low" / "medium" / "high"。旧数据回退到 thinking_mode 开关。 */
    val thinkingLevel: Flow<String> = context.dataStore.data.map { p ->
        p[Keys.THINKING_LEVEL] ?: if (p[Keys.THINKING_MODE] == false) "off" else "low"
    }
    val thinkingMode: Flow<Boolean> = thinkingLevel.map { it != "off" }

    /** 自定义主色："dynamic" 表示跟随系统壁纸，其余为 #RRGGBB。 */
    val accentColor: Flow<String> = context.dataStore.data.map { it[Keys.ACCENT_COLOR] ?: "dynamic" }
    /** 全局圆角倍率（0.5 ~ 1.6），默认 1.0。 */
    val cornerScale: Flow<Float> = context.dataStore.data.map {
        (it[Keys.CORNER_SCALE] ?: "1.0").toFloatOrNull()?.coerceIn(0.5f, 1.6f) ?: 1.0f
    }

    /** 聊天消息呈现：true = 文档流（助手无气泡），false = 双方气泡 */
    val chatDocStyle: Flow<Boolean> = context.dataStore.data.map { it[Keys.CHAT_DOC_STYLE] ?: true }

    /** Material You 取色 */
    val dynamicColor: Flow<Boolean> = context.dataStore.data.map { it[Keys.DYNAMIC_COLOR] ?: true }
    /** "system" / "light" / "dark" */
    val darkTheme: Flow<String> = context.dataStore.data.map { it[Keys.DARK_THEME] ?: "system" }
    /** 日志开关 */
    val loggingEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.LOGGING_ENABLED] ?: true }

    /** 云端模型配置（OpenAI 兼容：OpenAI / DeepSeek / Moonshot / Ollama / vLLM）。 */
    val cloudConfig: Flow<CloudConfig> = context.dataStore.data.map { p ->
        CloudConfig(
            enabled = p[Keys.CLOUD_ENABLED] ?: false,
            baseUrl = p[Keys.CLOUD_BASE_URL] ?: CloudConfig.DEFAULT_BASE_URL,
            apiKey = p[Keys.CLOUD_API_KEY] ?: "",
            model = p[Keys.CLOUD_MODEL] ?: CloudConfig.DEFAULT_MODEL,
            supportsVision = p[Keys.CLOUD_VISION] ?: false,
            supportsAudio = p[Keys.CLOUD_AUDIO] ?: false,
            builtinWebSearch = p[Keys.CLOUD_WEB] ?: false,
            builtinUrlReader = p[Keys.CLOUD_URL] ?: false
        )
    }

    suspend fun saveCloud(c: CloudConfig) {
        context.dataStore.edit { p ->
            p[Keys.CLOUD_ENABLED] = c.enabled
            p[Keys.CLOUD_BASE_URL] = c.baseUrl
            p[Keys.CLOUD_API_KEY] = c.apiKey
            p[Keys.CLOUD_MODEL] = c.model
            p[Keys.CLOUD_VISION] = c.supportsVision
            p[Keys.CLOUD_AUDIO] = c.supportsAudio
            p[Keys.CLOUD_WEB] = c.builtinWebSearch
            p[Keys.CLOUD_URL] = c.builtinUrlReader
        }
    }

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
            p[Keys.MIN_P] = c.minP.toString()
            p[Keys.REPEAT_PENALTY] = c.repeatPenalty.toString()
            p[Keys.REPEAT_LAST_N] = c.repeatLastN
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
        context.dataStore.edit {
            it[Keys.THINKING_MODE] = on
            it[Keys.THINKING_LEVEL] = if (on) "medium" else "off"
        }

    /** 设置思考档位："off" / "low" / "medium" / "high"。 */
    suspend fun setThinkingLevel(level: String) =
        context.dataStore.edit {
            it[Keys.THINKING_LEVEL] = level
            it[Keys.THINKING_MODE] = level != "off"
        }

    suspend fun setAccentColor(hexOrDynamic: String) =
        context.dataStore.edit { it[Keys.ACCENT_COLOR] = hexOrDynamic }

    suspend fun setCornerScale(scale: Float) =
        context.dataStore.edit { it[Keys.CORNER_SCALE] = scale.coerceIn(0.5f, 1.6f).toString() }

    suspend fun setChatDocStyle(on: Boolean) =
        context.dataStore.edit { it[Keys.CHAT_DOC_STYLE] = on }

    // ===================================================
    // B3 WebSearchTool：第三方搜索引擎 API key
    // 用户在 Settings 中配置；空字符串表示未配置（此时调用对应引擎会返回失败提示）
    // 默认搜索引擎 DuckDuckGo 不需要 API key（HTML 兜底解析）
    // ===================================================

    /** Tavily API key（用户在 Settings 配；空表示未配置）。 */
    val tavilyApiKey: Flow<String> = context.dataStore.data.map { it[Keys.TAVILY_API_KEY] ?: "" }

    /** Exa API key（用户在 Settings 配；空表示未配置）。 */
    val exaApiKey: Flow<String> = context.dataStore.data.map { it[Keys.EXA_API_KEY] ?: "" }

    suspend fun setTavilyApiKey(key: String) =
        context.dataStore.edit { it[Keys.TAVILY_API_KEY] = key }

    suspend fun setExaApiKey(key: String) =
        context.dataStore.edit { it[Keys.EXA_API_KEY] = key }

    // ===================================================
    // 低电量提示开关（两级，默认都开；用户可永久关闭）
    // ===================================================

    /** 低电量（阈值附近）提示是否允许显示。 */
    val warnLowBattery: Flow<Boolean> = context.dataStore.data.map { it[Keys.WARN_LOW_BATTERY] ?: true }

    /** 极低电量（≤5%）警告是否允许显示。 */
    val warnCriticalBattery: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.WARN_CRITICAL_BATTERY] ?: true }

    suspend fun setWarnLowBattery(on: Boolean) =
        context.dataStore.edit { it[Keys.WARN_LOW_BATTERY] = on }

    suspend fun setWarnCriticalBattery(on: Boolean) =
        context.dataStore.edit { it[Keys.WARN_CRITICAL_BATTERY] = on }

    // ===================================================
    // 欢迎页
    // ===================================================

    /**
     * 是否已完成首次启动欢迎页。
     *
     * 缺省推断：DataStore 里已有任何设置 → 视为老用户，不再弹欢迎页；
     * 全新安装（DataStore 为空）→ false，展示欢迎页。
     */
    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { p ->
        p[Keys.ONBOARDING_DONE] ?: p.asMap().isNotEmpty()
    }

    suspend fun setOnboardingDone(done: Boolean) =
        context.dataStore.edit { it[Keys.ONBOARDING_DONE] = done }

    /**
     * Beta：是否启用 `aibox:choices` 选项卡片。
     *
     * 默认关闭：离线小模型容易把工具参数、选项列表当正文输出，
     * 表现就是每轮都弹一堆 A/B/C 按钮。
     */
    val choicesBeta: Flow<Boolean> = context.dataStore.data.map { it[Keys.CHOICES_BETA] ?: false }

    suspend fun setChoicesBeta(on: Boolean) =
        context.dataStore.edit { it[Keys.CHOICES_BETA] = on }

    // ===================================================
    // 重置（按分类 / 全部）
    // ===================================================

    /** 只重置推理相关参数：后端、批大小、KV、上下文、采样、系统提示词。 */
    suspend fun resetInference() = context.dataStore.edit { p ->
        listOf(
            Keys.BACKEND, Keys.N_GPU_LAYERS, Keys.CPU_THREADS, Keys.ANTI_ROLLBACK,
            Keys.PHYSICAL_BATCH, Keys.BATCH, Keys.KV_QUANT, Keys.CONTEXT_LEN,
            Keys.TEMPERATURE, Keys.TOP_K, Keys.TOP_P, Keys.MIN_P,
            Keys.REPEAT_PENALTY, Keys.REPEAT_LAST_N, Keys.SYSTEM_PROMPT
        ).forEach { p.remove(it) }
        LogSystem.info("aibox.settings", "resetInference done")
    }

    /** 只重置外观：取色 / 主题 / 主色 / 圆角 / 消息呈现。 */
    suspend fun resetAppearance() = context.dataStore.edit { p ->
        listOf(
            Keys.DYNAMIC_COLOR, Keys.DARK_THEME, Keys.ACCENT_COLOR,
            Keys.CORNER_SCALE, Keys.CHAT_DOC_STYLE
        ).forEach { p.remove(it) }
        LogSystem.info("aibox.settings", "resetAppearance done")
    }

    /** 只重置云端模型配置。 */
    suspend fun resetCloud() = context.dataStore.edit { p ->
        listOf(
            Keys.CLOUD_ENABLED, Keys.CLOUD_BASE_URL, Keys.CLOUD_API_KEY, Keys.CLOUD_MODEL,
            Keys.CLOUD_VISION, Keys.CLOUD_AUDIO, Keys.CLOUD_WEB, Keys.CLOUD_URL
        ).forEach { p.remove(it) }
        LogSystem.info("aibox.settings", "resetCloud done")
    }

    /** 只重置下载源 / 语言 / 思考档位 / 日志开关 / 搜索 API key。 */
    suspend fun resetBehavior() = context.dataStore.edit { p ->
        listOf(
            Keys.DOWNLOAD_SOURCE, Keys.LANGUAGE, Keys.THINKING_LEVEL, Keys.THINKING_MODE,
            Keys.LOGGING_ENABLED, Keys.TAVILY_API_KEY, Keys.EXA_API_KEY
        ).forEach { p.remove(it) }
        LogSystem.info("aibox.settings", "resetBehavior done")
    }

    /**
     * 重置本 DataStore 的全部设置。
     *
     * 保留 [Keys.ONBOARDING_DONE]（避免重置后又被欢迎页拦住）与
     * [Keys.LAST_MODEL_ID]（模型文件还在，保留上次选择更符合预期）。
     */
    suspend fun resetAllSettings() = context.dataStore.edit { p ->
        p.clear()
        // 重置后不再弹欢迎页（用户已经在用了），也保留上次选择的模型 id
        p[Keys.ONBOARDING_DONE] = true
        LogSystem.info("aibox.settings", "resetAllSettings done")
    }
}
