package com.pocketllm.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用级日志器。
 *
 * 输出位置：
 *   /sdcard/Android/data/com.pocketllm/files/pocketllm.log
 * （即 Context.getExternalFilesDir(null)/pocketllm.log，无需 READ/WRITE_EXTERNAL_STORAGE 权限）
 *
 * 同时仍输出到 logcat 便于 adb 抓取。
 *
 * 线程安全：通过单线程 Executor 串行写文件；in-memory ring buffer 提供 UI 实时查看。
 *
 * 体积控制：超过 5MB 自动滚动到 .old，旧文件覆盖。
 */
object AppLogger {

    private const val TAG = "PocketLLM"
    private const val MAX_FILE_BYTES = 5L * 1024 * 1024
    private const val RING_BUFFER_SIZE = 1000

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AppLogger").apply { isDaemon = true }
    }
    private val ring = ConcurrentLinkedQueue<String>()
    private val enabled = AtomicBoolean(true)
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var logFile: File? = null
    @Volatile private var writer: java.io.BufferedWriter? = null

    /** 必须先调用 init 才会写文件；只 logcat 不写文件也可使用 [log] */
    fun init(context: Context) {
        try {
            // Android 10+ 应用专属外部目录：/sdcard/Android/data/<pkg>/files
            val dir = context.getExternalFilesDir(null)
                ?: File(context.filesDir, "external").apply { mkdirs() }
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "pocketllm.log")
            logFile = f
            // 用 append 模式打开；后续 log() 调用 write + flush
            writer = java.io.BufferedWriter(
                java.io.FileWriter(f, /* append = */ true),
                16 * 1024
            )
            // 起始分隔
            val banner = "==== PocketLLM log start at ${Date()} ===="
            writer?.write(banner); writer?.newLine(); writer?.flush()
            // 滚动旧文件
            rollIfNeeded()
            log(Log.INFO, "AppLogger", "log file: ${f.absolutePath}")
        } catch (t: Throwable) {
            // 退化到只 logcat
            Log.e(TAG, "AppLogger.init failed", t)
        }
    }

    /** 设置开关：从设置页面切换。silent=true 时不要打印 log（避免被 DataStore flow emit 触发死循环）。 */
    fun setEnabled(on: Boolean, silent: Boolean = false) {
        val changed = enabled.getAndSet(on) != on
        enabled.set(on)
        if (changed && !silent) {
            log(Log.INFO, "AppLogger", "logging ${if (on) "enabled" else "disabled"}")
        }
    }

    fun isEnabled(): Boolean = enabled.get()

    /** 返回当前 ring buffer 的快照，供 UI 显示 */
    fun snapshot(): List<String> = ring.toList()

    /** 返回当前日志文件路径（用于设置页"打开"提示） */
    fun filePath(): String? = logFile?.absolutePath

    @JvmOverloads
    fun log(priority: Int, tag: String = TAG, msg: String, t: Throwable? = null) {
        if (!enabled.get()) return
        val ts = formatter.format(Date())
        val level = when (priority) {
            Log.VERBOSE -> "V"
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            else -> "?"
        }
        val body = if (t != null) {
            val sw = StringWriter(); val pw = PrintWriter(sw); t.printStackTrace(pw)
            "$msg\n$sw"
        } else msg
        val line = "$ts $level/$tag: $body"
        // logcat
        when (priority) {
            Log.VERBOSE -> Log.v(tag, msg, t)
            Log.DEBUG -> Log.d(tag, msg, t)
            Log.INFO -> Log.i(tag, msg, t)
            Log.WARN -> Log.w(tag, msg, t)
            Log.ERROR -> Log.e(tag, msg, t)
            else -> Log.i(tag, msg, t)
        }
        // ring
        offer(line)
        // file
        val w = writer ?: return
        executor.execute {
            try {
                synchronized(w) {
                    w.write(line); w.newLine(); w.flush()
                }
                rollIfNeeded()
            } catch (_: Throwable) { /* ignore */ }
        }
    }

    /** 清空日志文件 */
    fun clear() {
        ring.clear()
        val w = writer ?: return
        executor.execute {
            try {
                synchronized(w) {
                    w.flush()
                    logFile?.let { f ->
                        if (f.exists()) {
                            java.io.PrintWriter(java.io.FileWriter(f, false)).use { it.print("") }
                        }
                    }
                }
                log(Log.INFO, "AppLogger", "log cleared")
            } catch (_: Throwable) { /* ignore */ }
        }
    }

    private fun offer(line: String) {
        ring.add(line)
        while (ring.size > RING_BUFFER_SIZE) ring.poll()
    }

    private fun rollIfNeeded() {
        val f = logFile ?: return
        try {
            if (f.length() > MAX_FILE_BYTES) {
                val old = File(f.parentFile, "pocketllm.log.old")
                if (old.exists()) old.delete()
                f.renameTo(old)
                // 重新打开 writer
                synchronized(writer!!) {
                    writer?.flush()
                    writer?.close()
                    }
                writer = java.io.BufferedWriter(
                    java.io.FileWriter(f, false),
                    16 * 1024
                )
            }
        } catch (_: Throwable) { /* ignore */ }
    }
}

/* Kotlin 便捷封装 */
fun logi(msg: String, tag: String = "PocketLLM") = AppLogger.log(Log.INFO, tag, msg)
fun logw(msg: String, t: Throwable? = null, tag: String = "PocketLLM") = AppLogger.log(Log.WARN, tag, msg, t)
fun loge(msg: String, t: Throwable? = null, tag: String = "PocketLLM") = AppLogger.log(Log.ERROR, tag, msg, t)
