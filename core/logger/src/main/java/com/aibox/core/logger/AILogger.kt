package com.aibox.core.logger

import kotlinx.coroutines.flow.Flow

/**
 * 统一日志 API。
 *
 * **这是整个 AIBox 唯一允许的日志入口**。
 * 禁止任何模块直接 import android.util.Log 或 Timber。
 *
 * 反屎山理由：
 * - 不同模块乱用 Log.e / Log.d 会导致日志格式不统一、排查时找不齐
 * - 集中 API 才能保证"双轨写入"（Room DB + 外部文件）的原子性
 *
 * 接口而非实现：便于测试 mock，也便于插件在宿主外测试日志功能。
 */
public interface AILogger {

    /** 同步推一条日志，立即返回 */
    public fun log(entry: LogEntry)

    /** 协程日志：高级用法，等价于 log() */
    public suspend fun logSuspend(entry: LogEntry)

    /** 实时日志流：UI 监听此 Flow 即可在应用内看到滚动日志 */
    public fun stream(): Flow<LogEntry>

    /** 查询历史日志（应用内浏览页用） */
    public suspend fun query(filter: LogFilter): List<LogEntry>

    /** 查询历史日志流（分页加载用） */
    public fun queryFlow(filter: LogFilter, pageSize: Int = 50): Flow<List<LogEntry>>

    /** 立即把缓冲区刷到磁盘（重要操作前调用） */
    public suspend fun flush()

    /** 清理 N 天前的日志（按需调用，由 WorkManager 调度） */
    public suspend fun purge(olderThanDays: Int): Int

    public companion object
}

/** 便捷构造工具，避免 [LogEntry] 字段在每个调用点重复写 */
public class LogBuilder {
    public var level: LogLevel = LogLevel.INFO
    public var module: LogModule = LogModule.UNKNOWN
    public var tag: String = ""
    public var message: String = ""
    public var throwable: Throwable? = null
    public var extras: MutableMap<String, String> = mutableMapOf()

    public fun extra(key: String, value: Any?): LogBuilder = apply {
        if (value != null) extras[key] = value.toString()
    }

    public fun build(): LogEntry = LogEntry(
        timestamp = System.currentTimeMillis(),
        level = level,
        module = module,
        tag = tag,
        message = message,
        threadName = Thread.currentThread().name,
        throwable = throwable,
        extras = extras,
    )
}

/** 查询过滤器：UI 的"按等级 / 按模块 / 按关键词 / 按时间"筛选都走这里 */
public data class LogFilter(
    public val minLevel: LogLevel = LogLevel.VERBOSE,
    public val modules: Set<LogModule> = emptySet(),
    public val tagContains: String? = null,
    public val messageContains: String? = null,
    public val sinceMillis: Long? = null,
    public val untilMillis: Long? = null,
    public val limit: Int = 500,
)

/** 便捷扩展函数：让 [AILogger] 用起来像传统 Log.x */
public inline fun AILogger.verbose(
    module: LogModule,
    tag: String,
    message: String,
    extras: Map<String, String> = emptyMap(),
) {
    log(LogBuilder().apply {
        this.level = LogLevel.VERBOSE
        this.module = module
        this.tag = tag
        this.message = message
        this.extras.putAll(extras)
    }.build())
}

public inline fun AILogger.debug(module: LogModule, tag: String, message: String, extras: Map<String, String> = emptyMap()) {
    log(LogBuilder().apply {
        this.level = LogLevel.DEBUG
        this.module = module
        this.tag = tag
        this.message = message
        this.extras.putAll(extras)
    }.build())
}

public inline fun AILogger.info(module: LogModule, tag: String, message: String, extras: Map<String, String> = emptyMap()) {
    log(LogBuilder().apply {
        this.level = LogLevel.INFO
        this.module = module
        this.tag = tag
        this.message = message
        this.extras.putAll(extras)
    }.build())
}

public inline fun AILogger.warn(module: LogModule, tag: String, message: String, extras: Map<String, String> = emptyMap()) {
    log(LogBuilder().apply {
        this.level = LogLevel.WARN
        this.module = module
        this.tag = tag
        this.message = message
        this.extras.putAll(extras)
    }.build())
}

public inline fun AILogger.error(
    module: LogModule,
    tag: String,
    message: String,
    throwable: Throwable? = null,
    extras: Map<String, String> = emptyMap(),
) {
    log(LogBuilder().apply {
        this.level = LogLevel.ERROR
        this.module = module
        this.tag = tag
        this.message = message
        this.throwable = throwable
        this.extras.putAll(extras)
    }.build())
}

public inline fun AILogger.crash(
    module: LogModule,
    tag: String,
    message: String,
    throwable: Throwable? = null,
    extras: Map<String, String> = emptyMap(),
) {
    log(LogBuilder().apply {
        this.level = LogLevel.CRASH
        this.module = module
        this.tag = tag
        this.message = message
        this.throwable = throwable
        this.extras.putAll(extras)
    }.build())
}
