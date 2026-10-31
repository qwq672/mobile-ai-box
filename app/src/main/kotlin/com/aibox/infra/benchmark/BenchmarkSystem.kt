package com.aibox.infra.benchmark

import android.content.Context
import com.aibox.domain.inference.BackendManagerV2
import com.aibox.util.ThermalMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 基准测试系统统一入口（P2 facade）。
 *
 * 把 [EnvironmentMonitor] / [EnergyProfiler] / [BasicBenchmark] / [AdvancedBenchmark] /
 * [BackendComparison] / [BenchmarkStorage] 打包到一个对象，供 [com.aibox.ui.benchmark.BenchmarkViewModel]
 * 通过 [com.aibox.di.AppContainer] 一站式访问。
 *
 * 不直接持有 [BackendManagerV2]：调用方传入，便于测试时替换为 mock。
 *
 * 用法：
 * ```
 * val system = BenchmarkSystem(appContext, thermalMonitor = container.thermalMonitor)
 * system.basicBenchmark.run(backend, loaded, spec)
 * system.backendComparison.compareBackends(modelPath, "gguf", backends = listOf("llamaCpp"))
 * system.storage.observeAll().collect { ... }
 * ```
 *
 * @param context 应用上下文
 * @param thermalMonitor 复用宿主的 [ThermalMonitor]（避免重复启动后台线程）
 * @param backendManager 已注册的后端管理器（用于 [BackendComparison]）
 */
class BenchmarkSystem(
    context: Context,
    thermalMonitor: ThermalMonitor?,
    backendManager: BackendManagerV2
) {

    val environmentMonitor: EnvironmentMonitor = EnvironmentMonitor(
        context = context.applicationContext,
        thermalMonitor = thermalMonitor
    )

    val energyProfiler: EnergyProfiler = EnergyProfiler(context.applicationContext).also { it.init() }

    val basicBenchmark: BasicBenchmark = BasicBenchmark(
        environmentMonitor = environmentMonitor,
        energyProfiler = energyProfiler
    )

    /** AdvancedBenchmark 是 object（无状态），直接通过类访问；这里 alias 暴露给 ViewModel 用。 */
    val advancedBenchmark: AdvancedBenchmark = AdvancedBenchmark

    val backendComparison: BackendComparison = BackendComparison(
        backendManager = backendManager,
        basicBenchmark = basicBenchmark,
        advancedBenchmark = advancedBenchmark
    )

    val storage: BenchmarkStorage = BenchmarkStorage(context.applicationContext)

    /** 当前运行的状态（idle / running matrix / running comparison）；UI 用于显示 spinner 与禁用按钮。 */
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** 设置 / 释放 running 状态；调用方在跑矩阵 / 对比前 set true，结束后 set false。 */
    fun setRunning(value: Boolean) {
        _running.value = value
    }

    /** 顺手记录一次设备 baseline，写入 logcat + 日志文件；调用方可在首次启动 benchmark 页时调用。 */
    fun logBaselineOnce() {
        environmentMonitor.logBaselineOnce()
    }
}
