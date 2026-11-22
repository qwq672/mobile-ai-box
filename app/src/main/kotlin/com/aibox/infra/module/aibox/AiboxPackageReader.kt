package com.aibox.infra.module.aibox

import com.aibox.infra.module.manifest.SemVer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * `.aibox` 包读取、校验与安全解压。
 *
 * 只做“内容包”：不安装 APK、不申请权限、不执行任意代码。
 * 执行型 payload（WASM/native）默认要求签名，并且由宿主决定是否信任。
 *
 * 零异常约定：所有失败返回 [AiboxResult.Err]。
 */
class AiboxPackageReader(
    private val limits: Limits = Limits(),
    private val signatureVerifier: AiboxSignatureVerifier? = null,
    /** 代码类 payload 是否强制要求签名。 */
    private val requireSignatureForCode: Boolean = true
) {

    data class Limits(
        val maxArchiveBytes: Long = 8L * 1024 * 1024 * 1024,
        val maxUncompressedBytes: Long = 16L * 1024 * 1024 * 1024,
        val maxEntryBytes: Long = 8L * 1024 * 1024 * 1024,
        val maxEntries: Int = 4096,
        val maxManifestBytes: Long = 1024 * 1024
    )

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = false
    }

    private val idRegex = Regex("^[a-z][a-z0-9_-]*(\\.[a-z0-9_-]+)+$")
    private val sha256Regex = Regex("^[a-f0-9]{64}$")

    fun inspect(file: File, verifyHashes: Boolean = true): AiboxResult<AiboxPackage> {
        if (!file.isFile) {
            return AiboxResult.Err(AiboxErrorCode.NOT_FOUND, "文件不存在", file.absolutePath)
        }
        if (file.length() > limits.maxArchiveBytes) {
            return AiboxResult.Err(
                AiboxErrorCode.TOO_LARGE,
                "包体积超过限制",
                "${file.length()} > ${limits.maxArchiveBytes}"
            )
        }

        return try {
            ZipFile(file).use { zip ->
                val entries = zip.entries().toList()
                if (entries.size > limits.maxEntries) {
                    return AiboxResult.Err(
                        AiboxErrorCode.TOO_MANY_ENTRIES,
                        "条目数超过限制",
                        "${entries.size} > ${limits.maxEntries}"
                    )
                }

                var total = 0L
                val seen = mutableSetOf<String>()
                for (e in entries) {
                    val normalized = normalizePath(e.name)
                        ?: return AiboxResult.Err(
                            AiboxErrorCode.UNSAFE_PATH,
                            "包内路径不安全",
                            e.name
                        )
                    if (!seen.add(normalized)) {
                        return AiboxResult.Err(
                            AiboxErrorCode.DUPLICATE_ENTRY,
                            "包内路径重复",
                            normalized
                        )
                    }
                    if (!e.isDirectory) {
                        if (e.size < 0 || e.size > limits.maxEntryBytes) {
                            return AiboxResult.Err(
                                AiboxErrorCode.TOO_LARGE,
                                "单个条目超过限制",
                                "${e.name}: ${e.size}"
                            )
                        }
                        total += e.size
                        if (total > limits.maxUncompressedBytes) {
                            return AiboxResult.Err(
                                AiboxErrorCode.TOO_LARGE,
                                "解压后总量超过限制",
                                "$total > ${limits.maxUncompressedBytes}"
                            )
                        }
                    }
                }

                val manifestEntry = zip.getEntry(MANIFEST_PATH)
                    ?: return AiboxResult.Err(
                        AiboxErrorCode.MANIFEST_MISSING,
                        "缺少 manifest.json"
                    )
                if (manifestEntry.size > limits.maxManifestBytes) {
                    return AiboxResult.Err(
                        AiboxErrorCode.MANIFEST_TOO_LARGE,
                        "manifest.json 过大",
                        "${manifestEntry.size}"
                    )
                }

                val manifestBytes = zip.getInputStream(manifestEntry).use { it.readBytes() }
                val manifest = try {
                    json.decodeFromString(AiboxManifest.serializer(), manifestBytes.toString(Charsets.UTF_8))
                } catch (e: Throwable) {
                    return AiboxResult.Err(
                        AiboxErrorCode.MANIFEST_INVALID,
                        "manifest.json 解析失败",
                        e.message
                    )
                }

                val manifestError = validateManifest(manifest)
                if (manifestError != null) return manifestError

                val warnings = mutableListOf<String>()
                val signatureEntry = zip.getEntry(SIGNATURE_PATH)
                val signaturePresent = signatureEntry != null

                if (manifest.type.let { AiboxPackageType.requiresSignature(it) } &&
                    requireSignatureForCode && !signaturePresent
                ) {
                    return AiboxResult.Err(
                        AiboxErrorCode.SIGNATURE_REQUIRED,
                        "该类型要求签名",
                        manifest.type.name
                    )
                }

                if (signaturePresent) {
                    val sig = manifest.signature
                    if (sig == null) {
                        warnings += "存在 signatures/manifest.sig，但 manifest 未声明签名信息"
                    } else {
                        val sigBytes = zip.getInputStream(signatureEntry).use { it.readBytes() }
                        val verifier = signatureVerifier
                        if (verifier == null) {
                            warnings += "已发现签名，但宿主未配置验签器，签名尚未校验"
                        } else if (!verifier.verify(manifestBytes, sigBytes, sig)) {
                            return AiboxResult.Err(
                                AiboxErrorCode.SIGNATURE_INVALID,
                                "签名校验失败",
                                sig.keyId
                            )
                        }
                    }
                } else if (manifest.type.let { AiboxPackageType.requiresSignature(it) }) {
                    warnings += "代码类 payload 未签名"
                }

                // 可选 checksums.json：逐文件校验
                val checksums = readChecksums(zip, warnings)
                if (verifyHashes) {
                    val payloadError = verifyPayloads(zip, manifest, checksums)
                    if (payloadError != null) return payloadError
                }

                AiboxResult.Ok(
                    AiboxPackage(
                        file = file,
                        manifest = manifest,
                        entries = entries.map {
                            AiboxEntryInfo(
                                path = normalizePath(it.name) ?: it.name,
                                sizeBytes = it.size,
                                compressedSizeBytes = it.compressedSize.toLong(),
                                isDirectory = it.isDirectory
                            )
                        },
                        signaturePresent = signaturePresent,
                        warnings = warnings.distinct()
                    )
                )
            }
        } catch (e: Throwable) {
            AiboxResult.Err(AiboxErrorCode.IO_ERROR, "读取包失败", e.message)
        }
    }

    fun extract(file: File, destDir: File): AiboxResult<ExtractedAiboxPackage> {
        val inspected = inspect(file)
        val pkg = when (inspected) {
            is AiboxResult.Err -> return inspected
            is AiboxResult.Ok -> inspected.value
        }

        return try {
            if (!destDir.exists() && !destDir.mkdirs()) {
                return AiboxResult.Err(AiboxErrorCode.EXTRACT_FAILED, "无法创建目标目录", destDir.absolutePath)
            }
            val root = destDir.canonicalFile
            val extracted = mutableMapOf<String, File>()

            ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val path = normalizePath(entry.name)
                        ?: return AiboxResult.Err(
                            AiboxErrorCode.UNSAFE_PATH,
                            "包内路径不安全",
                            entry.name
                        )
                    val target = File(root, path).canonicalFile
                    if (!target.path.startsWith(root.path + File.separator) && target != root) {
                        return AiboxResult.Err(
                            AiboxErrorCode.UNSAFE_PATH,
                            "解压路径越界",
                            target.absolutePath
                        )
                    }
                    if (entry.isDirectory) {
                        if (!target.exists() && !target.mkdirs()) {
                            return AiboxResult.Err(
                                AiboxErrorCode.EXTRACT_FAILED,
                                "无法创建目录",
                                target.absolutePath
                            )
                        }
                        continue
                    }
                    target.parentFile?.mkdirs()
                    val tmp = File(target.parentFile, "${target.name}.part")
                    val digest = MessageDigest.getInstance("SHA-256")
                    var written = 0L
                    zip.getInputStream(entry).use { input ->
                        tmp.outputStream().use { output ->
                            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                written += n
                                if (written > limits.maxEntryBytes) {
                                    tmp.delete()
                                    return AiboxResult.Err(
                                        AiboxErrorCode.TOO_LARGE,
                                        "单个条目解压超过限制",
                                        path
                                    )
                                }
                                digest.update(buf, 0, n)
                                output.write(buf, 0, n)
                            }
                        }
                    }
                    val expected = pkg.manifest.payload.firstOrNull { normalizePath(it.path) == path }?.sha256
                    if (!expected.isNullOrBlank() && !expected.equals(digest.digest().toHex(), ignoreCase = true)) {
                        tmp.delete()
                        return AiboxResult.Err(
                            AiboxErrorCode.CHECKSUM_MISMATCH,
                            "payload 校验失败",
                            path
                        )
                    }
                    if (!tmp.renameTo(target)) {
                        tmp.delete()
                        return AiboxResult.Err(
                            AiboxErrorCode.EXTRACT_FAILED,
                            "无法写入目标文件",
                            target.absolutePath
                        )
                    }
                    extracted[path] = target
                }
            }

            AiboxResult.Ok(ExtractedAiboxPackage(pkg.manifest, root, extracted))
        } catch (e: Throwable) {
            AiboxResult.Err(AiboxErrorCode.EXTRACT_FAILED, "解压失败", e.message)
        }
    }

    private fun validateManifest(m: AiboxManifest): AiboxResult.Err? {
        if (m.schemaVersion != 1) {
            return AiboxResult.Err(
                AiboxErrorCode.MANIFEST_INVALID,
                "schema_version 不支持",
                "${m.schemaVersion}"
            )
        }
        if (!idRegex.matches(m.id)) {
            return AiboxResult.Err(AiboxErrorCode.MANIFEST_INVALID, "id 格式不合法", m.id)
        }
        if (SemVer.parse(m.version) == null) {
            return AiboxResult.Err(AiboxErrorCode.MANIFEST_INVALID, "version 格式不合法", m.version)
        }
        if (m.type == AiboxPackageType.UNKNOWN) {
            return AiboxResult.Err(AiboxErrorCode.UNSUPPORTED_TYPE, "type 不合法")
        }
        val paths = mutableSetOf<String>()
        for (p in m.payload) {
            val normalized = normalizePath(p.path)
                ?: return AiboxResult.Err(AiboxErrorCode.UNSAFE_PATH, "payload 路径不安全", p.path)
            if (!paths.add(normalized)) {
                return AiboxResult.Err(AiboxErrorCode.DUPLICATE_ENTRY, "payload 路径重复", normalized)
            }
            if (p.sha256.isNotBlank() && !sha256Regex.matches(p.sha256.lowercase())) {
                return AiboxResult.Err(
                    AiboxErrorCode.MANIFEST_INVALID,
                    "sha256 格式不合法",
                    "${p.path}: ${p.sha256}"
                )
            }
        }
        return null
    }

    private fun verifyPayloads(
        zip: ZipFile,
        manifest: AiboxManifest,
        checksums: Map<String, String>
    ): AiboxResult.Err? {
        for (p in manifest.payload) {
            val path = normalizePath(p.path) ?: continue
            val entry = zip.getEntry(path)
                ?: return AiboxResult.Err(
                    AiboxErrorCode.PAYLOAD_MISSING,
                    "payload 文件缺失",
                    path
                )
            val expected = p.sha256.ifBlank { checksums[path].orEmpty() }
            if (expected.isBlank()) continue
            val actual = zip.getInputStream(entry).use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
                digest.digest().toHex()
            }
            if (!expected.equals(actual, ignoreCase = true)) {
                return AiboxResult.Err(
                    AiboxErrorCode.CHECKSUM_MISMATCH,
                    "payload 校验失败",
                    path
                )
            }
        }
        return null
    }

    private fun readChecksums(zip: ZipFile, warnings: MutableList<String>): Map<String, String> {
        val entry = zip.getEntry(CHECKSUMS_PATH) ?: return emptyMap()
        if (entry.size > limits.maxManifestBytes) {
            warnings += "checksums.json 过大，已忽略"
            return emptyMap()
        }
        return try {
            val text = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            val map = json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), text)
            map.mapKeys { normalizePath(it.key) ?: it.key }
        } catch (e: Throwable) {
            warnings += "checksums.json 解析失败：${e.message}"
            emptyMap()
        }
    }

    private fun normalizePath(raw: String): String? {
        if (raw.isBlank()) return null
        if (raw.startsWith("/") || raw.startsWith("\\")) return null
        if (raw.contains('\\')) return null
        val parts = raw.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        const val MANIFEST_PATH = "manifest.json"
        const val CHECKSUMS_PATH = "checksums.json"
        const val SIGNATURE_PATH = "signatures/manifest.sig"
    }
}
