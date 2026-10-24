package com.aibox.infra.crash

import android.os.Build
import android.os.Process
import com.aibox.util.log.LogLevel
import com.aibox.util.log.LogSystem
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 全局崩溃捕获器。
 *
 * 解决的问题：用户反馈"闪退后日志只有首次启动那一小段"——根因是
 * 旧的 UncaughtExceptionHandler 把日志交给异步 executor，崩溃时 executor
 * 队列里还没写完的日志会丢失。
 *
 * 本类的设计：
 * 1. 安装 UncaughtExceptionHandler，捕获 Java/Kotlin 异常
 * 2. 同步刷盘：[LogSystem.emergencyFlushAll] + [LogSystem.writeCrashReport]
 * 3. 写崩溃独立文件，附带最近 500 条日志上下文
 * 4. Native 崩溃由 [NativeCrashHandler] 单独处理
 *
 * 崩溃报告内容：
 * - 时间戳、进程 PID
 * - 设备型号、Android 版本
 * - App 版本、构建信息
 * - 当前加载的模型、激活的后端、已加载插件
 * - 完整堆栈
 * - 最近 500 条日志（用于追溯崩溃前的状态）
 */
class CrashHandler(
    private val appVersion: String,
    private val buildInfo: String,
    private val loadedModelsProvider: () -> List<String> = { emptyList() },
    private val activeBackendsProvider: () -> List<String> = { emptyList() },
    private val loadedPluginsProvider: () -> List<String> = { emptyList() }
) {

    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    /** 安装全局异常处理器。 */
    fun install() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleUncaughtException(thread, throwable)
        }
        LogSystem.info(TAG, "CrashHandler installed")
    }

    private fun handleUncaughtException(thread: Thread, throwable: Throwable) {
        try {
            // 1. 写日志（仍走 LogSystem，会进 ring buffer + 异步文件）
            LogSystem.error(TAG, "Uncaught exception on ${thread.name}", throwable)

            // 2. 紧急同步刷盘所有 sink（关键：不让异步队列丢失日志）
            LogSystem.emergencyFlushAll()

            // 3. 构建崩溃报告
            val report = buildCrashReport(thread, throwable, isNative = false)

            // 4. 写到崩溃独立文件（含最近 500 条日志上下文）
            LogSystem.writeCrashReport(report)
        } catch (_: Throwable) {
            // 崩溃处理器自身不能再崩,否则陷入递归
        }

        // 5. 调用系统默认处理器（让用户看到崩溃对话框）
        previousHandler?.uncaughtException(thread, throwable)
    }

    /**
     * 由 NativeCrashHandler 调用，处理 native 信号崩溃（SIGSEGV/SIGABRT）。
     * 注意：此时进程已经在崩溃，必须最快速、无依赖。
     */
    fun onNativeCrash(signal: Int, nativeStackTrace: String) {
        try {
            // 紧急刷盘
            LogSystem.emergencyFlushAll()

            // 写日志
            LogSystem.fatal(TAG, "Native crash signal=$signal\n$nativeStackTrace")

            // 构建报告
            val dummyThread = Thread.currentThread()
            val dummyThrowable = NativeCrashException("Native signal $signal", nativeStackTrace)
            val report = buildCrashReport(dummyThread, dummyThrowable, isNative = true, signal = signal, nativeStackTrace = nativeStackTrace)
            LogSystem.writeCrashReport(report)
        } catch (_: Throwable) {
        }
    }

    private fun buildCrashReport(
        thread: Thread,
        throwable: Throwable,
        isNative: Boolean,
        signal: Int = 0,
        nativeStackTrace: String = ""
    ): String {
        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))
        val sw = StringWriter()
        val pw = PrintWriter(sw)

        pw.println("=== AIBox Crash Report ===")
        pw.println("Time: $ts")
        pw.println("Type: ${if (isNative) "Native (signal=$signal)" else "Java/Kotlin"}")
        pw.println("Process PID: ${Process.myPid()}")
        pw.println("Thread: ${thread.name} (id=${thread.id})")
        pw.println()
        pw.println("=== Device Info ===")
        pw.println("Manufacturer: ${Build.MANUFACTURER}")
        pw.println("Model: ${Build.MODEL}")
        pw.println("Device: ${Build.DEVICE}")
        pw.println("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        pw.println("ABI: ${Build.SUPPORTED_ABIS.joinToString(",")}")
        pw.println()
        pw.println("=== App Info ===")
        pw.println("Version: $appVersion")
        pw.println("Build: $buildInfo")
        pw.println()
        pw.println("=== App State ===")
        pw.println("Loaded Models: ${loadedModelsProvider().joinToString(", ")}")
        pw.println("Active Backends: ${activeBackendsProvider().joinToString(", ")}")
        pw.println("Loaded Plugins: ${loadedPluginsProvider().joinToString(", ")}")
        pw.println()
        pw.println("=== Stack Trace ===")
        if (isNative) {
            pw.println(nativeStackTrace)
        } else {
            throwable.printStackTrace(pw)
        }
        pw.println()
        pw.println("=== End of Crash Report Header ===")
        pw.flush()
        return sw.toString()
    }

    companion object {
        private const val TAG = "aibox.crash"

        /** Native 崩溃占位异常。 */
        class NativeCrashException(message: String, val nativeTrace: String) : Throwable(message)
    }
}
