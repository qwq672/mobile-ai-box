package com.aibox.infra.module.manifest

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

/**
 * AIBox 模块 manifest 解析与数据模型。
 *
 * - 一个模块 APK 在 `assets/module_manifest/manifest.json` 提供本契约。
 * - 宿主通过 [ModuleManifestParser] 解析,通过 [DependencyResolver] 做拓扑排序。
 * - SONAME 去重 + 引用计数 dlopen 由 [com.aibox.infra.module.loader.NativeLibLoader] 完成。
 *
 * 设计:
 * - **零异常约定**:解析失败返回 [ParseResult.Error],不抛异常。
 * - **不依赖 Android Context**:可单测。
 * - **严格模式**:未知字段拒绝(additionalProperties=false 已在 schema 强制)。
 *
 * Manifest schema: app/src/main/assets/module_manifest/schema-v1.json
 *
 * @see ModuleManifestParser
 */

@Serializable
enum class ModuleTypeDto(val label: String) {
    @SerialName("backend_runtime")   BACKEND_RUNTIME("L1 共享运行时"),
    @SerialName("backend_provider")   BACKEND_PROVIDER("L2 提供者"),
    @SerialName("backend_consumer")   BACKEND_CONSUMER("L3 消费者"),
    @SerialName("chat")               CHAT("聊天模块"),
    @SerialName("agent")              AGENT("Agent 模块"),
    @SerialName("roleplay")           ROLEPLAY("角色扮演模块"),
    @SerialName("translate")          TRANSLATE("翻译模块"),
    @SerialName("tts")                TTS("TTS 模块"),
    @SerialName("asr")                ASR("ASR 模块"),
    @SerialName("image_gen")          IMAGE_GEN("图像生成模块"),
    @SerialName("embedding")          EMBEDDING("嵌入模型模块"),
    @SerialName("utility")            UTILITY("工具模块"),
    ;
    companion object {
        /** LiteRT 类模块(可能需要 safe_context)。 */
        fun isLiteRtLike() = setOf(BACKEND_CONSUMER, EMBEDDING, CHAT, AGENT)
    }
}

@Serializable
enum class AbiDto(val androidName: String) {
    @SerialName("arm64-v8a")   ARM64_V8A("arm64-v8a"),
    @SerialName("armeabi-v7a") ARMEABI_V7A("armeabi-v7a"),
    @SerialName("x86_64")      X86_64("x86_64"),
    @SerialName("x86")         X86("x86"),
}

@Serializable
enum class SafeContextMetricDto {
    @SerialName("cosine_similarity")  COSINE_SIMILARITY,
    @SerialName("exact_match")        EXACT_MATCH,
    @SerialName("label_match")        LABEL_MATCH,
    @SerialName("perplexity_ratio")   PERPLEXITY_RATIO,
}

/** 依赖的 native 库声明。 */
@Serializable
data class LibDependencyDto(
    /** 库的 SONAME,如 "libonnxruntime.so.1.16"。构建期 readelf -d 验证一致。 */
    @SerialName("soname")            val soname: String,
    /** true=L1 共享运行时(RTLD_GLOBAL + 引用计数);false=L2 模块私有(RTLD_LOCAL)。 */
    @SerialName("shared")            val shared: Boolean,
    /** 最低可接受版本(从 SONAME 解析)。 */
    @SerialName("min_version")       val minVersion: String? = null,
    /** 版本冲突时是否允许独立子进程隔离加载(兜底)。 */
    @SerialName("fallback_isolated_process")
    val fallbackIsolatedProcess: Boolean = false
)

/** 依赖的其他模块。 */
@Serializable
data class ModuleDependencyDto(
    @SerialName("module_id")   val moduleId: String,
    @SerialName("min_version") val minVersion: String
)

/** 自带 native 库描述(从 lib/<abi>/ 扫描)。 */
@Serializable
data class NativeLibBundledDto(
    @SerialName("soname")     val soname: String,
    @SerialName("abi")        val abi: AbiDto,
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    @SerialName("sha256")     val sha256: String = ""
)

/** LiteRT 模块专属:模型在长输入下乱输出的实测临界点。 */
@Serializable
data class SafeContextDto(
    /** true=模块加载时跑 benchmark 实测并缓存;false=用 default_* 静态值。 */
    @SerialName("auto_detect")      val autoDetect: Boolean,
    /** 默认安全上下文(可放心使用的最大 token 数)。 */
    @SerialName("default_safe")     val defaultSafe: Int? = null,
    /** 超过此值弹警告(仍允许继续)。 */
    @SerialName("default_warn")     val defaultWarn: Int? = null,
    /** 强烈不建议超过的值。 */
    @SerialName("default_block")    val defaultBlock: Int? = null,
    /** auto_detect=true 时使用的标准语料路径。 */
    @SerialName("benchmark_corpus") val benchmarkCorpus: String? = null,
    /** benchmark 评估指标。 */
    @SerialName("metric")           val metric: SafeContextMetricDto? = null,
    /** metric 跌破此阈值即判定为乱输出临界点。 */
    @SerialName("threshold")        val threshold: Float? = null,
    /** 缓存 key,推荐 'model_sha256:<前 16 字节>' 或 'module_id:version'。 */
    @SerialName("cache_key")        val cacheKey: String? = null,
    /** 本 manifest 对应的模型标识(如 'gemma-4-e2b')。 */
    @SerialName("model_id")         val modelId: String? = null
)

/** 完整 manifest。 */
@Serializable
data class ModuleManifestDto(
    @SerialName("schema_version")       val schemaVersion: String,
    @SerialName("module_id")            val moduleId: String,
    @SerialName("version")               val version: String,
    @SerialName("module_type")           val moduleType: ModuleTypeDto,
    @SerialName("min_host_api")          val minHostApi: Int,
    @SerialName("abi_filters")           val abiFilters: List<AbiDto>,
    @SerialName("depends_on_libs")       val dependsOnLibs: List<LibDependencyDto> = emptyList(),
    @SerialName("depends_on_modules")   val dependsOnModules: List<ModuleDependencyDto> = emptyList(),
    @SerialName("exports")               val exports: List<String> = emptyList(),
    @SerialName("interface_aidl")        val interfaceAidl: String? = null,
    @SerialName("capabilities")         val capabilities: List<String> = emptyList(),
    @SerialName("safe_context")         val safeContext: SafeContextDto? = null,
    @SerialName("native_libs_bundled")  val nativeLibsBundled: List<NativeLibBundledDto> = emptyList(),
    @SerialName("signature")            val signature: String? = null
)

/** 解析结果。零异常约定。 */
sealed class ParseResult {
    data class Ok(val manifest: ModuleManifestDto) : ParseResult()
    data class Err(val reason: String, val detail: String? = null) : ParseResult()
}

/**
 * Manifest 解析器。
 *
 * 用法:
 * ```
 * val parser = ModuleManifestParser(strict = true)
 * when (val r = parser.parse(jsonText)) {
 *   is ParseResult.Ok    -> useManifest(r.manifest)
 *   is ParseResult.Err   -> logError(r.reason, r.detail)
 * }
 * ```
 *
 * 验证项:
 * 1. schema_version 必须 == "1.0.0"
 * 2. module_id 必须匹配 `^[a-z][a-z0-9_-]*(\.[a-z0-9_-]+)+$`(至少 1 个点)
 * 3. version 必须匹配语义化版本正则
 * 4. exports 每项必须匹配 `^aibox_[a-z][a-z0-9_]*_v\d+$`
 * 5. safe_context.auto_detect=true 时必须有 default_safe 或 benchmark_corpus + metric + threshold
 * 6. depends_on_libs[].shared=true 时必须有 soname 且建议 min_version
 * 7. abi_filters 至少 1 个
 */
class ModuleManifestParser(
    /** 严格模式:true=未知字段拒绝;false=容忍。建议生产环境用 true。 */
    private val strict: Boolean = true
) {

    private val json = Json {
        ignoreUnknownKeys = !strict
        isLenient = !strict
        coerceInputValues = true
        explicitNulls = false
    }

    private val moduleIdRegex = Regex("^[a-z][a-z0-9_-]*(\\.[a-z0-9_-]+)+$")
    private val versionRegex = Regex("^\\d+\\.\\d+\\.\\d+(-[a-z0-9.]+)?$")
    private val exportNameRegex = Regex("^aibox_[a-z][a-z0-9_]*_v\\d+$")

    fun parse(text: String): ParseResult {
        // 1. JSON 解析(用于早 fail:明显格式错误直接返回,避免被反序列化阶段吞掉)
        try {
            json.parseToJsonElement(text).let { /* 仅做语法验证,实际解析走 decodeFromString */ }
        } catch (e: Exception) {
            return ParseResult.Err("JSON 解析失败", e.message)
        }

        // 2. 反序列化到 DTO
        val manifest: ModuleManifestDto = try {
            json.decodeFromString(ModuleManifestDto.serializer(), text)
        } catch (e: Exception) {
            return ParseResult.Err("反序列化失败", e.message)
        }

        // 3. 字段验证
        return validate(manifest)
    }

    private fun validate(m: ModuleManifestDto): ParseResult {
        // 3.1 schema 版本
        if (m.schemaVersion != "1.0.0") {
            return ParseResult.Err(
                "schema_version 不被支持",
                "实际值 '${m.schemaVersion}',当前仅支持 '1.0.0'"
            )
        }

        // 3.2 module_id 格式
        if (!moduleIdRegex.matches(m.moduleId)) {
            return ParseResult.Err(
                "module_id 格式不合法",
                "应匹配 ^[a-z][a-z0-9_-]*(\\.[a-z0-9_-]+)+$,实际 '${m.moduleId}'"
            )
        }

        // 3.3 version 格式
        if (!versionRegex.matches(m.version)) {
            return ParseResult.Err(
                "version 格式不合法",
                "应匹配语义化版本 \\d+.\\d+.\\d+,实际 '${m.version}'"
            )
        }

        // 3.4 abi_filters 至少 1 个
        if (m.abiFilters.isEmpty()) {
            return ParseResult.Err("abi_filters 不能为空")
        }

        // 3.5 exports 命名规范
        for (e in m.exports) {
            if (!exportNameRegex.matches(e)) {
                return ParseResult.Err(
                    "exports 命名不合法",
                    "应匹配 ^aibox_[a-z][a-z0-9_]*_v\\d+$,实际 '$e'"
                )
            }
        }

        // 3.6 depends_on_libs 校验
        val sonameSet = mutableSetOf<String>()
        for (dep in m.dependsOnLibs) {
            if (dep.soname.isBlank()) {
                return ParseResult.Err("depends_on_libs[].soname 不能为空")
            }
            if (!sonameSet.add(dep.soname)) {
                return ParseResult.Err(
                    "depends_on_libs 重复",
                    "soname '${dep.soname}' 声明多次"
                )
            }
            // shared=true 建议提供 min_version
            if (dep.shared && dep.minVersion.isNullOrBlank()) {
                // 警告级别,不阻断
                // 可选:改为 Err 让开发者必须填
            }
        }

        // 3.7 depends_on_modules 不能自引用
        for (dep in m.dependsOnModules) {
            if (dep.moduleId == m.moduleId) {
                return ParseResult.Err("depends_on_modules 不能自引用", "module_id='${m.moduleId}'")
            }
        }

        // 3.8 safe_context 校验
        m.safeContext?.let { sc ->
            if (sc.autoDetect) {
                if (sc.benchmarkCorpus.isNullOrBlank()) {
                    return ParseResult.Err(
                        "safe_context.auto_detect=true 时必须提供 benchmark_corpus",
                        "需指定标准语料路径"
                    )
                }
                if (sc.metric == null || sc.threshold == null) {
                    return ParseResult.Err(
                        "safe_context.auto_detect=true 时必须提供 metric + threshold",
                        "metric=${sc.metric}, threshold=${sc.threshold}"
                    )
                }
                if (sc.threshold !in 0f..1f) {
                    return ParseResult.Err(
                        "safe_context.threshold 必须在 [0, 1] 区间",
                        "实际 ${sc.threshold}"
                    )
                }
            } else {
                // 静态模式必须有 default_safe
                if (sc.defaultSafe == null || sc.defaultSafe < 64) {
                    return ParseResult.Err(
                        "safe_context.auto_detect=false 时必须提供 default_safe 且 ≥ 64",
                        "实际 ${sc.defaultSafe}"
                    )
                }
                if (sc.defaultWarn != null && sc.defaultWarn <= sc.defaultSafe) {
                    return ParseResult.Err(
                        "safe_context.default_warn 必须大于 default_safe",
                        "default_safe=${sc.defaultSafe}, default_warn=${sc.defaultWarn}"
                    )
                }
                if (sc.defaultBlock != null && sc.defaultWarn != null && sc.defaultBlock <= sc.defaultWarn) {
                    return ParseResult.Err(
                        "safe_context.default_block 必须大于 default_warn",
                        "default_warn=${sc.defaultWarn}, default_block=${sc.defaultBlock}"
                    )
                }
            }
        }

        // 3.9 module_type 与 safe_context 一致性:只有 LiteRT 类才能声明 safe_context
        m.safeContext?.let {
            if (!ModuleTypeDto.isLiteRtLike().contains(m.moduleType)) {
                return ParseResult.Err(
                    "safe_context 只允许在 LiteRT 类模块声明",
                    "当前 module_type='${m.moduleType}',不在允许集合 [BACKEND_CONSUMER, EMBEDDING, CHAT, AGENT]"
                )
            }
        }

        return ParseResult.Ok(m)
    }
}
