package com.aibox.util

import android.content.Context
import com.aibox.util.log.LogLevel
import com.aibox.util.log.LogSystem

/**
 * 应用级日志器（已废弃，转发到 [LogSystem]）。
 *
 * 新代码请直接使用 [LogSystem]，例如：
 *   LogSystem.info("aibox.app", "msg")
 *   LogSystem.error("aibox.ui.chat", "msg", e)
 *
 * 本对象仅作为向后兼容层保留，所有方法均转发到 [LogSystem]。
 *
 * @see LogSystem
 */
@Deprecated("Use LogSystem instead")
object AppLogger {

    /**
     * 转发到 [LogSystem.init]。新代码应在 Application 启动时直接调用 [LogSystem.init]。
     */
    fun init(context: Context) {
        LogSystem.init(context)
    }

    /**
     * 通用日志入口，转发到 [LogSystem.log]。
     *
     * @param priority  Android Log 级别常量（[android.util.Log.INFO] 等），通过 [LogLevel.fromAndroid] 转换
     * @param tag       日志标签，默认 "AIBox"（旧值，保留兼容）
     * @param msg       消息正文
     * @param t         关联异常（可选）
     */
    fun log(priority: Int, tag: String = "AIBox", msg: String, t: Throwable? = null) {
        LogSystem.log(LogLevel.fromAndroid(priority), tag, msg, t)
    }

    /**
     * 旧 API：开关日志。新 [LogSystem] 通过 [LogSystem.setRootLevel] 控制，本方法为 no-op。
     */
    @Suppress("UNUSED_PARAMETER")
    fun setEnabled(on: Boolean, silent: Boolean = false) {
        // no-op，新 LogSystem 通过 setRootLevel 控制级别
    }

    /** 旧 API：返回日志是否启用。新 [LogSystem] 始终启用（用级别控制），返回 true。 */
    fun isEnabled(): Boolean = true

    /** 返回 ring buffer 快照（按行格式化），供 UI 显示。 */
    fun snapshot(): List<String> = LogSystem.snapshot(1000).map { it.formatOneLine() }

    /** 返回日志目录路径（用于设置页"打开"提示）。 */
    fun filePath(): String? = LogSystem.getLogDirPath()

    /** 清空所有日志文件。 */
    fun clear() {
        LogSystem.clearAll()
    }
}

/* ============ Kotlin 便捷封装（已废弃，转发到 LogSystem） ============ */

/** 旧 [logi] 兼容封装。新代码请使用 [LogSystem.info]。 */
@Deprecated("Use LogSystem.info instead")
fun logi(msg: String, tag: String = "AIBox") = LogSystem.info(tag, msg)

/** 旧 [logw] 兼容封装。新代码请使用 [LogSystem.warn]。 */
@Deprecated("Use LogSystem.warn instead")
fun logw(msg: String, t: Throwable? = null, tag: String = "AIBox") = LogSystem.warn(tag, msg, t)

/** 旧 [loge] 兼容封装。新代码请使用 [LogSystem.error]。 */
@Deprecated("Use LogSystem.error instead")
fun loge(msg: String, t: Throwable? = null, tag: String = "AIBox") = LogSystem.error(tag, msg, t)
