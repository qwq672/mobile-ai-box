package com.aibox.util.log

/**
 * 日志级别，与 Android Log 对齐，但可独立扩展。
 *
 * - 用于 [LogSystem] 的统一日志接口
 * - 顺序与 android.util.Log.* 一致，方便互转
 */
enum class LogLevel(val priority: Int, val label: String) {
    VERBOSE(2, "V"),
    DEBUG(3, "D"),
    INFO(4, "I"),
    WARN(5, "W"),
    ERROR(6, "E"),
    FATAL(7, "F");  // FATAL: 仅崩溃时使用

    fun toAndroidPriority(): Int = priority

    companion object {
        fun fromAndroid(priority: Int): LogLevel = when (priority) {
            2 -> VERBOSE
            3 -> DEBUG
            4 -> INFO
            5 -> WARN
            6 -> ERROR
            7 -> FATAL
            else -> INFO
        }
    }
}
