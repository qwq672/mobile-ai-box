package com.aibox.core.plugin.host.registry

import com.aibox.core.logger.AILogger
import com.aibox.core.logger.LogModule
import com.aibox.core.logger.info
import com.aibox.core.plugin.host.connection.PluginConnectionManager
import com.aibox.core.plugin.host.discovery.PluginDiscovery
import com.aibox.core.plugin.host.discovery.PluginManifest
import com.aibox.core.plugin.host.signature.PluginSignatureVerifier
import com.aibox.core.plugin.host.signature.SignatureVerification
import com.aibox.plugin.aidl.IPluginInterface

/**
 * 插件注册表 — 宿主侧单例。
 *
 * 职责：
 * 1. 启动时扫描所有已安装插件
 * 2. 签名校验
 * 3. 按需绑定（懒加载，避免启动时占内存）
 * 4. 接口版本兼容性检查
 *
 * 线程模型：注册表本身线程安全，但 [IPluginInterface] 调用必须在调用方协程上下文中。
 */
public class PluginRegistry(
    private val discovery: PluginDiscovery,
    private val signatureVerifier: PluginSignatureVerifier,
    private val connectionManager: PluginConnectionManager,
    private val logger: AILogger,
) {

    private val manifests = mutableMapOf<String, PluginManifest>()      // packageName -> manifest
    private val verified = mutableSetOf<String>()                       // 签名校验通过的包名
    private val currentApiVersion = 1                                    // 宿主当前 AIDL API 版本

    /** 全量扫描 + 注册。在 App 启动时调用一次 */
    public suspend fun refresh(): RefreshResult {
        val found = discovery.discoverAll()

        manifests.clear()
        verified.clear()
        val rejectedSignatures = mutableListOf<String>()
        val rejectedApiVersions = mutableListOf<String>()

        for (manifest in found) {
            manifests[manifest.packageName] = manifest

            val verification = signatureVerifier.verify(manifest.packageName)
            if (!verification.isValid) {
                rejectedSignatures += manifest.packageName
                continue
            }

            // API 版本兼容性：插件 apiVersion > 宿主时拒绝（避免半截功能）
            if (manifest.apiVersion > currentApiVersion) {
                rejectedApiVersions += manifest.packageName
                continue
            }

            verified += manifest.packageName
        }

        logger.info(
            LogModule.PLUGIN_HOST, "registry",
            "refresh done. verified=${verified.size}, rejectedSig=${rejectedSignatures.size}, rejectedApi=${rejectedApiVersions.size}",
        )

        return RefreshResult(
            totalDiscovered = found.size,
            verified = verified.toList(),
            rejectedSignatures = rejectedSignatures,
            rejectedApiVersions = rejectedApiVersions,
        )
    }

    /** 获取已验证的插件接口（懒绑定） */
    public suspend fun getInterface(packageName: String): IPluginInterface? {
        if (packageName !in verified) return null
        val manifest = manifests[packageName] ?: return null
        return connectionManager.connect(packageName, manifest.className)
    }

    public fun listVerified(): List<PluginManifest> = verified.mapNotNull { manifests[it] }

    public fun isVerified(packageName: String): Boolean = packageName in verified
}

public data class RefreshResult(
    public val totalDiscovered: Int,
    public val verified: List<String>,
    public val rejectedSignatures: List<String>,
    public val rejectedApiVersions: List<String>,
)
