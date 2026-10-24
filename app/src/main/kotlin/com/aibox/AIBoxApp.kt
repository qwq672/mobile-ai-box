package com.aibox

import android.app.Application
import android.os.Build
import androidx.work.Configuration
import androidx.work.WorkManager
import com.aibox.di.AppContainer
import com.aibox.infra.crash.CrashHandler
import com.aibox.infra.crash.NativeCrashHandler
import com.aibox.infra.jni.NativeBridge
import com.aibox.util.log.LogLevel
import com.aibox.util.log.LogSystem

class AIBoxApp : Application(), Configuration.Provider {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        // ===================================================
        // Phase 1: 立即初始化日志系统（最先，不依赖任何其他模块）
        // ===================================================
        LogSystem.init(this)
        // 默认级别设置
        LogSystem.setRootLevel(LogLevel.DEBUG)
        LogSystem.setTagLevel("aibox.benchmark", LogLevel.DEBUG)
        LogSystem.setTagLevel("aibox.ui", LogLevel.INFO)        // UI 噪声降低
        LogSystem.setTagLevel("aibox.thermal", LogLevel.INFO)
        LogSystem.info(TAG, "AIBox Application onCreate starting")

        // ===================================================
        // Phase 2: 桥接 native 日志到 LogSystem
        // ===================================================
        try {
            NativeBridge.setLogHandler { level, tag, msg ->
                LogSystem.log(LogLevel.fromAndroid(level), tag, msg)
            }
        } catch (e: Throwable) {
            LogSystem.error(TAG, "Failed to install native log bridge", e)
        }

        // ===================================================
        // Phase 3: 安装崩溃捕获器（在 super.onCreate 之前，确保后续崩溃都能捕获）
        // ===================================================
        installCrashHandlers()

        super.onCreate()
        instance = this
        container = AppContainer(this)

        // ===================================================
        // Phase 4: 初始化 WorkManager
        // ===================================================
        runCatching { WorkManager.initialize(this, workManagerConfiguration) }
            .onFailure { LogSystem.error(TAG, "WorkManager init failed", it) }

        // ===================================================
        // Phase 5: 启动优化管理器（P3）
        // 非阻塞：内部把所有工作丢到自己的 CoroutineScope 里执行
        // （拉初始设置 + 订阅设置变化 + 订阅热档位 + 启动 idle watchdog + 异步预加载模型）
        // ===================================================
        runCatching { container.optimizationManager.onAppStart() }
            .onFailure { LogSystem.error(TAG, "OptimizationManager onAppStart failed", it) }

        // ===================================================
        // Phase 6: 注册内置工具（B1 工具调用抽象层）
        // 当前为占位（B2-B5 / C1-C4 实现后取消注释注册逻辑）；
        // ToolSettingsRepository 的默认启用列表已写入 DataStore，
        // 等具体工具实现并 register 后即可被 ToolRegistry.getEnabledTools 命中。
        // ===================================================
        runCatching { container.toolBootstrap.registerBuiltinTools() }
            .onFailure { LogSystem.error(TAG, "ToolBootstrap registerBuiltinTools failed", it) }

        LogSystem.info(TAG, "AIBox Application onCreate completed, version=${getAppVersion()}")
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    /**
     * 安装两层崩溃捕获：
     * 1. CrashHandler（Java/Kotlin 异常）- 同步刷盘 + 独立崩溃文件
     * 2. NativeCrashHandler（native 信号 SIGSEGV/SIGABRT 等）
     *
     * 两者结合保证崩溃前日志完整保留，闪退也能拿到完整日志。
     */
    private fun installCrashHandlers() {
        try {
            // 1. Java/Kotlin 崩溃捕获
            val crashHandler = CrashHandler(
                appVersion = getAppVersion(),
                buildInfo = "${BuildConfig.VERSION_NAME}-${BuildConfig.VERSION_CODE} (${BuildConfig.BUILD_TYPE})",
                loadedModelsProvider = {
                    runCatching {
                        if (::container.isInitialized) {
                            kotlinx.coroutines.runBlocking { container.modelRepository.listLoaded() }.map { it.name }
                        } else emptyList()
                    }.getOrDefault(emptyList())
                },
                activeBackendsProvider = {
                    runCatching {
                        if (::container.isInitialized) container.backendManager.listActive()
                        else emptyList()
                    }.getOrDefault(emptyList())
                },
                loadedPluginsProvider = {
                    runCatching {
                        if (::container.isInitialized) container.pluginManager.listActive()
                        else emptyList()
                    }.getOrDefault(emptyList())
                }
            )
            crashHandler.install()

            // 2. Native 崩溃捕获
            val crashDir = cacheDir.absolutePath + "/native_crashes"
            NativeCrashHandler.install(crashDir)

            LogSystem.info(TAG, "Crash handlers installed (java + native)")
        } catch (e: Throwable) {
            // 崩溃处理器自身崩溃不能阻塞
            LogSystem.error(TAG, "Failed to install crash handlers", e)
        }
    }

    private fun getAppVersion(): String {
        return try {
            val pkgInfo = packageManager.getPackageInfo(packageName, 0)
            "${pkgInfo.versionName} (${pkgInfo.longVersionCode})"
        } catch (e: Throwable) {
            "unknown"
        }
    }

    companion object {
        @Volatile lateinit var instance: AIBoxApp
            private set

        private const val TAG = "aibox.app"
    }
}
