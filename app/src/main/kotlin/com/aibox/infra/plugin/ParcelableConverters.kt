package com.aibox.infra.plugin

import com.aibox.domain.inference.BackendCapabilities
import com.aibox.domain.inference.BackendFeature
import com.aibox.domain.inference.InferenceConfigV2
import com.aibox.domain.inference.InferenceRequest
import com.aibox.domain.inference.InferenceResult
import com.aibox.domain.inference.Modality
import com.aibox.domain.inference.ModalityInput
import com.aibox.domain.inference.ModalityOutput
import com.aibox.domain.inference.OptimizationFlags
import com.aibox.domain.inference.QuantType
import com.aibox.domain.inference.TaskType
// AIDL 生成的 parcelable 类(同包名 com.aibox.plugin)
import com.aibox.plugin.BackendCapabilitiesParcel
import com.aibox.plugin.InferRequest
import com.aibox.plugin.InferResult
import com.aibox.plugin.LoadModelRequest
import com.aibox.plugin.LoadModelResult
import com.aibox.plugin.PluginInfo as PluginInfoParcel
import com.aibox.plugin.SettingFieldEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * AIDL parcelable 与 V2 domain 对象互转工具集。
 *
 * 本文件**只新增**，不修改任何 V2 类型；把 V2 不可序列化的字段（`Any?` / `Set<Enum>` / `sealed class`）
 * 拍平为 AIDL 兼容的 `int bitmask` / `String JSON`，由宿主 [PluginBackendAdapter] /
 * [PluginDescriptorAdapter] 调用。
 *
 * 设计原则：
 * 1. **零异常约定**：所有转换失败返回带 fallback 的"安全"对象（如 null / 默认值），不抛异常。
 *    调用方拿到结果后用 `success` 字段或 null 检查判断。
 * 2. **双向**：每对类型提供 `toParcel()` / `toDomain()` 互逆函数，便于宿主与插件互转。
 * 3. **JSON 兜底**：复杂结构（InferenceConfigV2 / ModalityInput / InferenceResult）用 JSON 字符串
 *    跨进程传递；AIDL 不支持 Map<String, Any> / sealed class，JSON 是最稳妥的兜底。
 *
 * 注：V2 类型没有 @Serializable 注解（本 Task 不能修改现有代码），所以 JSON 序列化
 * 采用**手写 JsonObject builder** 的方式，不依赖 @Serializable 编译器插件。
 */
object ParcelableConverters {

    /** AIDL PluginInfo → Kotlin PluginInfo。 */
    fun PluginInfoParcel.toDomain(): PluginInfo = PluginInfo(
        packageName = packageName ?: "",
        descriptorClass = descriptorClass ?: "",
        version = version ?: "",
        displayName = displayName ?: "",
        sizeBytes = sizeBytes
    )

    /** Kotlin PluginInfo → AIDL PluginInfo。 */
    fun PluginInfo.toParcel(): PluginInfoParcel =
        PluginInfoParcel().apply {
            packageName = this@toParcel.packageName
            descriptorClass = this@toParcel.descriptorClass
            version = this@toParcel.version
            displayName = this@toParcel.displayName
            sizeBytes = this@toParcel.sizeBytes
        }

    // =================================================================
    // SettingFieldEntry ↔ SettingField
    // =================================================================

    /**
     * AIDL SettingFieldEntry → Kotlin SettingField。
     *
     * AIDL 只保留可序列化的元数据；Kotlin 侧补回 lambda / Any 等运行期字段：
     * - defaultValue 根据 type 反序列化为 Boolean / String / Number。
     * - options 由 optionValues + optionLabels 组合。
     * - min/max/step 从 float 还原为 Number；若 type==SLIDER 且 step==0f 视为连续滑块（step=null）。
     * - visibleWhen 用默认 `{ true }`（AIDL 不能传 lambda）。
     * - enabled 用默认 true（AIDL 未保留）。
     * - keyboardHint 用默认 null（AIDL 未保留）。
     */
    fun com.aibox.plugin.SettingFieldEntry.toDomain(): SettingField {
        val fieldType = FieldType.entries.getOrElse(this.type) { FieldType.INPUT }
        return SettingField(
            key = key ?: "",
            type = fieldType,
            title = title ?: "",
            description = description ?: "",
            defaultValue = parseDefaultValue(defaultValue, fieldType),
            options = if (optionValues != null && optionLabels != null &&
                optionValues.isNotEmpty() && optionValues.size == optionLabels.size
            ) {
                optionValues.zip(optionLabels).map { (v, l) -> SettingOption(v, l) }
            } else null,
            min = if (fieldType == FieldType.SLIDER) minValue else null,
            max = if (fieldType == FieldType.SLIDER) maxValue else null,
            step = if (fieldType == FieldType.SLIDER && step > 0f) step else null,
            group = group,
            enabled = true,
            keyboardHint = null,
            visibleWhen = { true }
        )
    }

    /**
     * Kotlin SettingField → AIDL SettingFieldEntry。
     *
     * 损失信息：visibleWhen / enabled / keyboardHint 不传（AIDL 不支持）。
     */
    fun SettingField.toParcel(): com.aibox.plugin.SettingFieldEntry =
        com.aibox.plugin.SettingFieldEntry().apply {
            key = this@toParcel.key
            type = this@toParcel.type.ordinal
            title = this@toParcel.title
            description = this@toParcel.description
            defaultValue = this@toParcel.defaultValue?.toString() ?: ""
            optionValues = this@toParcel.options?.map { it.value } ?: emptyList()
            optionLabels = this@toParcel.options?.map { it.label } ?: emptyList()
            minValue = (this@toParcel.min as? Number)?.toFloat() ?: 0f
            maxValue = (this@toParcel.max as? Number)?.toFloat() ?: 0f
            step = (this@toParcel.step as? Number)?.toFloat() ?: 0f
            group = this@toParcel.group
        }

    /** 根据 FieldType 把 String 默认值解析回 Boolean / String / Float。 */
    private fun parseDefaultValue(raw: String?, type: FieldType): Any = when (type) {
        FieldType.SWITCH -> raw?.toBooleanStrictOrNull() ?: raw?.equals("true", ignoreCase = true) ?: false
        FieldType.SLIDER -> raw?.toFloatOrNull() ?: 0f
        FieldType.DROPDOWN, FieldType.INPUT, FieldType.GROUP -> raw ?: ""
    }

    // =================================================================
    // BackendCapabilitiesParcel ↔ BackendCapabilities
    // =================================================================

    /**
     * AIDL BackendCapabilitiesParcel → Kotlin BackendCapabilities。
     *
     * bitmask 还原为 Set<TaskType> / Set<Modality> / Set<BackendFeature>：
     * - 每位对应枚举 ordinal，bit i 表示 ordinal i 的项被支持。
     * - maxImageResolution: 0 / 负值视为 null（不支持图像输入输出）。
     */
    fun com.aibox.plugin.BackendCapabilitiesParcel.toDomain(): BackendCapabilities {
        val tasks = bitmaskToEnumSet(supportedTasksBitmask, TaskType.entries)
        val inputMods = bitmaskToEnumSet(supportedInputModalitiesBitmask, Modality.entries)
        val outputMods = bitmaskToEnumSet(supportedOutputModalitiesBitmask, Modality.entries)
        val feats = bitmaskToEnumSet(featuresBitmask, BackendFeature.entries)
        return BackendCapabilities(
            supportedTasks = tasks,
            supportedInputModalities = inputMods,
            supportedOutputModalities = outputMods,
            maxContextLength = maxContextLength,
            maxImageResolution = if (maxImageResolution > 0) maxImageResolution else null,
            supportedModelFormats = supportedModelFormats ?: emptyList(),
            supportedQuantizations = supportedQuantizations ?: emptyList(),
            features = feats
        )
    }

    /** Kotlin BackendCapabilities → AIDL BackendCapabilitiesParcel。 */
    fun BackendCapabilities.toParcel(): com.aibox.plugin.BackendCapabilitiesParcel =
        com.aibox.plugin.BackendCapabilitiesParcel().apply {
            supportedTasksBitmask = enumSetToBitmask(supportedTasks)
            supportedInputModalitiesBitmask = enumSetToBitmask(supportedInputModalities)
            supportedOutputModalitiesBitmask = enumSetToBitmask(supportedOutputModalities)
            maxContextLength = this@toParcel.maxContextLength
            maxImageResolution = this@toParcel.maxImageResolution ?: 0
            supportedModelFormats = this@toParcel.supportedModelFormats
            supportedQuantizations = this@toParcel.supportedQuantizations
            featuresBitmask = enumSetToBitmask(features)
        }

    /** 把 bitmask 还原为 Set<T>，T 必须有 ordinal。 */
    private fun <E : Enum<E>> bitmaskToEnumSet(mask: Int, all: List<E>): Set<E> {
        if (mask == 0) return emptySet()
        val result = LinkedHashSet<E>()
        all.forEach { e -> if ((mask shr e.ordinal) and 1 == 1) result.add(e) }
        return result
    }

    /** 把 Set<T> 编码为 bitmask，每位对应枚举 ordinal。 */
    private fun <E : Enum<E>> enumSetToBitmask(set: Set<E>): Int {
        var mask = 0
        set.forEach { e -> mask = mask or (1 shl e.ordinal) }
        return mask
    }

    // =================================================================
    // InferenceConfigV2 ↔ JSON String
    // =================================================================

    /** 把 InferenceConfigV2 序列化为 JSON 字符串（用于跨进程传递）。 */
    fun InferenceConfigV2.toJsonString(): String {
        val obj = buildJsonObject {
            put("taskType", JsonPrimitive((taskType.name).toString()))
            threads?.let { put("threads", JsonPrimitive((it).toString())) }
            put("batchSize", JsonPrimitive((batchSize).toString()))
            put("maxTokens", JsonPrimitive((maxTokens).toString()))
            put("contextLength", JsonPrimitive((contextLength).toString()))
            put("kvQuant", JsonPrimitive((kvQuant.name).toString()))
            weightQuant?.let { put("weightQuant", JsonPrimitive((it.name).toString())) }
            put("temperature", JsonPrimitive((temperature).toString()))
            put("topP", JsonPrimitive((topP).toString()))
            put("topK", JsonPrimitive((topK).toString()))
            put("repeatPenalty", JsonPrimitive((repeatPenalty).toString()))
            putJsonObject("optimizations") {
                put("preloadModel", JsonPrimitive((optimizations.preloadModel).toString()))
                put("warmupAfterLoad", JsonPrimitive((optimizations.warmupAfterLoad).toString()))
                put("preallocateKV", JsonPrimitive((optimizations.preallocateKV).toString()))
                put("prebuildSampler", JsonPrimitive((optimizations.prebuildSampler).toString()))
                put("streamFirstTokenImmediately", JsonPrimitive((optimizations.streamFirstTokenImmediately).toString()))
                put("forceFlashAttention", JsonPrimitive((optimizations.forceFlashAttention).toString()))
                put("aggressiveGraphOpt", JsonPrimitive((optimizations.aggressiveGraphOpt).toString()))
                put("continuousKVLayout", JsonPrimitive((optimizations.continuousKVLayout).toString()))
                put("reuseSampler", JsonPrimitive((optimizations.reuseSampler).toString()))
                put("zeroCopyStream", JsonPrimitive((optimizations.zeroCopyStream).toString()))
                put("enableGraphCache", JsonPrimitive((optimizations.enableGraphCache).toString()))
                put("thermalAwareThrottle", JsonPrimitive((optimizations.thermalAwareThrottle).toString()))
                put("idleUnloadTimeout", JsonPrimitive((optimizations.idleUnloadTimeout).toString()))
                put("lowPowerOnBattery", JsonPrimitive((optimizations.lowPowerOnBattery).toString()))
            }
            if (frameworkExtras.isNotEmpty()) {
                putJsonObject("frameworkExtras") {
                    frameworkExtras.forEach { (k, v) ->
                        when (v) {
                            is Boolean -> put(k, JsonPrimitive((v).toString()))
                            is Number -> put(k, JsonPrimitive((v).toString()))
                            is String -> put(k, JsonPrimitive(v))
                            else -> put(k, JsonPrimitive(v.toString()))
                        }
                    }
                }
            }
        }
        return obj.toString()
    }

    /** 把 JSON 字符串反序列化为 InferenceConfigV2；任何字段缺失或类型不符时用默认值。 */
    fun inferenceConfigFromJson(json: String?): InferenceConfigV2 {
        if (json.isNullOrBlank()) return InferenceConfigV2()
        return try {
            val obj = Json.parseToJsonElement(json).jsonObject
            val optObj = obj["optimizations"]?.jsonObject
            val fexObj = obj["frameworkExtras"]?.jsonObject
            InferenceConfigV2(
                taskType = obj["taskType"]?.jsonPrimitive?.contentOrNullSafe()
                    ?.let { runCatching { TaskType.valueOf(it) }.getOrNull() }
                    ?: TaskType.TEXT_GENERATION,
                threads = obj["threads"]?.jsonPrimitive?.intOrNull,
                batchSize = obj["batchSize"]?.jsonPrimitive?.intOrNull ?: 1,
                maxTokens = obj["maxTokens"]?.jsonPrimitive?.intOrNull ?: 512,
                contextLength = obj["contextLength"]?.jsonPrimitive?.intOrNull ?: 2048,
                kvQuant = obj["kvQuant"]?.jsonPrimitive?.contentOrNullSafe()
                    ?.let { runCatching { QuantType.valueOf(it) }.getOrNull() }
                    ?: QuantType.F16,
                weightQuant = obj["weightQuant"]?.jsonPrimitive?.contentOrNullSafe()
                    ?.let { runCatching { QuantType.valueOf(it) }.getOrNull() },
                temperature = obj["temperature"]?.jsonPrimitive?.floatOrNull ?: 0.7f,
                topP = obj["topP"]?.jsonPrimitive?.floatOrNull ?: 0.9f,
                topK = obj["topK"]?.jsonPrimitive?.intOrNull ?: 40,
                repeatPenalty = obj["repeatPenalty"]?.jsonPrimitive?.floatOrNull ?: 1.1f,
                optimizations = OptimizationFlags(
                    preloadModel = optObj?.get("preloadModel")?.jsonPrimitive?.booleanOrNullSafe() ?: true,
                    warmupAfterLoad = optObj?.get("warmupAfterLoad")?.jsonPrimitive?.booleanOrNullSafe() ?: true,
                    preallocateKV = optObj?.get("preallocateKV")?.jsonPrimitive?.booleanOrNullSafe() ?: true,
                    prebuildSampler = optObj?.get("prebuildSampler")?.jsonPrimitive?.booleanOrNullSafe() ?: true,
                    streamFirstTokenImmediately = optObj?.get("streamFirstTokenImmediately")?.jsonPrimitive?.booleanOrNullSafe() ?: true,
                    forceFlashAttention = optObj?.get("forceFlashAttention")?.jsonPrimitive?.booleanOrNullSafe() ?: true,
                    aggressiveGraphOpt = optObj?.get("aggressiveGraphOpt")?.jsonPrimitive?.booleanOrNullSafe() ?: true,
                    continuousKVLayout = optObj?.get("continuousKVLayout")?.jsonPrimitive?.booleanOrNullSafe() ?: true,
                    reuseSampler = optObj?.get("reuseSampler")?.jsonPrimitive?.booleanOrNullSafe() ?: true,
                    zeroCopyStream = optObj?.get("zeroCopyStream")?.jsonPrimitive?.booleanOrNullSafe() ?: true,
                    enableGraphCache = optObj?.get("enableGraphCache")?.jsonPrimitive?.booleanOrNullSafe() ?: true,
                    thermalAwareThrottle = optObj?.get("thermalAwareThrottle")?.jsonPrimitive?.booleanOrNullSafe() ?: true,
                    idleUnloadTimeout = optObj?.get("idleUnloadTimeout")?.jsonPrimitive?.longOrNull ?: 600_000L,
                    lowPowerOnBattery = optObj?.get("lowPowerOnBattery")?.jsonPrimitive?.booleanOrNullSafe() ?: false
                ),
                frameworkExtras = fexObj?.entries?.associate { (k, v) ->
                    k to jsonElementToAny(v)
                } ?: emptyMap()
            )
        } catch (_: Throwable) {
            InferenceConfigV2()
        }
    }

    /** 把 JsonElement 还原为 Any（Number / Boolean / String），用于 frameworkExtras 反序列化。 */
    private fun jsonElementToAny(e: JsonElement): Any = when (e) {
        is JsonPrimitive -> {
            if (e.isString) e.content
            else e.intOrNull ?: e.longOrNull ?: e.floatOrNull ?: e.booleanOrNullSafe() ?: e.content
        }
        is JsonObject -> e.toString()
        is JsonArray -> e.toString()
    }

    // =================================================================
    // ModalityInput ↔ JSON String（用于 InferRequest.inputJsons）
    // =================================================================

    /** 把一个 ModalityInput 序列化为 JSON 字符串。 */
    fun modalityInputToJson(input: ModalityInput): String = when (input) {
        is ModalityInput.Text -> buildJsonObject {
            put("type", JsonPrimitive("text"))
            put("content", JsonPrimitive((input.content).toString()))
        }.toString()

        is ModalityInput.Image -> buildJsonObject {
            put("type", JsonPrimitive("image"))
            put("uri", JsonPrimitive((input.uri).toString()))
            input.resolution?.let { put("resolution", JsonPrimitive((it).toString())) }
        }.toString()

        is ModalityInput.Audio -> buildJsonObject {
            put("type", JsonPrimitive("audio"))
            put("uri", JsonPrimitive((input.uri).toString()))
            put("sampleRate", JsonPrimitive((input.sampleRate).toString()))
        }.toString()

        is ModalityInput.Video -> buildJsonObject {
            put("type", JsonPrimitive("video"))
            put("uri", JsonPrimitive((input.uri).toString()))
        }.toString()
    }

    /** 把 JSON 字符串反序列化为 ModalityInput；解析失败返回 Text 空串占位。 */
    fun modalityInputFromJson(json: String?): ModalityInput {
        if (json.isNullOrBlank()) return ModalityInput.Text("")
        return try {
            val obj = Json.parseToJsonElement(json).jsonObject
            when (obj["type"]?.jsonPrimitive?.contentOrNullSafe()) {
                "text" -> ModalityInput.Text(obj["content"]?.jsonPrimitive?.content ?: "")
                "image" -> ModalityInput.Image(
                    uri = obj["uri"]?.jsonPrimitive?.content ?: "",
                    resolution = obj["resolution"]?.jsonPrimitive?.intOrNull
                )
                "audio" -> ModalityInput.Audio(
                    uri = obj["uri"]?.jsonPrimitive?.content ?: "",
                    sampleRate = obj["sampleRate"]?.jsonPrimitive?.intOrNull ?: 16000
                )
                "video" -> ModalityInput.Video(obj["uri"]?.jsonPrimitive?.content ?: "")
                else -> ModalityInput.Text("")
            }
        } catch (_: Throwable) {
            ModalityInput.Text("")
        }
    }

    /** 把整批 InferenceRequest.inputs 序列化为 JSON 字符串列表。 */
    fun List<ModalityInput>.toJsonList(): List<String> = map { modalityInputToJson(it) }

    /** 把 JSON 字符串列表反序列化为 ModalityInput 列表。 */
    fun jsonListToInputs(jsons: List<String>?): List<ModalityInput> =
        jsons?.map { modalityInputFromJson(it) } ?: emptyList()

    // =================================================================
    // InferenceResult ↔ JSON String
    // =================================================================

    /** 把 InferenceResult 序列化为 JSON 字符串（用于 InferResult.resultJson）。 */
    fun inferenceResultToJson(result: InferenceResult): String = when (result) {
        is InferenceResult.Text -> buildJsonObject {
            put("type", JsonPrimitive("text"))
            put("content", JsonPrimitive((result.content).toString()))
        }.toString()

        is InferenceResult.Image -> buildJsonObject {
            put("type", JsonPrimitive("image"))
            put("uri", JsonPrimitive((result.uri).toString()))
        }.toString()

        is InferenceResult.Audio -> buildJsonObject {
            put("type", JsonPrimitive("audio"))
            put("uri", JsonPrimitive((result.uri).toString()))
        }.toString()

        is InferenceResult.Multimodal -> buildJsonObject {
            put("type", JsonPrimitive("multimodal"))
            putJsonArray("parts") {
                result.parts.forEach { add(JsonPrimitive(modalityOutputToJson(it))) }
            }
        }.toString()

        is InferenceResult.Embedding -> buildJsonObject {
            put("type", JsonPrimitive("embedding"))
            putJsonArray("vector") {
                result.vector.forEach { add(JsonPrimitive((it).toString())) }
            }
        }.toString()

        is InferenceResult.StreamChunk -> buildJsonObject {
            put("type", JsonPrimitive("streamChunk"))
            put("done", JsonPrimitive((result.done).toString()))
            put("partial", JsonPrimitive(modalityOutputToJson(result.partial)))
        }.toString()
    }

    /** 把 JSON 字符串反序列化为 InferenceResult；解析失败返回 Text 错误描述。 */
    fun inferenceResultFromJson(json: String?): InferenceResult {
        if (json.isNullOrBlank()) return InferenceResult.Text("[empty result]")
        return try {
            val obj = Json.parseToJsonElement(json).jsonObject
            when (obj["type"]?.jsonPrimitive?.contentOrNullSafe()) {
                "text" -> InferenceResult.Text(obj["content"]?.jsonPrimitive?.content ?: "")
                "image" -> InferenceResult.Image(obj["uri"]?.jsonPrimitive?.content ?: "")
                "audio" -> InferenceResult.Audio(obj["uri"]?.jsonPrimitive?.content ?: "")
                "multimodal" -> {
                    val parts = obj["parts"]?.jsonArray?.mapNotNull { e ->
                        runCatching { modalityOutputFromJson(e.toString()) }.getOrNull()
                    } ?: emptyList()
                    InferenceResult.Multimodal(parts)
                }
                "embedding" -> {
                    val vec = obj["vector"]?.jsonArray?.mapNotNull {
                        it.jsonPrimitive.floatOrNull
                    }?.toFloatArray() ?: FloatArray(0)
                    InferenceResult.Embedding(vec)
                }
                "streamChunk" -> {
                    val done = obj["done"]?.jsonPrimitive?.booleanOrNullSafe() ?: true
                    val partial = obj["partial"]?.let { modalityOutputFromJson(it.toString()) }
                        ?: ModalityOutput.Text("")
                    InferenceResult.StreamChunk(partial, done)
                }
                else -> InferenceResult.Text("[unknown result type]")
            }
        } catch (t: Throwable) {
            InferenceResult.Text("[parse error: ${t.message}]")
        }
    }

    // =================================================================
    // ModalityOutput ↔ JSON String（用于 IStreamCallback.onChunk partialJson）
    // =================================================================

    /** 把 ModalityOutput 序列化为 JSON 字符串。 */
    fun modalityOutputToJson(output: ModalityOutput): String = when (output) {
        is ModalityOutput.Text -> buildJsonObject {
            put("type", JsonPrimitive("text"))
            put("content", JsonPrimitive((output.content).toString()))
        }.toString()
        is ModalityOutput.Image -> buildJsonObject {
            put("type", JsonPrimitive("image"))
            put("uri", JsonPrimitive((output.uri).toString()))
        }.toString()
        is ModalityOutput.Audio -> buildJsonObject {
            put("type", JsonPrimitive("audio"))
            put("uri", JsonPrimitive((output.uri).toString()))
        }.toString()
    }

    /** 把 JSON 字符串反序列化为 ModalityOutput；失败返回 Text 空串占位。 */
    fun modalityOutputFromJson(json: String?): ModalityOutput {
        if (json.isNullOrBlank()) return ModalityOutput.Text("")
        return try {
            val obj = Json.parseToJsonElement(json).jsonObject
            when (obj["type"]?.jsonPrimitive?.contentOrNullSafe()) {
                "text" -> ModalityOutput.Text(obj["content"]?.jsonPrimitive?.content ?: "")
                "image" -> ModalityOutput.Image(obj["uri"]?.jsonPrimitive?.content ?: "")
                "audio" -> ModalityOutput.Audio(obj["uri"]?.jsonPrimitive?.content ?: "")
                else -> ModalityOutput.Text("")
            }
        } catch (_: Throwable) {
            ModalityOutput.Text("")
        }
    }

    // =================================================================
    // OptimizationFlags ↔ Map<String, Boolean>（用于 IPluginInterface.applyOptimizations）
    // =================================================================

    /** 把 OptimizationFlags 序列化为扁平 Map<String, String>（AIDL 不支持 Map<String,Boolean>，用 "true"/"false" 字符串表示）。 */
    fun OptimizationFlags.toFlagMap(): Map<String, String> = mapOf(
        "preloadModel" to preloadModel.toString(),
        "warmupAfterLoad" to warmupAfterLoad.toString(),
        "preallocateKV" to preallocateKV.toString(),
        "prebuildSampler" to prebuildSampler.toString(),
        "streamFirstTokenImmediately" to streamFirstTokenImmediately.toString(),
        "forceFlashAttention" to forceFlashAttention.toString(),
        "aggressiveGraphOpt" to aggressiveGraphOpt.toString(),
        "continuousKVLayout" to continuousKVLayout.toString(),
        "reuseSampler" to reuseSampler.toString(),
        "zeroCopyStream" to zeroCopyStream.toString(),
        "enableGraphCache" to enableGraphCache.toString(),
        "thermalAwareThrottle" to thermalAwareThrottle.toString(),
        "lowPowerOnBattery" to lowPowerOnBattery.toString()
    )

    // =================================================================
    // InferenceRequest ↔ AIDL InferRequest
    // =================================================================

    /**
     * 把 V2 [InferenceRequest] 转换为 AIDL [com.aibox.plugin.InferRequest]。
     *
     * 注意：AIDL 端的 modelHandle 由调用方单独传给 [com.aibox.plugin.IPluginInterface.infer]
     * 的第二参数；这里只填 taskId/taskType/modelId/inputs/config/stream/sessionId。
     */
    fun InferenceRequest.toInferParcel(): com.aibox.plugin.InferRequest =
        com.aibox.plugin.InferRequest().apply {
            taskId = this@toInferParcel.taskId
            taskType = this@toInferParcel.taskType.ordinal
            modelId = this@toInferParcel.modelId
            inputJsons = this@toInferParcel.inputs.toJsonList()
            configJson = this@toInferParcel.config.toJsonString()
            stream = this@toInferParcel.stream
            sessionId = this@toInferParcel.sessionId
        }

    /**
     * 把 AIDL [com.aibox.plugin.InferRequest] 转换为 V2 [InferenceRequest]。
     *
     * 调用方需要单独把 modelHandle 传给 [InferenceBackendV2.infer] 的 LoadedModel 参数；
     * 本方法只重建 InferenceRequest，不含 modelHandle。
     */
    fun com.aibox.plugin.InferRequest.toDomain(): InferenceRequest = InferenceRequest(
        taskId = taskId ?: "",
        taskType = taskType.let { TaskType.entries.getOrElse(it) { TaskType.TEXT_GENERATION } },
        modelId = modelId ?: "",
        inputs = jsonListToInputs(inputJsons),
        config = inferenceConfigFromJson(configJson),
        stream = stream,
        sessionId = sessionId
    )

    // =================================================================
    // 兼容性辅助（kotlinx-serialization JsonPrimitive 的 nullable 版本）
    // =================================================================

    /**
     * kotlinx-serialization 1.7.x 的 JsonPrimitive.content 不为空（除非 JsonNull）；
     * 这里加一层 Safe-call 便于 ?: 兜底。
     */
    private fun JsonPrimitive.contentOrNullSafe(): String? =
        if (this == kotlinx.serialization.json.JsonNull) null else content

    /** Boolean? 兜底；JsonNull 或非 bool 字段时返回 null。 */
    private fun JsonPrimitive.booleanOrNullSafe(): Boolean? = when {
        this == kotlinx.serialization.json.JsonNull -> null
        isString -> content.toBooleanStrictOrNull()
        else -> content.toBooleanStrictOrNull()
    }
}
