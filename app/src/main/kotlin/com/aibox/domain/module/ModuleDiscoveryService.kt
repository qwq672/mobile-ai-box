package com.aibox.domain.module

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.aibox.util.log.LogSystem

/**
 * 模块发现服务：扫描设备上所有 AIBox 模块 APK。
 *
 * 扫描的 Intent Action（4 种模块类型）：
 * - com.aibox.action.BACKEND_PLUGIN  → 推理框架插件（如 LiteRT-LM）
 * - com.aibox.action.CHAT_MODULE     → 聊天模块（如 Easy Chat）
 * - com.aibox.action.TRANSLATE_MODULE → 翻译模块
 * - com.aibox.action.IMAGE_GEN_MODULE → 图像生成模块（未来）
 *
 * 每个发现到的 Service 会读取其 metaData 拿到 name / displayName / version 等字段。
 *
 * 注意：Android 11+ (API 30+) 包可见性要求宿主 AndroidManifest 已声明 <queries>
 * 否则 queryIntentServices 返回空。本类假设 queries 已配置（见 AndroidManifest.xml）。
 */
class ModuleDiscoveryService(private val context: Context) {

    /** 扫描所有 AIBox 模块，返回去重后的列表（宿主在第一位）。 */
    fun discoverAll(): List<DiscoveredModule> {
        val results = mutableListOf<DiscoveredModule>()

        // 1. 扫描 4 种 action
        for (action in MODULE_ACTIONS) {
            try {
                val intent = Intent(action)
                val resolved = context.packageManager.queryIntentServices(
                    intent,
                    PackageManager.GET_META_DATA
                )
                LogSystem.info(TAG, "Action $action: found ${resolved.size} service(s)")
                resolved.forEach { ri ->
                    val module = parseResolveInfo(ri, action)
                    if (module != null && results.none { it.packageName == module.packageName }) {
                        results.add(module)
                    }
                }
            } catch (e: Throwable) {
                LogSystem.warn(TAG, "discover action=$action failed: ${e.message}", e)
            }
        }

        // 2. 把宿主自己也作为 1 个"系统模块"加入列表（第一位）
        results.add(0, hostModule())

        LogSystem.info(TAG, "Total discovered: ${results.size} (including host)")
        return results
    }

    private fun parseResolveInfo(ri: ResolveInfo, action: String): DiscoveredModule? {
        return try {
            val service = ri.serviceInfo
            val pkg = service.packageName
            val meta = service.metaData

            // 兼容两套 meta-data 命名（EasyChat/Translate 用 aibox.module.*，LiteRT 用 aibox.version）
            val displayName = meta?.getString(META_DISPLAY_NAME)
                ?: meta?.getString(META_ALT_VERSION)
                ?: pkg.substringAfterLast('.')
            val version = meta?.getString(META_VERSION)
                ?: meta?.getString(META_ALT_VERSION)
                ?: "unknown"
            val name = meta?.getString(META_NAME) ?: displayName.lowercase().replace(' ', '-')

            DiscoveredModule(
                packageName = pkg,
                name = name,
                displayName = displayName,
                version = version,
                type = actionToType(action),
                isHost = false,
                isSystem = false
            )
        } catch (e: Throwable) {
            LogSystem.warn(TAG, "parseResolveInfo failed: ${e.message}", e)
            null
        }
    }

    private fun hostModule(): DiscoveredModule {
        return DiscoveredModule(
            packageName = context.packageName,
            name = "aibox-host",
            displayName = "AIBox 宿主",
            version = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            }.getOrDefault("unknown"),
            type = ModuleType.HOST,
            isHost = true,
            isSystem = true
        )
    }

    private fun actionToType(action: String): ModuleType = when (action) {
        ACTION_BACKEND_PLUGIN -> ModuleType.BACKEND_PLUGIN
        ACTION_CHAT_MODULE -> ModuleType.CHAT_MODULE
        ACTION_TRANSLATE_MODULE -> ModuleType.TRANSLATE_MODULE
        ACTION_IMAGE_GEN_MODULE -> ModuleType.IMAGE_GEN_MODULE
        else -> ModuleType.UNKNOWN
    }

    companion object {
        private const val TAG = "aibox.module.discovery"

        const val ACTION_BACKEND_PLUGIN = "com.aibox.action.BACKEND_PLUGIN"
        const val ACTION_CHAT_MODULE = "com.aibox.action.CHAT_MODULE"
        const val ACTION_TRANSLATE_MODULE = "com.aibox.action.TRANSLATE_MODULE"
        const val ACTION_IMAGE_GEN_MODULE = "com.aibox.action.IMAGE_GEN_MODULE"

        private val MODULE_ACTIONS = listOf(
            ACTION_BACKEND_PLUGIN,
            ACTION_CHAT_MODULE,
            ACTION_TRANSLATE_MODULE,
            ACTION_IMAGE_GEN_MODULE
        )

        const val META_NAME = "aibox.module.name"
        const val META_DISPLAY_NAME = "aibox.module.displayName"
        const val META_VERSION = "aibox.module.version"
        const val META_ALT_VERSION = "aibox.version"
    }
}

/** 已发现的模块。 */
data class DiscoveredModule(
    val packageName: String,
    val name: String,
    val displayName: String,
    val version: String,
    val type: ModuleType,
    val isHost: Boolean,
    val isSystem: Boolean
)

/** 模块类型。 */
enum class ModuleType(val label: String, val icon: String) {
    HOST("宿主", "🏠"),
    BACKEND_PLUGIN("推理框架", "⚙️"),
    CHAT_MODULE("聊天模块", "💬"),
    TRANSLATE_MODULE("翻译模块", "🌐"),
    IMAGE_GEN_MODULE("图像生成", "🎨"),
    UNKNOWN("未知", "❓")
}
