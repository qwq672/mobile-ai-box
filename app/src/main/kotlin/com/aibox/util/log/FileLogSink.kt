package com.aibox.util.log

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock

/**
 * 文件日志 Sink：按日期轮转，自动清理过期文件。
 *
 * 文件布局：
 *   <logDir>/aibox-2026-10-20.log   # 当天
 *   <logDir>/aibox-2026-10-19.log   # 昨天
 *   <logDir>/crashes/crash-2026-10-20_19-43-21.txt   # 崩溃独立文件
 *   <logDir>/sessions/session-<id>.log   # 推理会话独立日志
 *
 * 线程安全：单线程 Executor + ReentrantLock。
 * 紧急刷盘：emergencyFlush 同步执行（绕过 executor，确保崩溃前能写完）。
 *
 * 体积控制：单文件 50MB 上限，超过自动滚动到 .1 .2 .3；
 *          maxFileAgeDays 天前的日志自动删除；总大小不超过 maxTotalSizeMb。
 */
class FileLogSink(
    private val logDir: File,
    private val maxFileBytes: Long = 50L * 1024 * 1024,
    private val maxFileAgeDays: Int = 7,
    private val maxTotalSizeMb: Int = 100
) : LogSink {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AiboxFileLog").apply { isDaemon = true }
    }
    private val writeLock = ReentrantLock()

    @Volatile private var currentDate: LocalDate = LocalDate.now()
    @Volatile private var currentWriter: BufferedWriter? = null
    @Volatile private var currentFile: File? = null

    init {
        ensureLogDir()
        openWriterForToday()
    }

    private fun ensureLogDir() {
        if (!logDir.exists()) logDir.mkdirs()
        File(logDir, "crashes").apply { if (!exists()) mkdirs() }
        File(logDir, "sessions").apply { if (!exists()) mkdirs() }
        File(logDir, "benchmarks").apply { if (!exists()) mkdirs() }
    }

    private fun openWriterForToday() {
        writeLock.lock()
        try {
            // 关闭旧 writer
            currentWriter?.let { runCatching { it.flush(); it.close() } }
            currentWriter = null

            // 按日期命名
            val dateStr = currentDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val f = File(logDir, "aibox-$dateStr.log")
            currentFile = f

            // 写入 banner
            val bw = BufferedWriter(FileWriter(f, /* append = */ true), 16 * 1024)
            bw.write("==== AIBox log session at ${LocalDateTime.now()} ====")
            bw.newLine()
            bw.flush()
            currentWriter = bw

            // 触发清理
            cleanOldFiles()
        } catch (_: Throwable) {
            // 文件系统异常不阻塞
        } finally {
            writeLock.unlock()
        }
    }

    override fun append(entry: LogEntry) {
        // 日期变了，切换文件
        val entryDate = entry.timestamp.toLocalDate()
        if (entryDate != currentDate) {
            currentDate = entryDate
            openWriterForToday()
        }

        val line = entry.formatOneLine()
        val w = currentWriter ?: return
        executor.execute {
            writeLock.lock()
            try {
                w.write(line)
                w.newLine()
                w.flush()
                rollIfNeeded()
            } catch (_: Throwable) {
                // 静默,文件系统异常不能崩溃 App
            } finally {
                writeLock.unlock()
            }
        }
    }

    /**
     * 紧急刷盘：绕过 executor，同步直接写。
     * 适用于崩溃前。
     */
    override fun emergencyFlush() {
        writeLock.lock()
        try {
            currentWriter?.let { w ->
                w.flush()
            }
        } catch (_: Throwable) {
        } finally {
            writeLock.unlock()
        }
    }

    /** 写一条崩溃报告到独立文件。 */
    fun writeCrashReport(crashReport: String, recentContext: List<LogEntry>) {
        writeLock.lock()
        try {
            val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
            val crashFile = File(File(logDir, "crashes"), "crash-$ts.txt")
            PrintWriter(FileWriter(crashFile, false), true).use { pw ->
                pw.println(crashReport)
                pw.println()
                pw.println("=== Recent Log Context (last ${recentContext.size} entries) ===")
                recentContext.forEach { entry ->
                    pw.println(entry.formatOneLine())
                }
                pw.println()
                pw.println("=== End of Crash Report ===")
                pw.flush()
            }
        } catch (_: Throwable) {
        } finally {
            writeLock.unlock()
        }
    }

    /** 写推理/基准测试会话独立日志。 */
    fun writeSessionLog(sessionId: String, content: String) {
        executor.execute {
            writeLock.lock()
            try {
                val sessionFile = File(File(logDir, "sessions"), "session-$sessionId.log")
                PrintWriter(FileWriter(sessionFile, /* append = */ true), true).use { pw ->
                    pw.println(content)
                    pw.flush()
                }
            } catch (_: Throwable) {
            } finally {
                writeLock.unlock()
            }
        }
    }

    /** 写基准测试结果到独立目录。 */
    fun writeBenchmarkResult(sessionId: String, content: String) {
        executor.execute {
            writeLock.lock()
            try {
                val benchFile = File(File(logDir, "benchmarks"), "bench-$sessionId.log")
                PrintWriter(FileWriter(benchFile, false), true).use { pw ->
                    pw.println(content)
                    pw.flush()
                }
            } catch (_: Throwable) {
            } finally {
                writeLock.unlock()
            }
        }
    }

    private fun rollIfNeeded() {
        val f = currentFile ?: return
        if (f.length() > maxFileBytes) {
            // 滚动到 .1 .2
            val rotated = File(f.parentFile, f.name + ".1")
            if (rotated.exists()) rotated.delete()
            f.renameTo(rotated)
            // 重开
            val bw = BufferedWriter(FileWriter(f, false), 16 * 1024)
            currentWriter = bw
        }
    }

    private fun cleanOldFiles() {
        try {
            val cutoff = LocalDate.now().minusDays(maxFileAgeDays.toLong())
            logDir.listFiles { file ->
                file.name.startsWith("aibox-") && file.name.endsWith(".log")
            }?.forEach { f ->
                val dateStr = f.name.removePrefix("aibox-").removeSuffix(".log")
                runCatching {
                    LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE)
                }.getOrNull()?.let { date ->
                    if (date.isBefore(cutoff)) f.delete()
                }
            }
            // 总大小检查
            val totalBytes = logDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
            if (totalBytes > maxTotalSizeMb * 1024L * 1024L) {
                // 删除最旧的日志文件
                logDir.listFiles { f -> f.name.startsWith("aibox-") }
                    ?.sortedBy { it.lastModified() }
                    ?.take((totalBytes - maxTotalSizeMb * 1024L * 1024L).toInt() / (1024 * 1024) + 1)
                    ?.forEach { it.delete() }
            }
        } catch (_: Throwable) {
        }
    }

    fun getLogFile(): File? = currentFile

    fun listLogFiles(): List<File> = logDir.listFiles { f ->
        f.name.startsWith("aibox-") && f.name.endsWith(".log")
    }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun listCrashFiles(): List<File> = File(logDir, "crashes")
        .listFiles { f -> f.name.startsWith("crash-") }
        ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun listSessionFiles(): List<File> = File(logDir, "sessions")
        .listFiles { f -> f.name.startsWith("session-") }
        ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun listBenchmarkFiles(): List<File> = File(logDir, "benchmarks")
        .listFiles { f -> f.name.startsWith("bench-") }
        ?.sortedByDescending { it.lastModified() } ?: emptyList()

    override fun clear() {
        writeLock.lock()
        try {
            listLogFiles().forEach { it.delete() }
            listCrashFiles().forEach { it.delete() }
            listSessionFiles().forEach { it.delete() }
            listBenchmarkFiles().forEach { it.delete() }
        } catch (_: Throwable) {
        } finally {
            writeLock.unlock()
        }
    }

    override fun close() {
        emergencyFlush()
        writeLock.lock()
        try {
            currentWriter?.let { runCatching { it.close() } }
            currentWriter = null
        } finally {
            writeLock.unlock()
        }
        executor.shutdown()
    }
}
