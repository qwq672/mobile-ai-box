package com.aibox.infra.plugin

import android.os.RemoteException
import com.aibox.domain.inference.BackendCapabilities
import com.aibox.domain.inference.BackendDescriptor
import com.aibox.domain.inference.InferenceConfigV2
import com.aibox.domain.inference.OptimizationFlags
import com.aibox.domain.inference.QuantType
import com.aibox.domain.inference.TaskType

/**
 * 把 [com.aibox.plugin.IPluginDescriptor] 适配为 V2 [BackendDescriptor]。
 *
 * 与 [PluginBackendAdapter] 共同构成"插件 ↔ V2 抽象层"的桥：
 * - [PluginBackendAdapter] 适配运行时接口（加载 / 推理 / 卸载）。
 * - 本类适配元信息接口（能力 / 设置 schema / 默认值 / parseSettings）。
 *
 * 设计原则：
 * 1. **零异常约定**：所有 AIDL 调用 try-catch RemoteException，返回带 fallback 的"安全"对象。
 * 2. **parseSettings 在宿主侧**：AIDL IPluginDescriptor 不暴露 parseSettings（避免插件做宿主逻辑）；
 *    本类在宿主侧用"通用键名约定"（threads / temperature / kvQuant 等）解析，
 *    其余字段（如 `litert.preferredDelegate`）放进 [InferenceConfigV2.frameworkExtras]，
 *    插件在 loadModel 时从 frameworkExtras 取回。
 * 3. **getDefaultSettings 类型转换**：AIDL 端返回 `Map<String, String>`，
 *    本类按 SettingField 的 type 把 String 解析回 Boolean / Number / String。
 *
 * @property descriptor 插件 AIDL 描述符。
 * @property pluginInfo 宿主侧 domain PluginInfo（与 descriptor.getPluginInfo() 一致，缓存一份避免重复 IPC）。
 */
class PluginDescriptorAdapter(
    private val descriptor: com.aibox.plugin.IPluginDescriptor,
    private val pluginInfo: PluginInfo
) : BackendDescriptor {

    private val logTag = "aibox.plugin.${pluginInfo.packageName}"

    override fun getName(): String = pluginInfo.packageName

    override fun getDisplayName(): String = pluginInfo.displayName

    override fun getVersion(): String = pluginInfo.version

    override fun describeCapabilities(): BackendCapabilities =
        ParcelableConverters.run {
            try {
                descriptor.describeCapabilities().toDomain()
            } catch (e: RemoteException) {
                BackendCapabilities(
                    supportedTasks = emptySet(),
                    supportedInputModalities = emptySet(),
                    supportedOutputModalities = emptySet(),
                    maxContextLength = 0,
                    maxImageResolution = null,
                    supportedModelFormats = emptyList(),
                    supportedQuantizations = emptyList(),
                    features = emptySet()
                )
            }
        }

    override fun getSettingsSchema(): List<SettingField> = try {
        descriptor.settingsSchema
            ?.mapNotNull { runCatching { ParcelableConverters.run { it.toDomain() } }.getOrNull() }
            ?: emptyList()
    } catch (e: RemoteException) {
        emptyList()
    }

    override fun getDefaultSettings(): Map<String, Any> = try {
        val schema = getSettingsSchema()
        val rawStrMap = descriptor.defaultSettings ?: emptyMap()
        // 按 schema 把 String 还原为原始类型；schema 没覆盖的兜底为 String
        rawStrMap.mapValues { (key, strValue) ->
            val field = schema.firstOrNull { it.key == key }
            if (field != null) {
                ParcelableConverters.run {
                    // 复用 SettingFieldEntry 转换逻辑：构造一个临时 entry 走 parseDefaultValue
                    com.aibox.plugin.SettingFieldEntry().apply {
                        type = field.type.ordinal
                        defaultValue = strValue
                    }.toDomain().defaultValue
                }
            } else {
                // 兜底：尝试解析为 Boolean / Number，否则保留 String
                strValue?.toBooleanStrictOrNull() ?: strValue?.toIntOrNull() ?: strValue
            }
        }
    } catch (e: RemoteException) {
        emptyMap()
    }

    /**
     * 把用户设置的 `Map<String, Any>` 解析为 [InferenceConfigV2]。
     *
     * 宿主侧实现，遵循"通用键名约定"：
     * - `taskType` (String → TaskType)
     * - `threads` (Number → Int?)
     * - `batchSize` / `maxTokens` / `contextLength` / `topK` (Number → Int)
     * - `temperature` / `topP` / `repeatPenalty` (Number → Float)
     * - `kvQuant` / `weightQuant` (String → QuantType?)
     *
     * 其余键（如 `litert.preferredDelegate`、`llamaCpp.nGpuLayers`）直接放入
     * [InferenceConfigV2.frameworkExtras]，由插件在 loadModel 时自行解析。
     *
     * 注：optimizations 字段本方法不解析；优化开关由 [PluginBackendAdapter.applyOptimizations]
     * 通过 AIDL 单独传给插件，不通过设置 schema 走（避免混淆"用户偏好"与"运行时优化"）。
     */
    override fun parseSettings(values: Map<String, Any>): InferenceConfigV2 {
        val taskType = (values["taskType"] as? String)
            ?.let { runCatching { TaskType.valueOf(it) }.getOrNull() }
            ?: TaskType.TEXT_GENERATION

        val threads = (values["threads"] as? Number)?.toInt()
        val batchSize = (values["batchSize"] as? Number)?.toInt() ?: 1
        val maxTokens = (values["maxTokens"] as? Number)?.toInt() ?: 512
        val contextLength = (values["contextLength"] as? Number)?.toInt() ?: 2048
        val kvQuant = (values["kvQuant"] as? String)
            ?.let { runCatching { QuantType.valueOf(it) }.getOrNull() }
            ?: QuantType.F16
        val weightQuant = (values["weightQuant"] as? String)
            ?.let { runCatching { QuantType.valueOf(it) }.getOrNull() }
        val temperature = (values["temperature"] as? Number)?.toFloat() ?: 0.7f
        val topP = (values["topP"] as? Number)?.toFloat() ?: 0.9f
        val topK = (values["topK"] as? Number)?.toInt() ?: 40
        val repeatPenalty = (values["repeatPenalty"] as? Number)?.toFloat() ?: 1.1f

        // 通用键名白名单：剩余字段（如 litert.preferredDelegate）放进 frameworkExtras
        val knownKeys = setOf(
            "taskType", "threads", "batchSize", "maxTokens", "contextLength",
            "kvQuant", "weightQuant", "temperature", "topP", "topK", "repeatPenalty"
        )
        val frameworkExtras: Map<String, Any> = values
            .filterKeys { it !in knownKeys }
            .mapValues { it.value }

        return InferenceConfigV2(
            taskType = taskType,
            threads = threads,
            batchSize = batchSize,
            maxTokens = maxTokens,
            contextLength = contextLength,
            kvQuant = kvQuant,
            weightQuant = weightQuant,
            temperature = temperature,
            topP = topP,
            topK = topK,
            repeatPenalty = repeatPenalty,
            optimizations = OptimizationFlags.DEFAULT,
            frameworkExtras = frameworkExtras
        )
    }
}
