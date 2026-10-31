package com.aibox.util.log

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.locks.ReentrantLock

/**
 * 内存环形缓冲 Sink。
 *
 * - 默认保留最近 [capacity] 条日志，超过自动剔除最旧
 * - 每 [flushChunkSize] 条触发一次异步落盘（避免每条都 I/O）
 * - 关键方法 [emergencyFlush]：崩溃前同步刷盘，保证不丢
 * - [snapshot] 返回最近 N 条日志（崩溃报告用）
 *
 * 线程安全：ConcurrentLinkedDeque + ReentrantLock 保护"取快照 + 紧急刷盘"原子操作。
 */
class RingBufferLogSink(
    private val capacity: Int = 5000,
    private val flushChunkSize: Int = 200,
    private val onChunkFlush: ((List<LogEntry>) -> Unit)? = null  // 通知下游文件 sink 异步刷盘
) : LogSink {

    private val buffer = ConcurrentLinkedDeque<LogEntry>()
    private val flushLock = ReentrantLock()
    @Volatile private var pendingCount = 0

    override fun append(entry: LogEntry) {
        // 满了剔除最旧
        while (buffer.size >= capacity) {
            buffer.pollFirst()
        }
        buffer.addLast(entry)
        pendingCount++
        // 达到 chunk size 触发异步刷盘
        if (pendingCount >= flushChunkSize) {
            flushLock.lock()
            try {
                if (pendingCount >= flushChunkSize) {
                    val chunk = ArrayList<LogEntry>(flushChunkSize)
                    for (i in 0 until flushChunkSize) {
                        buffer.peekFirst()?.let { chunk.add(it) }
                    }
                    pendingCount = 0
                    onChunkFlush?.invoke(chunk)
                }
            } finally {
                flushLock.unlock()
            }
        }
    }

    /**
     * 紧急刷盘：崩溃前调用，把当前所有缓冲日志同步交给下游文件 sink。
     * 必须快速、无锁等待、不抛异常。
     */
    override fun emergencyFlush() {
        flushLock.lock()
        try {
            val snapshot = buffer.toList()
            onChunkFlush?.invoke(snapshot)
            pendingCount = 0
        } catch (_: Throwable) {
            // 紧急刷盘不能抛异常,否则崩溃处理器自己崩溃
        } finally {
            flushLock.unlock()
        }
    }

    /** 返回最近 N 条日志（崩溃报告附加上下文用）。 */
    fun snapshot(lastN: Int = 500): List<LogEntry> {
        val list = buffer.toList()
        return if (list.size <= lastN) list else list.takeLast(lastN)
    }

    /** 返回全部缓冲（用于 UI 实时查看）。 */
    fun snapshotAll(): List<LogEntry> = buffer.toList()

    /** 按 tag 过滤的快照。 */
    fun snapshotByTag(vararg tagPrefixes: String, lastN: Int = 200): List<LogEntry> {
        return buffer.toList()
            .filter { entry -> tagPrefixes.any { entry.tag.startsWith(it) } }
            .takeLast(lastN)
    }

    /** 按 sessionId 过滤。 */
    fun snapshotBySession(sessionId: String): List<LogEntry> {
        return buffer.filter { it.sessionId == sessionId }
    }

    override fun clear() {
        buffer.clear()
        pendingCount = 0
    }

    override fun close() {
        emergencyFlush()
        buffer.clear()
    }
}
