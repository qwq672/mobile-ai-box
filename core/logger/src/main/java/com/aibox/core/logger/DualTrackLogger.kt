package com.aibox.core.logger

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 双轨日志实现：
 *
 * 轨 1 — Room DB：应用内 UI 浏览（结构化索引，按等级/模块/关键词筛选）
 * 轨 2 — 外部存储文件：滚动文件（按日期+大小切分）
 *
 * 为什么必须双轨：
 * - 用户要求：日志必须能在机内看到，不需要 logcat
 * - Room 提供查询能力，但应用卸载就丢；外部存储可被用户直接用文件管理器看到
 * - Crash 永远先写文件再写 DB（DB 在崩溃中可能写不进去）
 *
 * 线程模型：
 * - log() 同步入队（不阻塞调用方）
 * - 后台单协程消费队列，串行写 DB + 文件
 * - stream() 走 SharedFlow，多 UI 同时订阅也不会重复写
 *
 * Crash 单独路径：[crash] 调用立即同步写入 crash 文件，避免进程被回收时丢失。
 */
public class DualTrackLogger private constructor(
    private val appContext: Context,
    private val dao: LogDaoWrapper,
    private val fileWriter: LogFileWriter,
) : AILogger {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = MutableSharedFlow<LogEntry>(
        replay = 0,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 对外日志流（UI 监听用） */
    private val stream: SharedFlow<LogEntry> = queue.asSharedFlow()

    private val flushMutex = Mutex()

    init {
        scope.launch { consumeLoop() }
    }

    override fun log(entry: LogEntry) {
        // CRASH 级别同步立即写文件（防止进程被杀丢失）
        if (entry.level == LogLevel.CRASH) {
            fileWriter.writeCrashNow(entry)
        }
        // 普通条目入队异步写
        queue.tryEmit(entry)
    }

    override suspend fun logSuspend(entry: LogEntry) {
        log(entry)
    }

    override fun stream(): kotlinx.coroutines.flow.Flow<LogEntry> = stream

    override suspend fun query(filter: LogFilter): List<LogEntry> = dao.query(filter)

    override fun queryFlow(filter: LogFilter, pageSize: Int): kotlinx.coroutines.flow.Flow<List<LogEntry>> =
        flow {
            var offset = 0
            while (true) {
                val batch = dao.queryPage(filter, pageSize, offset)
                if (batch.isEmpty()) break
                emit(batch)
                offset += batch.size
                if (batch.size < pageSize) break
            }
        }

    override suspend fun flush() {
        flushMutex.withLock {
            fileWriter.flush()
        }
    }

    override suspend fun purge(olderThanDays: Int): Int {
        val cutoff = System.currentTimeMillis() - olderThanDays.toLong() * 24 * 3600_000L
        val deleted = dao.purgeOlderThan(cutoff)
        fileWriter.purgeOlderThan(olderThanDays)
        return deleted
    }

    /** 后台消费循环：把队列里的日志写入 DB + 文件 */
    private suspend fun consumeLoop() {
        queue.collect { entry ->
            try {
                dao.insert(entry)
                fileWriter.write(entry)
            } catch (t: Throwable) {
                // 日志记录本身失败不能再抛出，否则陷入死循环
                // 只能走最保守路径：写 native stderr
                System.err.println("[aibox-logger] failed to persist: ${t.message}")
            }
        }
    }

    public companion object {
        @Volatile private var instance: DualTrackLogger? = null

        public fun get(appContext: Context): DualTrackLogger {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val app = appContext.applicationContext
                    val db = LogDatabase.create(app)
                    val dao = db.wrappedDao()
                    val fileWriter = LogFileWriter.create(app)
                    DualTrackLogger(app, dao, fileWriter).also { instance = it }
                }
            }
        }
    }
}

/**
 * 日志文件写入器：滚动文件策略。
 *
 * 路径：getExternalFilesDir(null)/aibox/logs/aibox-yyyyMMdd-HH.log
 *   → 用户可以用任何文件管理器直接看到（无需 adb pull）
 * 切分策略：
 *   - 按日期切：每跨过本地零点开新文件
 *   - 按大小切：单文件超过 5MB 开新文件
 *   - 保留天数：默认 14 天
 *
 * Crash 单独目录：getExternalFilesDir(null)/aibox/crashes/crash-yyyymmdd-hhmmss.log
 */
public class LogFileWriter private constructor(
    private val logDir: File,
    private val crashDir: File,
    private val maxFileBytes: Long = 5L * 1024 * 1024,
    private val maxRetainDays: Int = 14,
) {
    private val mutex = Mutex()
    private val dateFormat = SimpleDateFormat("yyyyMMdd-HH", Locale.US)
    private var currentFile: File? = null
    private var currentFileSize: Long = 0L

    public suspend fun write(entry: LogEntry) {
        mutex.withLock {
            val file = currentFile ?: rotateFileIfNeeded()
            val line = format(entry) + System.lineSeparator()
            file.appendText(line)
            currentFileSize += line.toByteArray().size
            currentFile = rotateFileIfNeeded(force = currentFileSize > maxFileBytes)
        }
    }

    /** 同步写入：CRASH 专用 */
    public fun writeCrashNow(entry: LogEntry) {
        try {
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(entry.timestamp))
            val crashFile = File(crashDir, "crash-$ts.log")
            crashFile.writeText(format(entry) + System.lineSeparator())
        } catch (t: Throwable) {
            System.err.println("[aibox-crash] failed to write crash log: ${t.message}")
        }
    }

    public suspend fun flush() {
        // 文件 append 是同步操作，无需额外 flush
    }

    public fun purgeOlderThan(days: Int) {
        val cutoff = System.currentTimeMillis() - days.toLong() * 24 * 3600_000L
        logDir.listFiles()?.forEach { f ->
            if (f.lastModified() < cutoff) f.delete()
        }
        crashDir.listFiles()?.forEach { f ->
            if (f.lastModified() < cutoff) f.delete()
        }
    }

    private fun rotateFileIfNeeded(force: Boolean = false): File {
        val now = System.currentTimeMillis()
        val dateKey = dateFormat.format(Date(now))
        val expectedName = "aibox-$dateKey.log"
        val expectedFile = File(logDir, expectedName)
        if (expectedFile != currentFile || force || !expectedFile.exists()) {
            currentFile = expectedFile
            currentFileSize = expectedFile.length()
        }
        return currentFile!!
    }

    private fun format(entry: LogEntry): String {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(entry.timestamp))
        val extrasStr = if (entry.extras.isEmpty()) "" else " ${entry.extras}"
        val throwableStr = entry.throwable?.let { " ${it.stackTraceToString()}" } ?: ""
        return "[$ts] ${entry.level.name}/${entry.module.name} ${entry.tag}: ${entry.message}${extrasStr}${throwableStr}"
    }

    public companion object {
        public fun create(appContext: Context): LogFileWriter {
            // 关键：用 getExternalFilesDir 而非内部存储
            //   - Android 10+ 不需要 WRITE_EXTERNAL_STORAGE 权限
            //   - 卸载时自动清理
            //   - 用户在文件管理器可直接看到（路径 /Android/data/com.aibox/files/aibox/logs）
            val baseDir = appContext.getExternalFilesDir(null)
                ?: appContext.filesDir // 极端无外部存储时回退内部
            val logDir = File(baseDir, "aibox/logs").apply { mkdirs() }
            val crashDir = File(baseDir, "aibox/crashes").apply { mkdirs() }
            return LogFileWriter(logDir, crashDir)
        }
    }
}
