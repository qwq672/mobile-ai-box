package com.aibox.infra.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 监听系统级安装 / 卸载 / 替换事件，通知 [PluginManager] 同步插件状态。
 *
 * 设计原则：
 * 1. **被动接收**：本类只接收系统广播并转发给 [PluginManager]；不做业务逻辑。
 * 2. **过滤逻辑放在 PluginManager**：本类只把"包名 + 事件类型"传给 PluginManager，
 *    由后者判断是否是 AIBox 插件、是否需要更新启用状态。
 * 3. **零异常约定**：onReceive 内 try-catch 所有异常，避免广播队列阻塞。
 *
 * AndroidManifest 注册示例（动态注册也可，本类支持两种）：
 * ```xml
 * <receiver android:name="com.aibox.infra.plugin.PluginInstallReceiver"
 *           android:exported="true">
 *     <intent-filter>
 *         <action android:name="android.intent.action.PACKAGE_ADDED" />
 *         <action android:name="android.intent.action.PACKAGE_REMOVED" />
 *         <action android:name="android.intent.action.PACKAGE_REPLACED" />
 *         <data android:scheme="package" />
 *     </intent-filter>
 * </receiver>
 * ```
 *
 * 注：Android 8+ 对隐式广播有限制，但 PACKAGE_ADDED / REMOVED / REPLACED 属于
 * "面向所有应用的广播"白名单，静态注册仍然有效。
 */
class PluginInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            val action = intent.action ?: return
            val data = intent.data ?: return
            val packageName = data.schemeSpecificPart?.takeIf { it.isNotBlank() } ?: return

            val event: PluginPackageEvent = when (action) {
                Intent.ACTION_PACKAGE_ADDED -> PluginPackageEvent.Added(packageName)
                Intent.ACTION_PACKAGE_REMOVED -> PluginPackageEvent.Removed(
                    packageName,
                    replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                )
                Intent.ACTION_PACKAGE_REPLACED -> PluginPackageEvent.Replaced(packageName)
                Intent.ACTION_PACKAGE_FULLY_REMOVED -> PluginPackageEvent.FullyRemoved(packageName)
                else -> return
            }

            // 转发到 PluginManager 单例回调（避免 DI 解耦 BroadcastReceiver）。
            listener?.onPluginPackageEvent(event)
        } catch (_: Throwable) {
            // 静默吞掉，避免广播队列阻塞
        }
    }

    /**
     * 插件包事件回调接口；[PluginManager] 实现此接口，通过 [setListener] 注册到本 Receiver。
     */
    fun interface Listener {
        fun onPluginPackageEvent(event: PluginPackageEvent)
    }

    companion object {
        @Volatile
        private var listener: Listener? = null

        /** 注册监听器（PluginManager.registerPackageReceiver 时调用）。 */
        fun setListener(l: Listener?) {
            listener = l
        }
    }
}

/**
 * 插件包事件类型。
 *
 * - [Added]: 新安装（非替换）。
 * - [Removed]: 卸载中（可能是替换前的旧版本卸载，replacing=true 表示新版本会立即安装）。
 * - [FullyRemoved]: 数据全清（用户从设置选"卸载并清除数据"）。
 * - [Replaced]: 替换完成（旧版本卸载 + 新版本安装在同一原子操作）。
 */
sealed class PluginPackageEvent {
    abstract val packageName: String

    data class Added(override val packageName: String) : PluginPackageEvent()
    data class Removed(override val packageName: String, val replacing: Boolean) : PluginPackageEvent()
    data class FullyRemoved(override val packageName: String) : PluginPackageEvent()
    data class Replaced(override val packageName: String) : PluginPackageEvent()
}
