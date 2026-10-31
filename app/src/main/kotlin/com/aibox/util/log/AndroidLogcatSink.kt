package com.aibox.util.log

import android.util.Log

/**
 * Android logcat Sink。让所有日志也输出到 logcat，方便 adb 抓取。
 *
 * 只读，不持久化。
 */
class AndroidLogcatSink : LogSink {
    override fun append(entry: LogEntry) {
        val tag = entry.tag.take(23)  // logcat tag 限长
        when (entry.level) {
            LogLevel.VERBOSE -> Log.v(tag, entry.message, entry.throwable)
            LogLevel.DEBUG -> Log.d(tag, entry.message, entry.throwable)
            LogLevel.INFO -> Log.i(tag, entry.message, entry.throwable)
            LogLevel.WARN -> Log.w(tag, entry.message, entry.throwable)
            LogLevel.ERROR -> Log.e(tag, entry.message, entry.throwable)
            LogLevel.FATAL -> Log.e(tag, "[FATAL] ${entry.message}", entry.throwable)
        }
    }

    override fun emergencyFlush() { /* 无需 */ }
    override fun clear() { /* 无需 */ }
    override fun close() { /* 无需 */ }
}
