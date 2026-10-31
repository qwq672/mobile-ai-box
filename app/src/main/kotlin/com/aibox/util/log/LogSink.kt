package com.aibox.util.log

/**
 * 日志输出端接口。LogSystem 的日志会被同时分发到所有已注册的 sink。
 *
 * 实现可以是：
 * - RingBufferLogSink（内存环形缓冲，崩溃前同步刷盘）
 * - FileLogSink（按日期轮转的文件）
 * - CrashFileLogSink（崩溃独立文件）
 * - SessionLogSink（推理/基准测试会话独立日志）
 * - AndroidLogcatSink（logcat，便于 adb 抓取）
 * - UiStreamLogSink（UI 实时显示）
 *
 * 重要：实现必须保证线程安全，LogSystem 内部不加锁。
 */
interface LogSink {
    /** 处理一条日志。必须线程安全。 */
    fun append(entry: LogEntry)

    /** 紧急刷盘。崩溃前调用，必须同步完成且不能失败。 */
    fun emergencyFlush()

    /** 清空缓冲（不删文件）。 */
    fun clear()

    /** 释放资源。 */
    fun close()
}
