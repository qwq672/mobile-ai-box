package com.aibox.infra.startup

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * 启动诊断与安全模式。
 *
 * 背景：曾出现「启动后长时间进不了主页、机身发热、系统提示未响应，重装也复现」。
 * 这类问题通常不是单一异常，而是启动阶段某个后台任务持续占 CPU + 主线程被初始化阻塞。
 *
 * 本对象做四件事：
 * 1. 记录启动各阶段耗时（相对进程启动 / init 之后）；
 * 2. 主线程 watchdog：超过 8 秒没有首帧就写日志并标记本次启动失败；
 * 3. 下次启动检测上次是否 ANR / native crash，若是则进入安全模式（跳过预加载、插件同步、云端恢复）；
 * 4. 把上一次异常退出的 ANR trace 落盘，方便事后定位。
 *
 * 不依赖 DataStore / Room，避免诊断本身被待诊断的组件拖住。
 */
object StartupGuard {

    private const val TAG = "aibox.startup"
    private const val PREFS = "aibox_startup"
    private const val KEY_LAST_START = "last_start_at"
    private const val KEY_FAILED = "startup_failed"

    /** 超过该时间仍未首帧，判定启动异常。 */
    private const val WATCHDOG_MS = 8_000L

    /** 只看最近 15 分钟内的异常退出，避免很久以前的 ANR 永久触发安全模式。 */
    private const val RECENT_MS = 15 * 60 * 1000L

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var safeMode: Boolean = false
        private set

    @Volatile
    private var firstFrameReported = false

    private var initUptime = 0L
    private var appContext: Context? = null
    @Volatile private var watchdogStarted = false

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        initUptime = SystemClock.uptimeMillis()

        logMemory(app, "init")

        val sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prevStart = sp.getLong(KEY_LAST_START, 0L)
        val prevFailed = sp.getBoolean(KEY_FAILED, false)
        val badExit = findRecentBadExit(app, prevStart)
        safeMode = prevFailed || badExit != null

        // 先记录本次启动时间；startup_failed 保留到首帧成功后再清，
        // 这样本次即使再次卡死，下次仍能进入安全模式。
        sp.edit().putLong(KEY_LAST_START, System.currentTimeMillis()).apply()

        LogSystem.info(
            TAG,
            "init: sinceProcessStart=${sinceProcessStartMs()}ms safeMode=$safeMode " +
                "prevFailed=$prevFailed prevStart=$prevStart badExit=${badExit?.reasonName()}"
        )
        if (badExit != null) dumpExitInfo(app, badExit)
    }

    /** 记录一个启动阶段；detail 可放额外上下文，避免日志只有时间没有信息。 */
    fun phase(name: String, detail: String = "") {
        val sinceInit = if (initUptime > 0L) SystemClock.uptimeMillis() - initUptime else 0L
        val suffix = if (detail.isBlank()) "" else " detail=$detail"
        LogSystem.info(
            TAG,
            "phase=$name sinceInit=${sinceInit}ms sinceProcessStart=${sinceProcessStartMs()}ms$suffix"
        )
    }

    /** 后台 watchdog：启动后 8 秒仍无首帧，写日志 + 标记失败，供下次安全模式使用。 */
    fun startWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        Thread({
            try {
                Thread.sleep(WATCHDOG_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (firstFrameReported) return@Thread
            val app = appContext
            LogSystem.error(
                TAG,
                "watchdog: no first frame after ${WATCHDOG_MS}ms " +
                    "(sinceProcessStart=${sinceProcessStartMs()}ms, safeMode=$safeMode). " +
                    "下一次启动将进入安全模式（跳过预加载/插件同步/云端恢复）。"
            )
            if (app != null) {
                logMemory(app, "watchdog")
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_FAILED, true).apply()
            }
            LogSystem.emergencyFlushAll()
        }, "aibox-startup-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    /** MainActivity 首帧回调：记录总耗时并清除失败标记。 */
    fun onFirstFrame(source: String) {
        if (firstFrameReported) return
        firstFrameReported = true

        val sinceInit = if (initUptime > 0L) SystemClock.uptimeMillis() - initUptime else 0L
        LogSystem.info(
            TAG,
            "first-frame source=$source sinceInit=${sinceInit}ms " +
                "sinceProcessStart=${sinceProcessStartMs()}ms safeMode=$safeMode"
        )

        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_FAILED, false)?.apply()
    }

    private fun sinceProcessStartMs(): Long = runCatching {
        SystemClock.uptimeMillis() - Process.getStartUptimeMillis()
    }.getOrDefault(0L)

    private fun logMemory(context: Context, stage: String) {
        runCatching {
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val mb = 1024L * 1024L
            LogSystem.info(
                TAG,
                "memory@$stage: total=${mi.totalMem / mb}MB avail=${mi.availMem / mb}MB " +
                    "low=${mi.lowMemory} threshold=${mi.threshold / mb}MB"
            )
        }
    }

    private fun findRecentBadExit(context: Context, prevStart: Long): ApplicationExitInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val am = context.getSystemService(ActivityManager::class.java) ?: return null
            val now = System.currentTimeMillis()
            am.getHistoricalProcessExitReasons(context.packageName, 0, 8)
                .firstOrNull { info ->
                    val recent = info.timestamp >= now - RECENT_MS
                    val afterPrevStart = prevStart <= 0L || info.timestamp >= prevStart - 5_000L
                    recent && afterPrevStart && info.reason.isBadStartupExit()
                }
        }.getOrNull()
    }

    private fun dumpExitInfo(context: Context, info: ApplicationExitInfo) {
        ioScope.launch {
            runCatching {
                val sb = StringBuilder()
                sb.appendLine("timestamp=${info.timestamp}")
                sb.appendLine("reason=${info.reasonName()}")
                sb.appendLine("description=${info.description}")
                sb.appendLine("importance=${info.importance} pss=${info.pss} rss=${info.rss}")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val trace = info.traceInputStream?.bufferedReader()?.use { it.readText() }
                    if (!trace.isNullOrBlank()) {
                        sb.appendLine("---- trace ----")
                        sb.appendLine(trace.take(60_000))
                    }
                }
                val file = File(context.filesDir, "startup_last_abnormal_exit.txt")
                file.writeText(sb.toString())
                LogSystem.error(
                    TAG,
                    "上一次异常退出已落盘：${file.absolutePath}\n${sb.take(4_000)}"
                )
            }
        }
    }

    private fun Int.isBadStartupExit(): Boolean = when (this) {
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> true
        else -> false
    }

    private fun ApplicationExitInfo.reasonName(): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        else -> "OTHER($reason)"
    }
}
