package com.aibox.litert

import com.aibox.plugin.IPluginLogCallback

/**
 * LiteRT 插件日志桥:把所有 [LiteRTBackend] / [LiteRTBinder] / [LiteRTDescriptor] 的日志
 * 统一通过 [IPluginLogCallback] 回传宿主 [com.aibox.util.log.LogSystem]。
 *
 * 设计原则:
 *  1. **不写本地日志文件**:插件进程崩溃 / 被杀时本地文件可能丢;宿主 LogSystem 有 ring buffer
 *     + emergencyFlush + 独立崩溃文件,把日志送到宿主统一刷盘更稳。
 *  2. **降级到 logcat**:宿主尚未调 `setLogCallback` 之前(或宿主崩溃导致 callback 死),
 *     日志降级到 android.util.Log,便于 adb 抓取调试;callback 可用后再回传宿主。
 *  3. **线程安全**:[callback] 用 @Volatile,允许多线程并发写;[log] 同步调 callback.onLog,
 *     binder onTranscat 是线程安全的(每调用一份 marshalling)。
 *
 * level 常量与 android.util.Log 对齐(2=VERBOSE / 3=DEBUG / 4=INFO / 5=WARN / 6=ERROR),
 * 与 [IPluginLogCallback.onLog] 的 level 参数一致。
 */
object LiteRTLogger {

    /** tag 统一前缀,便于宿主 LogSystem.setTagLevel 过滤。 */
    private const val TAG_PREFIX = "aibox.plugin.litert"

    @Volatile
    private var callback: IPluginLogCallback? = null

    /** 宿主通过 [LiteRTBinder.setLogCallback] 注入;传 null 表示宿主解绑。 */
    fun setCallback(cb: IPluginLogCallback?) {
        callback = cb
        // 切换 callback 时打一条 info,确认桥接已建立(降级 logcat 路径)。
        android.util.Log.i(TAG_PREFIX, if (cb != null) "log bridge to host established" else "log bridge disconnected")
    }

    fun verbose(tag: String, message: String) = log(android.util.Log.VERBOSE, tag, message)
    fun debug(tag: String, message: String) = log(android.util.Log.DEBUG, tag, message)
    fun info(tag: String, message: String) = log(android.util.Log.INFO, tag, message)
    fun warn(tag: String, message: String, throwable: Throwable? = null) {
        val full = if (throwable != null) "$message\n${throwable.stackTraceToString()}" else message
        log(android.util.Log.WARN, tag, full)
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val full = if (throwable != null) "$message\n${throwable.stackTraceToString()}" else message
        log(android.util.Log.ERROR, tag, full)
    }

    /**
     * 统一入口:先尝试回传宿主,失败降级 logcat。
     *
     * 调用方传 [tag] 通常为 `$TAG_PREFIX.<sub>` 形式(如 `aibox.plugin.litert.backend`),
     * 这里不强加前缀(避免双重前缀),仅保证 [TAG_PREFIX] 用于 fallback logcat。
     */
    fun log(level: Int, tag: String, message: String) {
        val cb = callback
        if (cb != null) {
            try {
                cb.onLog(level, tag, message)
                return
            } catch (e: Throwable) {
                // 宿主 binder 死亡 / RemoteException → 降级 logcat,下次 setLogCallback 会重置。
                android.util.Log.w(TAG_PREFIX, "log callback failed, fallback to logcat: ${e.message}")
            }
        }
        // 降级 logcat(仅 callback 未注入或调用失败时)。
        android.util.Log.println(level, tag, message)
    }
}
