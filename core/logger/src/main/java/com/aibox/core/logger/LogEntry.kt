package com.aibox.core.logger

/**
 * 日志等级。
 *
 * 顺序：[VERBOSE] < [DEBUG] < [INFO] < [WARN] < [ERROR] < [CRASH]。
 *
 * CRASH 单独最高级别，仅用于未捕获异常和 native crash。其他场景禁止使用。
 */
public enum class LogLevel(public val severity: Int) {
    VERBOSE(0),
    DEBUG(1),
    INFO(2),
    WARN(3),
    ERROR(4),
    CRASH(5);

    public companion object {
        /** 通过最低等级过滤，避免在 release 里塞入 verbose 日志 */
        public fun fromSeverity(value: Int): LogLevel =
            entries.firstOrNull { it.severity == value } ?: INFO
    }
}

/** 日志来源模块：所有日志条目必须声明来自哪个模块，便于按模块过滤 */
public enum class LogModule {
    APP,
    PLUGIN_HOST,
    INFERENCE,
    NPU,
    DOWNLOAD,
    CHAT,
    IMAGE,
    AUDIO,
    PERSONA,
    LOGGER,
    SYSTEM,
    UNKNOWN,
}

/**
 * 单条日志条目。
 *
 * [timestamp] 是毫秒时间戳；[threadName] 便于多线程排查；
 * [extras] 是结构化字段（如 pluginId / modelPath / latencyMillis），便于应用内 UI 筛选。
 */
public data class LogEntry(
    public val id: Long = 0L,
    public val timestamp: Long,
    public val level: LogLevel,
    public val module: LogModule,
    public val tag: String,
    public val message: String,
    public val threadName: String,
    public val throwable: Throwable? = null,
    public val extras: Map<String, String> = emptyMap(),
)
