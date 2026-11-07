package com.pocketllm

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import com.pocketllm.di.AppContainer
import com.pocketllm.infra.jni.NativeBridge
import com.pocketllm.util.AppLogger
import com.pocketllm.util.loge
import com.pocketllm.util.logi

class PocketLLMApp : Application(), Configuration.Provider {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        // 先初始化日志（写到 /sdcard/Android/data/com.pocketllm/files/pocketllm.log）
        AppLogger.init(this)
        logi("PocketLLM Application onCreate")

        // 把 native 侧的 LOGI/LOGE 通过 NativeBridge.onNativeLog 静态方法桥到 AppLogger，
        // 让用户在 pocketllm.log 中也能看到 C++ 端的日志（无需 adb logcat）。
        NativeBridge.setLogHandler { level, tag, msg ->
            // level 值：ANDROID_LOG_VERBOSE=2, DEBUG=3, INFO=4, WARN=5, ERROR=6
            AppLogger.log(level, tag, msg)
        }

        installCrashLogger()
        super.onCreate()
        instance = this
        container = AppContainer(this)

        // 初始化 WorkManager（保留用于后续可能的真正后台下载实现）
        runCatching { WorkManager.initialize(this, workManagerConfiguration) }
            .onFailure { loge("WorkManager init failed", it) }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    /** 未捕获异常 → 文件 + logcat，便于用户反馈时定位 */
    private fun installCrashLogger() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val trace = android.util.Log.getStackTraceString(e)
                val msg = "=== crash on ${java.util.Date()} thread=${t.name} ===\n$trace\n"
                AppLogger.log(android.util.Log.ERROR, "Crash", msg, e)
                openFileOutput("crash.log", MODE_APPEND).bufferedWriter().use { it.write(msg + "\n") }
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    companion object {
        @Volatile lateinit var instance: PocketLLMApp
            private set
    }
}


