package com.aibox.infra.module.aibox

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `.aibox` 模块包的数据模型。
 *
 * `.aibox` 只承载“内容”：
 * - 模型 / LoRA / VAE
 * - 工作流 / prompt / 主题 / 语音
 * - WASM 工具
 * - 可信 native payload
 *
 * 完整第三方可执行插件仍然使用 APK；`.aibox` 不替代 APK 的安装、UID、权限与进程隔离。
 */
@Serializable
enum class AiboxPackageType(val label: String) {
    @SerialName("model-bundle")   MODEL_BUNDLE("模型包"),
    @SerialName("lora-pack")      LORA_PACK("LoRA 包"),
    @SerialName("workflow-pack")  WORKFLOW_PACK("工作流包"),
    @SerialName("theme-pack")     THEME_PACK("主题包"),
    @SerialName("voice-pack")     VOICE_PACK("语音包"),
    @SerialName("tool-pack")      TOOL_PACK("工具声明包"),
    @SerialName("wasm-plugin")    WASM_PLUGIN("WASM 插件"),
    @SerialName("native-payload") NATIVE_PAYLOAD("可信 native payload"),
    @SerialName("apk-payload")    APK_PAYLOAD("APK payload"),
    @SerialName("unknown")        UNKNOWN("未知");

    companion object {
        /** 这些类型会执行代码或加载 native，默认要求签名。 */
        fun requiresSignature(type: AiboxPackageType): Boolean =
            type == WASM_PLUGIN || type == NATIVE_PAYLOAD || type == APK_PAYLOAD
    }
}

@Serializable
data class AiboxPayloadEntry(
    /** 语义角色：diffusion / vae / text_encoder / lora / model / workflow / wasm / native。 */
    val role: String,
    /** 相对包内路径，禁止绝对路径与 `..`。 */
    val path: String,
    @SerialName("sha256") val sha256: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0L,
    val required: Boolean = true,
    /** native payload 使用。 */
    val abi: String? = null
)

@Serializable
data class AiboxModuleDependency(
    val id: String,
    val version: String = "*",
    val required: Boolean = true,
    @SerialName("prompt_at") val promptAt: String = "install",
    val fallback: String? = null
)

@Serializable
data class AiboxDependencies(
    val modules: List<AiboxModuleDependency> = emptyList(),
    val capabilities: List<String> = emptyList()
)

@Serializable
data class AiboxSignature(
    val alg: String = "ed25519",
    @SerialName("key_id") val keyId: String = ""
)

@Serializable
data class AiboxManifest(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val id: String,
    val type: AiboxPackageType = AiboxPackageType.UNKNOWN,
    val version: String,
    val name: String = "",
    val description: String = "",
    val capabilities: List<String> = emptyList(),
    val payload: List<AiboxPayloadEntry> = emptyList(),
    val dependencies: AiboxDependencies = AiboxDependencies(),
    val signature: AiboxSignature? = null
)

/** 包内一个条目（不解压内容）。 */
data class AiboxEntryInfo(
    val path: String,
    val sizeBytes: Long,
    val compressedSizeBytes: Long,
    val isDirectory: Boolean
)

/** 验签钩子；具体密钥/信任策略由宿主提供。 */
fun interface AiboxSignatureVerifier {
    /**
     * @param manifestBytes 原始 manifest.json 字节
     * @param signatureBytes signatures/manifest.sig 内容
     * @param signature manifest 中声明的签名信息
     */
    fun verify(manifestBytes: ByteArray, signatureBytes: ByteArray, signature: AiboxSignature): Boolean
}

data class AiboxPackage(
    val file: java.io.File,
    val manifest: AiboxManifest,
    val entries: List<AiboxEntryInfo>,
    val signaturePresent: Boolean,
    val warnings: List<String>
)

data class ExtractedAiboxPackage(
    val manifest: AiboxManifest,
    val rootDir: java.io.File,
    val extractedFiles: Map<String, java.io.File>
)

sealed class AiboxResult<out T> {
    data class Ok<T>(val value: T) : AiboxResult<T>()
    data class Err(val code: AiboxErrorCode, val reason: String, val detail: String? = null) :
        AiboxResult<Nothing>()
}

enum class AiboxErrorCode {
    NOT_FOUND,
    TOO_LARGE,
    TOO_MANY_ENTRIES,
    UNSAFE_PATH,
    DUPLICATE_ENTRY,
    MANIFEST_MISSING,
    MANIFEST_TOO_LARGE,
    MANIFEST_INVALID,
    UNSUPPORTED_TYPE,
    PAYLOAD_MISSING,
    CHECKSUM_MISMATCH,
    SIGNATURE_REQUIRED,
    SIGNATURE_INVALID,
    EXTRACT_FAILED,
    IO_ERROR
}
