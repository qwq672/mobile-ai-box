package com.aibox.infra.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import com.aibox.domain.inference.BackendManagerV2
import com.aibox.util.log.LogSystem
import com.aibox.util.outcome.Outcome
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 插件生命周期管理器（核心）。
 *
 * 职责：
 * 1. **发现**：扫描已安装 APK 中带 `com.aibox.action.BACKEND_PLUGIN` intent-filter 的 Service。
 * 2. **启用**：校验签名 → 兼容性检查 → 绑定到 PluginService → 获取 IPluginDescriptor +
 *    IPluginInterface → 包装成 [PluginBackendAdapter] + [PluginDescriptorAdapter] → 注册到
 *    [BackendManagerV2] → 持久化"已启用"。
 * 3. **禁用**：从 BackendManager 注销 → 解绑 Service → 持久化"已禁用"。
 * 4. **卸载**：禁用 + 清理数据 + 触发系统卸载意图。
 * 5. **监听系统包变化**：注册 [PluginInstallReceiver]，插件 APK 安装 / 卸载 / 替换时自动同步。
 *
 * 设计原则：
 * - **零异常约定**：所有 public 方法返回 [Outcome] 或安全值（List / Boolean），不抛异常。
 * - **同步绑定**：[enablePlugin] 用 [CountDownLatch] 等待 onServiceConnected 回调，
 *   超时则失败返回；适合在 ViewModel 主线程调度后异步调用。
 * - **Service 单绑定双接口**：插件 PluginService 的 Binder 同时实现
 *   [com.aibox.plugin.IPluginInterface] 和 [com.aibox.plugin.IPluginDescriptor]；
 *   本类用各自的 `Stub.asInterface(binder)` 获取接口（要求插件 Binder 覆写
 *   `queryLocalInterface` 返回 this；插件侧负责实现细节）。
 * - **不持有 Context 强引用以外的对象**：避免内存泄漏。
 *
 * 线程安全：plugins / packageReceiver 操作应集中在主线程或单线程调度器；
 * 本类内部用 `synchronized(plugins)` 保护 plugins map，但 bindService 的 ServiceConnection
 * 回调可能在 binder 线程触发。
 *
 * @property context 宿主 Context。
 * @property backendManager V2 后端路由器；插件启用时把 [PluginBackendAdapter] 注册到此。
 * @property logCallback 注入到插件的日志回调（level, tag, msg）→ LogSystem。
 * @property currentAiboxVersion 宿主版本号，传给 [PluginCompatibilityChecker]。
 */
class PluginManager(
    private val context: Context,
    private val backendManager: BackendManagerV2,
    private val logCallback: (Int, String, String) -> Unit,
    private val currentAiboxVersion: String = ""
) {

    private val logTag = "aibox.plugin.manager"

    /** 已启用的插件句柄，按 packageName 索引。 */
    private val plugins = mutableMapOf<String, PluginHandle>()

    /** 状态持久化。 */
    private val pluginStore = PluginStore(context)

    /** 兼容性校验器。 */
    private val compatibilityChecker = PluginCompatibilityChecker(context, currentAiboxVersion)

    /** 包安装 / 卸载事件接收器（静态注册，单实例监听）。 */
    private val packageReceiver = PluginInstallReceiver()

    /** 是否已注册 packageReceiver。 */
    @Volatile private var receiverRegistered = false

    // =================================================
    // 1. 发现
    // =================================================

    /**
     * 扫描已安装的 AIBox 插件。
     *
     * 通过 PackageManager.queryIntentServices 查找带 `com.aibox.action.BACKEND_PLUGIN`
     * intent-filter 的 Service；每个 ServiceInfo 对应一个候选插件。
     *
     * @return 候选插件 [PluginInfo] 列表（包含已启用与未启用）；查询失败返回空列表。
     */
    fun discoverPlugins(): List<PluginInfo> {
        val intent = Intent(ACTION_BACKEND_PLUGIN)
        val resolved = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentServices(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentServices(intent, PackageManager.GET_META_DATA)
            }
        } catch (e: Throwable) {
            LogSystem.warn(logTag, "discoverPlugins queryIntentServices failed: ${e.message}", e)
            return emptyList()
        }
        return resolved.mapNotNull { resolve -> resolveInfoToPluginInfo(resolve) }
    }

    /**
     * 把 ResolveInfo 解析为 [PluginInfo]。
     *
     * 从 ServiceInfo + PackageInfo 提取：
     * - packageName: ServiceInfo.packageName
     * - descriptorClass: ServiceInfo.name（PluginService 类全限定名）
     * - version: PackageInfo.versionName
     * - displayName: ApplicationInfo.loadLabel（ PackageManager ）
     * - sizeBytes: ApplicationInfo.sourceDir 文件大小
     */
    private fun resolveInfoToPluginInfo(resolve: ResolveInfo): PluginInfo? {
        return try {
            val service: ServiceInfo = resolve.serviceInfo ?: return null
            val pkg = service.packageName
            if (pkg.isNullOrBlank()) return null
            val pm = context.packageManager
            val pkgInfo = pm.getPackageInfo(pkg, 0)
            val displayName = runCatching {
                pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
            }.getOrDefault(pkg)
            val sizeBytes = runCatching {
                java.io.File(pkgInfo.applicationInfo?.sourceDir ?: "").length()
            }.getOrDefault(0L)
            PluginInfo(
                packageName = pkg,
                descriptorClass = service.name ?: "",
                version = pkgInfo.versionName ?: "",
                displayName = displayName,
                sizeBytes = sizeBytes
            )
        } catch (e: Throwable) {
            LogSystem.warn(logTag, "resolveInfoToPluginInfo failed: ${e.message}", e)
            null
        }
    }

    // =================================================
    // 2. 启用
    // =================================================

    /**
     * 启用插件：绑定 service + 注册到 [BackendManagerV2]。
     *
     * 流程：
     * 1. 检查是否已启用；若是，返回现有 handle。
     * 2. 校验签名（防止恶意插件伪装）。
     * 3. 兼容性校验（minAiboxVersion / targetSdk / ABI）。
     * 4. 绑定到插件的 PluginService。
     * 5. 通过 AIDL 获取 IPluginDescriptor 与 IPluginInterface。
     * 6. 注入日志回调（让插件把日志发回宿主 LogSystem）。
     * 7. 包装成 [PluginBackendAdapter] + [PluginDescriptorAdapter]。
     * 8. 注册到 BackendManagerV2。
     * 9. 持久化"已启用"状态。
     */
    fun enablePlugin(packageName: String): Outcome<PluginHandle> {
        // 0. 已启用则幂等返回
        synchronized(plugins) {
            plugins[packageName]?.let { return Outcome.Success(it) }
        }

        // 1. 签名校验
        if (!verifySignature(packageName)) {
            val reason = "Signature verification failed for $packageName"
            LogSystem.warn(logTag, reason)
            return Outcome.Failure(reason)
        }

        // 2. 兼容性校验
        val compat = compatibilityChecker.check(packageName)
        if (!compat.success) {
            val reason = "Incompatible plugin: ${compat.reason}"
            LogSystem.warn(logTag, reason)
            return Outcome.Failure(reason)
        }

        // 3. 绑定
        val bindResult = bindPluginService(packageName)
        val bind = bindResult.getOrNull()
            ?: return Outcome.Failure(bindResult.reasonOrNull() ?: "Bind service failed")

        // 4. 取 descriptor + interface
        val descriptor = com.aibox.plugin.IPluginDescriptor.Stub.asInterface(bind.binder)
        val iface = com.aibox.plugin.IPluginInterface.Stub.asInterface(bind.binder)
        if (descriptor == null || iface == null) {
            try { context.unbindService(bind.connection) } catch (_: Throwable) {}
            val reason = "Plugin binder does not implement both IPluginDescriptor and IPluginInterface"
            LogSystem.warn(logTag, "$reason (descriptor=${descriptor != null}, iface=${iface != null})")
            return Outcome.Failure(reason)
        }

        // 5. 取 PluginInfo（优先用 AIDL getPluginInfo，兜底用 discoverPlugins 解析）
        val parcelInfo = try {
            descriptor.pluginInfo
        } catch (e: RemoteException) {
            LogSystem.warn(logTag, "Failed to get pluginInfo from descriptor: ${e.message}", e)
            null
        }
        val pluginInfo = if (parcelInfo != null) {
            ParcelableConverters.run { parcelInfo.toDomain() }
        } else {
            discoverPlugins().firstOrNull { it.packageName == packageName }
        } ?: PluginInfo(
            packageName = packageName,
            descriptorClass = "",
            version = "",
            displayName = packageName,
            sizeBytes = 0L
        )

        // 6. 注入日志回调（让插件把日志发回宿主 LogSystem）
        val logCallbackStub = object : com.aibox.plugin.IPluginLogCallback.Stub() {
            override fun onLog(level: Int, tag: String?, message: String?) {
                try {
                    logCallback(level, tag ?: "", message ?: "")
                } catch (_: Throwable) {
                    // 日志回调本身失败不能影响主流程
                }
            }
        }
        try {
            iface.setLogCallback(logCallbackStub)
        } catch (e: RemoteException) {
            LogSystem.warn(logTag, "Failed to setLogCallback: ${e.message}", e)
        }

        // 7. 构造 adapters
        val backendAdapter = PluginBackendAdapter(iface, descriptor, pluginInfo)
        val descriptorAdapter = PluginDescriptorAdapter(descriptor, pluginInfo)

        // 8. 注册到 BackendManagerV2
        try {
            backendManager.registerBackend(backendAdapter, descriptorAdapter)
        } catch (e: IllegalArgumentException) {
            // registerBackend 可能因 name 不一致抛 IllegalArgumentException
            try { context.unbindService(bind.connection) } catch (_: Throwable) {}
            val reason = "BackendManagerV2.registerBackend rejected: ${e.message}"
            LogSystem.warn(logTag, reason, e)
            return Outcome.Failure(reason, e)
        }

        // 9. 持久化"已启用"
        pluginStore.setEnabled(packageName, true)

        // 10. 缓存 handle
        val handle = PluginHandle(
            info = pluginInfo,
            connection = bind.connection,
            pluginInterface = iface,
            descriptor = descriptor
        )
        synchronized(plugins) {
            plugins[packageName] = handle
        }
        LogSystem.info(logTag, "Plugin enabled: $packageName v${pluginInfo.version}")
        return Outcome.Success(handle)
    }

    // =================================================
    // 3. 禁用
    // =================================================

    /**
     * 禁用插件。
     *
     * 流程：
     * 1. 从 BackendManager 注销。
     * 2. 解绑 Service。
     * 3. 从 plugins map 移除。
     * 4. 持久化"已禁用"。
     *
     * 注意：本方法**不主动取消**正在运行的推理；推理中的请求由 BackendManagerV2 的调用方
     * （如 ChatViewModel）保证完成或取消后再禁用；本类只清理服务绑定。
     */
    fun disablePlugin(packageName: String): Outcome<Unit> {
        val handle: PluginHandle?
        synchronized(plugins) {
            handle = plugins.remove(packageName)
        }
        if (handle == null) {
            // 不在已启用列表中，幂等返回 Success
            pluginStore.setEnabled(packageName, false)
            return Outcome.Success(Unit)
        }
        // 1. 从 BackendManager 注销
        try {
            backendManager.unregisterBackend(packageName)
        } catch (e: Throwable) {
            LogSystem.warn(logTag, "BackendManagerV2.unregisterBackend failed: ${e.message}", e)
        }
        // 2. 解绑 Service
        try {
            context.unbindService(handle.connection)
        } catch (e: Throwable) {
            LogSystem.warn(logTag, "unbindService failed for $packageName: ${e.message}", e)
        }
        // 3. 持久化"已禁用"
        pluginStore.setEnabled(packageName, false)
        LogSystem.info(logTag, "Plugin disabled: $packageName")
        return Outcome.Success(Unit)
    }

    // =================================================
    // 4. 卸载
    // =================================================

    /**
     * 卸载插件（系统级卸载）。
     *
     * 流程：
     * 1. 调 [disablePlugin]（注销 + 解绑）。
     * 2. 调 [PluginStore.clearAll] 清理插件数据。
     * 3. 触发系统卸载意图（ACTION_DELETE），用户在系统对话框确认后 APK 被移除。
     *
     * 注：Android 没有应用主动卸载其它应用的 API；只能弹出系统卸载对话框让用户确认。
     */
    fun uninstallPlugin(packageName: String) {
        disablePlugin(packageName)
        pluginStore.clearAll(packageName)
        try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            LogSystem.warn(logTag, "Failed to start uninstall intent: ${e.message}", e)
        }
    }

    // =================================================
    // 5. 包变化监听
    // =================================================

    /** 注册系统包安装 / 卸载监听器（Application 启动时调用一次）。 */
    fun registerPackageReceiver() {
        if (receiverRegistered) return
        PluginInstallReceiver.setListener { event -> handlePackageEvent(event) }
        receiverRegistered = true
        // 注：静态注册（在 AndroidManifest 中）更可靠；动态注册时使用 IntentFilter。
        // 本类只设置 Listener 回调；静态 receiver 已在 manifest 中声明。
        LogSystem.info(logTag, "PluginInstallReceiver listener registered")
    }

    /** 注销监听器（Application onTerminate 时调用）。 */
    fun unregisterPackageReceiver() {
        PluginInstallReceiver.setListener(null)
        receiverRegistered = false
    }

    /** 处理包事件：插件被安装 → 重新扫描 + 自动启用；被卸载 → 禁用。 */
    private fun handlePackageEvent(event: PluginPackageEvent) {
        when (event) {
            is PluginPackageEvent.Added -> {
                LogSystem.info(logTag, "Plugin package added: ${event.packageName}")
                // 不自动启用；只刷新发现列表，等用户在 UI 上手动启用。
            }
            is PluginPackageEvent.Replaced -> {
                LogSystem.info(logTag, "Plugin package replaced: ${event.packageName}")
                // 如已启用，禁用 → 重新启用，让新版本生效
                synchronized(plugins) {
                    if (plugins.containsKey(event.packageName)) {
                        disablePlugin(event.packageName)
                        enablePlugin(event.packageName)
                    }
                }
            }
            is PluginPackageEvent.Removed -> {
                if (event.replacing) {
                    // 替换流程的第一步；Replaced 事件会随后到达，这里不做清理。
                    LogSystem.info(logTag, "Plugin package being replaced: ${event.packageName}")
                } else {
                    LogSystem.info(logTag, "Plugin package removed: ${event.packageName}")
                    disablePlugin(event.packageName)
                    pluginStore.clearAll(event.packageName)
                }
            }
            is PluginPackageEvent.FullyRemoved -> {
                LogSystem.info(logTag, "Plugin package fully removed: ${event.packageName}")
                disablePlugin(event.packageName)
                pluginStore.clearAll(event.packageName)
            }
        }
    }

    // =================================================
    // 6. 状态查询
    // =================================================

    /** 列出当前已启用（已绑定）的插件包名。 */
    fun listActive(): List<String> = synchronized(plugins) { plugins.keys.toList() }

    /** 查询某插件是否已启用（在 plugins map 中）。 */
    fun isActive(packageName: String): Boolean = synchronized(plugins) { plugins.containsKey(packageName) }

    /** 获取某插件的 handle；未启用返回 null。 */
    fun getHandle(packageName: String): PluginHandle? = synchronized(plugins) { plugins[packageName] }

    // =================================================
    // 内部辅助
    // =================================================

    /**
     * 同步绑定到插件 Service。
     *
     * 用 [CountDownLatch] 阻塞等待 onServiceConnected；超时 [BIND_TIMEOUT_MS] 则失败返回。
     * 调用方应在非主线程调用（避免阻塞 UI）。
     */
    private fun bindPluginService(packageName: String): Outcome<BindResult> {
        val latch = CountDownLatch(1)
        var binder: IBinder? = null
        var error: String? = null

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, b: IBinder) {
                binder = b
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                // 服务进程崩溃；后续 onBindingDied 会触发，这里仅记日志
                LogSystem.warn(logTag, "Service disconnected: $name")
            }

            override fun onBindingDied(name: ComponentName) {
                error = "Plugin service binding died: $name"
                latch.countDown()
            }

            override fun onNullBinding(name: ComponentName) {
                error = "Plugin service returned null binding: $name"
                latch.countDown()
            }
        }

        val intent = Intent(ACTION_BACKEND_PLUGIN).setPackage(packageName)
        val bound = try {
            context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        } catch (e: Throwable) {
            return Outcome.Failure("bindService threw: ${e.message}", e)
        }
        if (!bound) {
            return Outcome.Failure("bindService returned false for $packageName (no service with action $ACTION_BACKEND_PLUGIN)")
        }

        val ok = try {
            latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            try { context.unbindService(conn) } catch (_: Throwable) {}
            return Outcome.Failure("Interrupted while waiting for bind to $packageName")
        }

        if (!ok) {
            try { context.unbindService(conn) } catch (_: Throwable) {}
            return Outcome.Failure("Bind timeout (${BIND_TIMEOUT_MS}ms) for $packageName")
        }

        error?.let {
            try { context.unbindService(conn) } catch (_: Throwable) {}
            return Outcome.Failure(it)
        }
        val b = binder
            ?: return Outcome.Failure("Binder is null after onServiceConnected for $packageName")
        return Outcome.Success(BindResult(b, conn))
    }

    /**
     * 签名校验（防止恶意插件伪装）。
     *
     * 当前实现：检查插件包是否被签名（任意证书均可）；后续可扩展为"白名单证书"或"与宿主签名一致"。
     *
     * @return true 表示签名校验通过；false 表示无签名或被显式标记为不可信。
     */
    private fun verifySignature(packageName: String): Boolean {
        return try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val pkgInfo = pm.getPackageInfo(
                    packageName,
                    PackageManager.GET_SERVICES or PackageManager.GET_SIGNING_CERTIFICATES
                )
                val signingInfo = pkgInfo.signingInfo
                signingInfo != null
            } else {
                @Suppress("DEPRECATION", "PackageManagerGetSignatures")
                val pkgInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                !pkgInfo.signatures.isNullOrEmpty()
            }
        } catch (e: Throwable) {
            LogSystem.warn(logTag, "verifySignature failed for $packageName: ${e.message}", e)
            false
        }
    }

    /** 服务绑定结果。 */
    private data class BindResult(
        val binder: IBinder,
        val connection: ServiceConnection
    )

    companion object {
        /** 插件 Service 的 intent action（插件 manifest 中声明此 action）。 */
        const val ACTION_BACKEND_PLUGIN = "com.aibox.action.BACKEND_PLUGIN"

        /** 绑定超时时间（毫秒）；插件 Service 启动慢或 ANR 时避免无限等待。 */
        const val BIND_TIMEOUT_MS = 10_000L
    }
}

/**
 * 已启用插件的运行期句柄。
 *
 * @property info 插件基本信息（包名 / 版本 / 显示名 / 大小）。
 * @property connection ServiceConnection 句柄（用于 unbindService）。
 * @property pluginInterface 插件运行时 AIDL 接口（加载 / 推理 / 卸载）。
 * @property descriptor 插件元信息 AIDL 接口（能力 / 设置 schema）。
 */
data class PluginHandle(
    val info: PluginInfo,
    val connection: ServiceConnection,
    val pluginInterface: com.aibox.plugin.IPluginInterface,
    val descriptor: com.aibox.plugin.IPluginDescriptor
)
