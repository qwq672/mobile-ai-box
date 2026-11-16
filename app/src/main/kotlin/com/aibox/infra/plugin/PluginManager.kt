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
        val merged = resolved.mapNotNull { resolve -> resolveInfoToPluginInfo(resolve) }.toMutableList()
        // 兜底：部分 ROM（荣耀/部分定制系统）会把 queryIntentServices 的结果过滤掉，
        // 即使宿主已在 <queries> 中声明了包名。这里对已知后端包直接做 getPackageInfo 探测，
        // 避免「插件明明装了却始终提示未安装」。
        for (pkg in KNOWN_BACKEND_PACKAGES) {
            if (merged.any { it.packageName == pkg }) continue
            probeKnownBackendPackage(pkg)?.let {
                merged.add(it)
                LogSystem.info(logTag, "discoverPlugins: fallback found installed plugin $pkg")
            }
        }
        return merged
    }

    /**
     * 按包名直接确认后端插件是否安装（不依赖 Intent 查询结果）。
     * 仅接受确实声明了 BACKEND_PLUGIN 服务的包。
     */
    private fun probeKnownBackendPackage(packageName: String): PluginInfo? {
        return try {
            val pm = context.packageManager
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(
                        (PackageManager.GET_SERVICES or PackageManager.GET_META_DATA).toLong()
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(
                    packageName,
                    PackageManager.GET_SERVICES or PackageManager.GET_META_DATA
                )
            }
            // 不再要求 service 名字里必须有 PluginService / descriptor meta-data：
            // 各模块的 Service 命名并不统一，过严的过滤会把“已安装”误判成“未安装”。
            val services = pkgInfo.services.orEmpty().filter { it.exported }
            val service = services.firstOrNull { s ->
                s.metaData?.containsKey(META_DESCRIPTOR_CLASS) == true
            } ?: services.firstOrNull { s ->
                val n = s.name.orEmpty()
                n.contains("PluginService", ignoreCase = true) ||
                    n.contains("Backend", ignoreCase = true) ||
                    n.contains("Plugin", ignoreCase = true)
            } ?: services.firstOrNull()
            val app = pkgInfo.applicationInfo
            PluginInfo(
                packageName = packageName,
                descriptorClass = service?.name ?: "",
                version = pkgInfo.versionName ?: "",
                displayName = runCatching { app?.loadLabel(pm)?.toString() }
                    .getOrNull() ?: packageName,
                sizeBytes = runCatching { java.io.File(app?.sourceDir ?: "").length() }
                    .getOrDefault(0L)
            )
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (e: Throwable) {
            LogSystem.warn(logTag, "probeKnownBackendPackage($packageName) failed: ${e.message}", e)
            null
        }
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
        val candidate = discoverPlugins().firstOrNull { it.packageName == packageName }
        val bindResult = bindPluginService(packageName, candidate?.descriptorClass)
        val bind = bindResult.getOrNull()
            ?: return Outcome.Failure(bindResult.reasonOrNull() ?: "Bind service failed")

        // 4. 取 descriptor + interface
        // 注意：部分插件（如当前 LiteRT 插件）的 Binder 只实现 IPluginInterface，
        // 描述符由独立类提供。两个 AIDL 的方法序号会冲突，把运行时 Binder 硬转成
        // IPluginDescriptor 会调到 loadModel / streamInfer 等错误方法，所以先用
        // interfaceDescriptor 确认它到底实现了哪个接口。
        val binderInterface = runCatching { bind.binder.interfaceDescriptor }.getOrNull()
        val descriptor = if (binderInterface == null ||
            binderInterface.contains("IPluginDescriptor", ignoreCase = true)
        ) {
            com.aibox.plugin.IPluginDescriptor.Stub.asInterface(bind.binder)
        } else {
            LogSystem.info(
                logTag,
                "Plugin $packageName binder implements only '$binderInterface'; descriptor unavailable"
            )
            null
        }
        val iface = com.aibox.plugin.IPluginInterface.Stub.asInterface(bind.binder)
        if (iface == null) {
            try { context.unbindService(bind.connection) } catch (_: Throwable) {}
            val reason = "Plugin binder does not implement IPluginInterface"
            LogSystem.warn(logTag, reason)
            return Outcome.Failure(reason)
        }

        // 5. 取 PluginInfo。IPluginInterface 自带 getPluginInfo，优先用它；
        //    descriptor 若存在再兜底，最后才退回包解析结果。
        val parcelInfo = try {
            iface.pluginInfo ?: descriptor?.pluginInfo
        } catch (e: Throwable) {
            LogSystem.warn(logTag, "Failed to get pluginInfo: ${e.message}", e)
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
                // 安装后立即在后台尝试绑定。绑定是阻塞的（等 onServiceConnected），
                // 不能占着广播主线程，否则会 ANR。
                Thread {
                    runCatching {
                        val r = enablePlugin(event.packageName)
                        LogSystem.info(
                            logTag,
                            "auto-enable after install ${event.packageName}: " +
                                (if (r is Outcome.Success) "ok" else "failed: ${r.reasonOrNull()}")
                        )
                    }
                }.start()
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

    /**
     * 发现并自动绑定所有已安装的后端插件（应用启动 / 加载模型前调用）。
     *
     * 模块页目前只做「发现」，没有单独的启用入口；如果这里不自动绑定，
     * 用户装了 LiteRT 插件，加载 .litertlm 模型仍会提示「未安装」。
     * 失败的包会打日志，便于区分「真没装」和「装了但绑定失败」。
     */
    fun syncInstalledBackendPlugins(): List<String> {
        val discovered = discoverPlugins()
        LogSystem.info(
            logTag,
            "syncInstalledBackendPlugins: discovered=${discovered.map { it.packageName }} active=${listActive()}"
        )
        for (info in discovered) {
            if (isActive(info.packageName)) continue
            when (val r = enablePlugin(info.packageName)) {
                is Outcome.Success -> LogSystem.info(logTag, "auto-enabled plugin: ${info.packageName}")
                is Outcome.Failure -> LogSystem.warn(
                    logTag,
                    "auto-enable plugin failed: ${info.packageName}: ${r.reason}"
                )
            }
        }
        return listActive()
    }

    /** 查询某插件是否已启用（在 plugins map 中）。 */
    fun isActive(packageName: String): Boolean = synchronized(plugins) { plugins.containsKey(packageName) }

    /**
     * 诊断一个后端插件包，回答「到底卡在哪一步」。
     *
     * 依次检查：包是否可见 → 是否被发现 → 兼容性 → 是否已绑定。
     * 不主动 bind（避免副作用），但会列出可尝试的 Service。
     */
    fun diagnose(packageName: String): PluginDiagnostic {
        val pm = context.packageManager
        val pkgInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return PluginDiagnostic(
                packageName = packageName,
                installed = false,
                reason = "未安装，或 Android 11+ 包可见性未放行（需要 AndroidManifest <queries> 声明该包名）"
            )
        } catch (e: Throwable) {
            return PluginDiagnostic(
                packageName = packageName,
                installed = false,
                reason = "查询失败：${e.message}"
            )
        }

        val services = exportedServiceNames(packageName)
        val compat = compatibilityChecker.check(packageName)
        val discovered = discoverPlugins().any { it.packageName == packageName }
        val active = isActive(packageName)
        val reason = when {
            !discovered -> "包已安装，但没有发现 BACKEND_PLUGIN Service（可能是 action 不一致或 Service 未 exported）"
            !compat.success -> "发现插件但兼容性检查未通过：${compat.reason}"
            !active -> "发现且兼容，但绑定/注册失败（查看日志 aibox.plugin.manager 的 auto-enable 记录）"
            else -> "已启用"
        }
        return PluginDiagnostic(
            packageName = packageName,
            installed = true,
            version = pkgInfo.versionName ?: "",
            services = services,
            discovered = discovered,
            compatible = compat.success,
            active = active,
            reason = reason
        )
    }

    /** 诊断所有已知后端包（包括未安装的），用于模块页展示。 */
    fun diagnoseKnown(): List<PluginDiagnostic> = KNOWN_BACKEND_PACKAGES.map { diagnose(it) }

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
    private fun bindPluginService(
        packageName: String,
        preferredServiceClass: String? = null
    ): Outcome<BindResult> {
        // 1. 先按 action 绑定（标准路径）
        val actionResult = bindWithIntent(
            Intent(ACTION_BACKEND_PLUGIN).setPackage(packageName),
            packageName
        )
        if (actionResult is Outcome.Success) return actionResult
        val actionReason = actionResult.reasonOrNull().orEmpty()
        // 只有“找不到 action 对应的 Service”才降级到显式组件；超时/崩溃直接返回，
        // 避免把 10 秒超时乘以 Service 数量，变成几分钟的假死。
        if (!actionReason.contains("returned false")) return actionResult

        // 2. 兜底：逐个尝试该包内 exported 的 Service（显式 ComponentName 绑定）
        val candidates = buildList {
            if (!preferredServiceClass.isNullOrBlank()) add(preferredServiceClass)
            addAll(exportedServiceNames(packageName).filter { it != preferredServiceClass })
        }
        LogSystem.info(logTag, "bind fallback for $packageName, candidates=$candidates")
        var lastReason = actionReason
        for (cls in candidates) {
            val r = bindWithIntent(
                Intent().setComponent(ComponentName(packageName, cls)),
                packageName
            )
            if (r is Outcome.Success) {
                LogSystem.info(logTag, "bind fallback succeeded: $packageName/$cls")
                return r
            }
            lastReason = r.reasonOrNull() ?: lastReason
        }
        return Outcome.Failure(
            "找不到可绑定的插件 Service：$packageName（action 与显式组件都失败；最后原因：$lastReason）"
        )
    }

    /** 列出包内 exported 的 Service 类名，优先名字像插件入口的。 */
    private fun exportedServiceNames(packageName: String): List<String> {
        return try {
            val pm = context.packageManager
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SERVICES.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_SERVICES)
            }
            val services = pkgInfo.services.orEmpty()
                .filter { it.exported && !it.name.isNullOrBlank() }
            val nameOf: (ServiceInfo) -> String = { it.name ?: "" }
            services.sortedByDescending { s ->
                val n = nameOf(s)
                when {
                    n.contains("PluginService", true) -> 3
                    n.contains("Plugin", true) || n.contains("Backend", true) -> 2
                    else -> 1
                }
            }.map(nameOf).distinct()
        } catch (e: Throwable) {
            LogSystem.warn(logTag, "exportedServiceNames($packageName) failed: ${e.message}", e)
            emptyList()
        }
    }

    private fun bindWithIntent(intent: Intent, packageName: String): Outcome<BindResult> {
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

        val bound = try {
            context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        } catch (e: Throwable) {
            return Outcome.Failure("bindService threw: ${e.message}", e)
        }
        if (!bound) {
            return Outcome.Failure(
                "bindService returned false for $packageName (no matching service)"
            )
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

        /** 插件 Service 上声明描述符类名的 meta-data key。 */
        const val META_DESCRIPTOR_CLASS = "aibox.descriptor_class"

        /**
         * 已知后端插件包名。用于 queryIntentServices 被 ROM 过滤时的兜底探测。
         * 未安装的包 getPackageInfo 会抛 NameNotFoundException，直接忽略。
         */
        private val KNOWN_BACKEND_PACKAGES = listOf(
            "com.aibox.litert.backend",
            "com.aibox.litert",
            "com.aibox.litertlm",
            "com.aibox.litert.module",
            "com.aibox.onnx.backend",
            "com.aibox.onnx",
            "com.aibox.mnn.backend"
        )

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
    /** 描述符接口；文本描述符缺失的插件（Binder 只暴露运行时接口）为 null。 */
    val descriptor: com.aibox.plugin.IPluginDescriptor?
)

/**
 * 单个后端插件的诊断结果。
 *
 * 用来回答「装了插件为什么还提示未安装」：卡在包可见性、Service 发现、
 * 兼容性检查，还是绑定/注册阶段。
 */
data class PluginDiagnostic(
    val packageName: String,
    val installed: Boolean,
    val version: String = "",
    /** 包内 exported 的 Service 类名（按疑似入口排序）。 */
    val services: List<String> = emptyList(),
    /** 是否被 discoverPlugins 找到。 */
    val discovered: Boolean = false,
    /** 兼容性检查是否通过。 */
    val compatible: Boolean = false,
    /** 是否已绑定并注册到 BackendManagerV2。 */
    val active: Boolean = false,
    val reason: String = ""
)
