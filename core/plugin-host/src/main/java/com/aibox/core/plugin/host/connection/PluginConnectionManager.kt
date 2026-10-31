package com.aibox.core.plugin.host.connection

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.aibox.core.logger.AILogger
import com.aibox.core.logger.LogModule
import com.aibox.core.logger.error
import com.aibox.core.logger.info
import com.aibox.core.logger.warn
import com.aibox.plugin.aidl.IPluginInterface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 插件连接管理器。
 *
 * 反屎山原则：
 * - 每个插件维护独立 [ServiceConnection] 便于按需解绑
 * - 连接必须超时控制（5s）：插件进程崩溃不能阻塞宿主
 * - 重连策略：插件死亡时由 [PluginDeathRecipient] 触发清理
 * - 必须在 [release] 时彻底解绑，防止 Activity 泄漏
 *
 * 性能：bindService 比 startService 慢（IPC 建立），
 * 所以缓存已绑定的 [IPluginInterface]，避免每次推理都重新绑定。
 */
public class PluginConnectionManager(
    private val appContext: Context,
    private val logger: AILogger,
) {
    private val connections = ConcurrentHashMap<String, PluginConnection>()
    private val lock = Any()

    public suspend fun connect(packageName: String, className: String, timeoutMs: Long = 5000L): IPluginInterface? {
        connections[packageName]?.let { return it.binder }

        val connection = synchronized(lock) {
            connections[packageName]?.let { return it.binder }
            PluginConnection(appContext, logger, packageName, className)
        }
        val connected: IPluginInterface? = withTimeoutOrNull(timeoutMs) {
            connection.bind()
        } ?: run {
            logger.warn(LogModule.PLUGIN_HOST, "connection", "timeout binding $packageName")
            return null
        }

        if (connected == null || connection.binder == null) {
            logger.error(LogModule.PLUGIN_HOST, "connection", "failed to bind $packageName")
            return null
        }
        synchronized(lock) {
            if (connections[packageName] != null) return connections[packageName]!!.binder
            connections[packageName] = connection
        }
        logger.info(LogModule.PLUGIN_HOST, "connection", "connected to $packageName")
        return connection.binder
    }

    public fun disconnect(packageName: String) {
        connections.remove(packageName)?.unbind()
    }

    public fun release() {
        connections.values.forEach { it.unbind() }
        connections.clear()
    }
}

/** 单个插件的连接封装 */
private class PluginConnection(
    private val appContext: Context,
    private val logger: AILogger,
    private val packageName: String,
    private val className: String,
) {
    @Volatile var binder: IPluginInterface? = null
        private set

    private val ready = CompletableDeferred<IPluginInterface?>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = IPluginInterface.Stub.asInterface(service)
            ready.complete(binder)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            logger.warn(LogModule.PLUGIN_HOST, "connection", "disconnected: $packageName")
            binder = null
        }

        override fun onBindingDied(name: ComponentName?) {
            logger.error(LogModule.PLUGIN_HOST, "connection", "died: $packageName")
            binder = null
            ready.complete(null)
        }

        override fun onNullBinding(name: ComponentName?) {
            logger.error(LogModule.PLUGIN_HOST, "connection", "null binding: $packageName")
            ready.complete(null)
        }
    }

    suspend fun bind(): IPluginInterface? {
        val intent = Intent().apply {
            component = ComponentName(packageName, className)
        }
        val flags = Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
        val bound = try {
            appContext.bindService(intent, serviceConnection, flags)
        } catch (t: Throwable) {
            logger.error(LogModule.PLUGIN_HOST, "connection", "bindService error: ${t.message}", t)
            return null
        }
        if (!bound) {
            logger.error(LogModule.PLUGIN_HOST, "connection", "bindService returned false for $packageName")
            return null
        }
        return ready.await()
    }

    fun unbind() {
        try {
            appContext.unbindService(serviceConnection)
        } catch (_: Throwable) {
            // 已经解绑过了，忽略
        }
        binder = null
    }
}
