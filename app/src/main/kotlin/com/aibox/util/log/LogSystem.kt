package com.aibox.util.log

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 统一日志系统入口。
 *
 * 设计原则：
 * 1. 所有日志走这里，统一分发到所有 sink
 * 2. 崩溃前可同步刷盘（[emergencyFlushAll]）
 * 3. 支持按 tag 配置级别（如 aibox.benchmark 用 DEBUG，aibox.ui 用 WARN）
 * 4. 支持推理/基准测试会话独立日志
 * 5. 全静态 API（和 android.util.Log 一样的使用体验）
 *
 * 使用：
 *   LogSystem.init(context)
 *   LogSystem.info("aibox.backend", "Loading model...")
 *   LogSystem.debug("aibox.benchmark", "PP: $pp t/s")
 *   LogSystem.warn("plugin.litert", "NPU delegate init failed", e)
 *
 * 会话日志：
 *   val sessionId = LogSystem.startSession("inference", "qwen2.5-0.5b")
 *   LogSystem.info(tag, "msg", sessionId = sessionId)
 *   LogSystem.endSession(sessionId, "=== Session End ===")
 */
object LogSystem {

    private val sinks = CopyOnWriteArrayList<LogSink>()
    private val tagLevels = ConcurrentHashMap<String, LogLevel>()
    @Volatile private var rootLevel: LogLevel = LogLevel.DEBUG
    @Volatile private var initialized = false
    @Volatile private var fileLogSink: FileLogSink? = null
    @Volatile private var ringBufferSink: RingBufferLogSink? = null

    /** 必须先 init 才会写文件；不 init 也可用（只 logcat）。 */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return

            // 日志目录：/sdcard/Android/data/com.aibox/files/aibox/logs/
            val logDir = File(context.getExternalFilesDir(null), "aibox/logs")

            // 1. RingBuffer（崩溃前紧急刷盘）
            val ring = RingBufferLogSink(
                capacity = 5000,
                flushChunkSize = 200,
                onChunkFlush = { chunk -> fileLogSink?.let { fileSink ->
                    chunk.forEach { fileSink.append(it) }
                } }
            )
            ringBufferSink = ring

            // 2. FileLogSink（按日期轮转）
            val fileSink = FileLogSink(logDir)
            fileLogSink = fileSink

            // 3. Logcat
            val logcatSink = AndroidLogcatSink()

            sinks.add(logcatSink)    // 先加 logcat,保证即时可见
            sinks.add(ring)
            sinks.add(fileSink)

            initialized = true

            info(TAG, "LogSystem initialized, dir=${logDir.absolutePath}")
        }
    }

    /** 通用日志入口。 */
    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null, sessionId: String? = null) {
        if (!shouldLog(tag, level)) return
        val entry = LogEntry(
            level = level,
            tag = tag,
            message = message,
            throwable = throwable,
            sessionId = sessionId
        )
        sinks.forEach { sink ->
            try {
                sink.append(entry)
            } catch (_: Throwable) {
                // sink 异常不能影响主流程
            }
        }
    }

    /** 紧急刷盘：崩溃前调用。同步刷所有 sink。 */
    fun emergencyFlushAll() {
        sinks.forEach { sink ->
            try {
                sink.emergencyFlush()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 写崩溃报告到独立文件，附带 ring buffer 中最近 500 条日志作为上下文。
     * 同步执行，崩溃前必须能写完。
     */
    fun writeCrashReport(report: String) {
        val ring = ringBufferSink ?: return
        val fileSink = fileLogSink ?: return
        val context = ring.snapshot(500)
        fileSink.writeCrashReport(report, context)
    }

    /** 写推理/基准测试会话独立日志。 */
    fun writeSessionLog(sessionId: String, content: String) {
        fileLogSink?.writeSessionLog(sessionId, content)
    }

    /** 写基准测试结果到独立目录。 */
    fun writeBenchmarkResult(sessionId: String, content: String) {
        fileLogSink?.writeBenchmarkResult(sessionId, content)
    }

    /** 启动会话，返回 sessionId。 */
    fun startSession(category: String, name: String): String {
        val ts = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val id = "${category}_${ts}_${name.replace(Regex("[^a-zA-Z0-9.-]"), "")}"
        info(TAG, "=== Session Start: $id ===")
        return id
    }

    /** 结束会话。 */
    fun endSession(sessionId: String, summary: String = "") {
        info(TAG, "=== Session End: $sessionId ===\n$summary")
    }

    // ============== 便捷 API ==============

    fun verbose(tag: String, msg: String, sessionId: String? = null) =
        log(LogLevel.VERBOSE, tag, msg, sessionId = sessionId)
    fun debug(tag: String, msg: String, sessionId: String? = null) =
        log(LogLevel.DEBUG, tag, msg, sessionId = sessionId)
    fun info(tag: String, msg: String, sessionId: String? = null) =
        log(LogLevel.INFO, tag, msg, sessionId = sessionId)
    fun warn(tag: String, msg: String, t: Throwable? = null, sessionId: String? = null) =
        log(LogLevel.WARN, tag, msg, t, sessionId)
    fun error(tag: String, msg: String, t: Throwable? = null, sessionId: String? = null) =
        log(LogLevel.ERROR, tag, msg, t, sessionId)
    fun fatal(tag: String, msg: String, t: Throwable? = null) =
        log(LogLevel.FATAL, tag, msg, t)

    // ============== 级别控制 ==============

    fun setRootLevel(level: LogLevel) {
        rootLevel = level
    }

    fun setTagLevel(tagPrefix: String, level: LogLevel) {
        tagLevels[tagPrefix] = level
    }

    private fun shouldLog(tag: String, level: LogLevel): Boolean {
        // 1. 查 tag 前缀级别（最长前缀匹配）
        val tagLevel = findTagLevel(tag)
        if (tagLevel != null) return level.priority >= tagLevel.priority

        // 2. 根级别
        return level.priority >= rootLevel.priority
    }

    private fun findTagLevel(tag: String): LogLevel? {
        var best: LogLevel? = null
        var bestLen = -1
        tagLevels.forEach { (prefix, level) ->
            if (tag.startsWith(prefix) && prefix.length > bestLen) {
                best = level
                bestLen = prefix.length
            }
        }
        return best
    }

    // ============== UI 访问 API ==============

    /** 返回 ring buffer 快照（UI 实时查看）。 */
    fun snapshot(lastN: Int = 1000): List<LogEntry> {
        return ringBufferSink?.snapshot(lastN) ?: emptyList()
    }

    /** 按 tag 过滤的快照。 */
    fun snapshotByTag(vararg tagPrefixes: String, lastN: Int = 500): List<LogEntry> {
        return ringBufferSink?.snapshotByTag(*tagPrefixes, lastN = lastN) ?: emptyList()
    }

    /** 列出所有日志文件。 */
    fun listLogFiles(): List<File> = fileLogSink?.listLogFiles() ?: emptyList()
    fun listCrashFiles(): List<File> = fileLogSink?.listCrashFiles() ?: emptyList()
    fun listSessionFiles(): List<File> = fileLogSink?.listSessionFiles() ?: emptyList()
    fun listBenchmarkFiles(): List<File> = fileLogSink?.listBenchmarkFiles() ?: emptyList()

    /** 获取日志根目录路径（用户/分享用）。 */
    fun getLogDirPath(): String? = fileLogSink?.let {
        it.listLogFiles().firstOrNull()?.parentFile?.absolutePath
    }

    /** 清空所有日志。 */
    fun clearAll() {
        sinks.forEach { runCatching { it.clear() } }
    }

    private const val TAG = "aibox.log"
}
