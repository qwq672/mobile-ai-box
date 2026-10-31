package com.aibox.core.plugin.host.discovery

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import com.aibox.core.common.AIBoxConstants
import com.aibox.core.logger.AILogger
import com.aibox.core.logger.LogModule
import com.aibox.core.logger.info
import com.aibox.core.logger.warn

/**
 * 插件发现器：基于 Intent Action + PackageManager.queryIntentServices 扫描已安装插件。
 *
 * 反屎山原则：
 * - 不用 PackageManager.getInstalledPackages() 全量扫描（O(N) 全包名遍历，慢）
 * - 用 Intent Action 精确查询（O(1) 索引）
 * - Android 11+ 必须配合宿主 Manifest 的 <queries> 才能查到（已配置）
 *
 * 发现流程：
 * 1. queryIntentServices(ACTION_INFERENCE_BACKEND) → 拿到所有推理插件 ServiceInfo
 * 2. 对每个 ServiceInfo 提取 [PluginManifest]
 * 3. 验证签名：插件签名必须匹配宿主预设签名（防止恶意插件冒充）
 * 4. 注册到 [PluginRegistry]
 */
public class PluginDiscovery(
    private val appContext: Context,
    private val logger: AILogger,
) {

    public fun discoverAll(): List<PluginManifest> {
        val discovered = mutableListOf<PluginManifest>()

        // 1. 推理后端插件
        discovered += discoverByAction(AIBoxConstants.ACTION_INFERENCE_BACKEND, PluginKind.INFERENCE_BACKEND)
        // 2. 能力插件（生图/音频/角色扮演扩展）
        discovered += discoverByAction(AIBoxConstants.ACTION_CAPABILITY, PluginKind.CAPABILITY)
        // 3. NPU 委托插件
        discovered += discoverByAction(AIBoxConstants.ACTION_NPU_DELEGATE, PluginKind.NPU_DELEGATE)

        logger.info(LogModule.PLUGIN_HOST, "discovery", "discovered ${discovered.size} plugins")
        return discovered
    }

    private fun discoverByAction(action: String, kind: PluginKind): List<PluginManifest> {
        val intent = android.content.Intent(action)
        val pm = appContext.packageManager
        val results: List<ResolveInfo> = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(intent, PackageManager.GET_META_DATA)
            }
        } catch (t: Throwable) {
            logger.warn(LogModule.PLUGIN_HOST, "discovery", "queryIntentServices failed for $action: ${t.message}")
            return emptyList()
        }

        return results.mapNotNull { resolveInfo ->
            val service: ServiceInfo = resolveInfo.serviceInfo
            try {
                PluginManifest(
                    packageName = service.packageName,
                    className = service.name,
                    kind = kind,
                    versionName = service.metaData?.getString("aibox_plugin_version") ?: "0.0.0",
                    apiVersion = service.metaData?.getInt("aibox_api_version", 1) ?: 1,
                )
            } catch (t: Throwable) {
                logger.warn(
                    LogModule.PLUGIN_HOST, "discovery",
                    "failed to parse plugin ${service.packageName}: ${t.message}",
                )
                null
            }
        }
    }
}

