package com.aibox.util.log

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 单条日志记录的结构化表示。
 *
 * 设计原则：不可变（data class + copy），与"零异常约定"对齐。
 *
 * @property timestamp  时间戳（毫秒精度）
 * @property level      日志级别
 * @property tag        标签（用于过滤，如 "aibox.llamaCpp", "plugin.litert"）
 * @property threadName 线程名（调试用）
 * @property threadId   线程 ID
 * @property message    消息正文
 * @property throwable  关联的异常（可选）
 * @property sessionId  所属会话 ID（可选，用于推理/基准测试会话独立日志）
 */
data class LogEntry(
    val timestamp: LocalDateTime = LocalDateTime.now(),
    val level: LogLevel,
    val tag: String,
    val threadName: String = Thread.currentThread().name,
    val threadId: Long = Thread.currentThread().id,
    val message: String,
    val throwable: Throwable? = null,
    val sessionId: String? = null
) {
    private val tsFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    /** 格式化为单行字符串（写入日志文件用）。 */
    fun formatOneLine(): String {
        val ts = timestamp.format(tsFmt)
        val levelLabel = level.label
        val tid = "%-15s".format(threadName.take(15))
        val body = if (throwable != null) {
            val sw = java.io.StringWriter()
            throwable.printStackTrace(java.io.PrintWriter(sw))
            "$message\n$sw"
        } else {
            message
        }
        val sessionSuffix = sessionId?.let { " [$it]" } ?: ""
        return "$ts $tid $levelLabel/$tag$sessionSuffix: $body"
    }

    /** 短格式（UI 列表显示用，截断 message）。 */
    fun formatShort(maxLen: Int = 120): String {
        val ts = timestamp.format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
        val body = message.replace('\n', ' ')
        val truncated = if (body.length > maxLen) body.take(maxLen) + "…" else body
        return "$ts ${level.label}/$tag: $truncated"
    }
}
