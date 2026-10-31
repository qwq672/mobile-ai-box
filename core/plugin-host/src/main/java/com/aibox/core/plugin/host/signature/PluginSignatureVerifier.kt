package com.aibox.core.plugin.host.signature

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import com.aibox.core.logger.AILogger
import com.aibox.core.logger.LogModule
import com.aibox.core.logger.warn
import java.security.MessageDigest

/**
 * 插件签名验证器。
 *
 * 反屎山原则：所有跨进程信任必须基于签名校验，禁止任何"宽松模式"。
 *
 * 校验规则：
 * - 插件签名 SHA-256 必须在宿主白名单内（CI 发布时注入）
 * - 测试模式（DEBUG 构建）允许跳过白名单，但仍记录警告
 * - 不通过校验的插件：**禁止加载**，只记录日志
 *
 * 白名单维护：
 * - 白名单 JSON 文件放在 assets/plugin_whitelist.json
 * - 包含字段：[ { "pluginId": "xxx", "signatureSha256": "abc..." } ]
 * - CI 通过 GH_SECRET 注入私钥签名插件 + 公钥指纹到白名单
 */
public class PluginSignatureVerifier(
    private val appContext: Context,
    private val logger: AILogger,
    private val whitelist: SignatureWhitelist,
) {

    public fun verify(packageName: String): SignatureVerification {
        return try {
            val signatures = getSignatures(packageName)
            if (signatures.isEmpty()) {
                return SignatureVerification.Invalid(packageName, "no signatures found")
            }
            val sigSha256 = sha256(signatures.first().toByteArray())
            val pluginId = packageName.substringAfterLast('.')

            if (whitelist.isTrusted(pluginId, sigSha256)) {
                SignatureVerification.Valid(packageName, sigSha256)
            } else {
                logger.warn(
                    LogModule.PLUGIN_HOST, "signature",
                    "plugin signature not in whitelist: $packageName, sig=$sigSha256",
                )
                SignatureVerification.Invalid(packageName, "signature not in whitelist")
            }
        } catch (t: Throwable) {
            SignatureVerification.Invalid(packageName, "verify error: ${t.message}")
        }
    }

    private fun getSignatures(packageName: String): Array<Signature> {
        val pm = appContext.packageManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            @Suppress("DEPRECATION", "PackageManagerGetSignatures")
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            info.signatures ?: emptyArray()
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}

public sealed class SignatureVerification {
    public data class Valid(val packageName: String, val signatureSha256: String) : SignatureVerification()
    public data class Invalid(val packageName: String, val reason: String) : SignatureVerification()

    public val isValid: Boolean get() = this is Valid
}

/** 白名单存储接口 */
public interface SignatureWhitelist {
    public fun isTrusted(pluginId: String, signatureSha256: String): Boolean
}

/** 测试用：始终允许（DEBUG 构建可用） */
public class PermissiveWhitelist : SignatureWhitelist {
    override fun isTrusted(pluginId: String, signatureSha256: String): Boolean = true
}

/** 内存白名单 */
public class InMemoryWhitelist(private val trusted: Map<String, String>) : SignatureWhitelist {
    override fun isTrusted(pluginId: String, signatureSha256: String): Boolean {
        return trusted[pluginId] == signatureSha256
    }
}
