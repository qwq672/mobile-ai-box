package com.aibox.infra.benchmark

/**
 * 基准测试结果（Layer 1 基础基准的输出）。
 *
 * 由 [BasicBenchmark.run] 在跑完 `warmup + repCount` 次推理后取中位数得到。
 * 所有性能指标都是"中位数"而非"平均"——更鲁棒，能滤掉偶发的 GC 暂停 / 系统抖动。
 *
 * @property spec 触发本次测试的 [BenchmarkSpec]（含 modelPath / config / ppSize / tgSize / repCount）。
 * @property ppTps 提示词处理速度（prompt tokens per second），越高表示 prompt 编码越快。
 * @property tgTps 生成速度（tokens per second），越高表示 token 逐个 decode 越快。
 * @property ttftMs 首 token 延迟（time to first token, 毫秒），主要包含 prompt 处理 + KV cache 分配 + 首 decode。
 * @property totalTimeMs 整次推理总耗时（毫秒），从开始到 done chunk。
 * @property peakMemoryBytes 峰值内存占用（字节）；来自 [EnvironmentMonitor] 或 native 端读数。
 * @property energyMilliJoules 能耗（毫焦耳），如设备 / 系统不支持读取则为 null。
 * @property environment 测试期间的设备环境快照（SoC / 热度 / 电量 / RAM 等）。
 * @property logFilePath 关联的会话独立日志路径（[com.aibox.util.log.LogSystem.startSession] 创建的 sessionId 对应文件）；可空。
 * @property timestamp 结果产生的时间戳（[System.currentTimeMillis]）。
 * @property failed 是否失败；true 表示本次 spec 无法跑出有效结果，[errorMessage] 描述原因。
 * @property errorMessage 失败原因（[failed] = true 时非空）。
 */
data class BenchmarkResult(
    val spec: BenchmarkSpec,
    val ppTps: Double,
    val tgTps: Double,
    val ttftMs: Long,
    val totalTimeMs: Long,
    val peakMemoryBytes: Long,
    val energyMilliJoules: Long?,
    val environment: EnvironmentSnapshot,
    val logFilePath: String?,
    val timestamp: Long = System.currentTimeMillis(),
    val failed: Boolean = false,
    val errorMessage: String? = null
) {

    companion object {

        /**
         * 构造一份"失败"的 [BenchmarkResult]，便于 [BackendComparison] 等上层在 catch 块里统一返回。
         *
         * 所有数值字段填 0 / null，[failed] = true，[errorMessage] = [reason]。
         */
        fun failed(spec: BenchmarkSpec, reason: String): BenchmarkResult = BenchmarkResult(
            spec = spec,
            ppTps = 0.0,
            tgTps = 0.0,
            ttftMs = 0L,
            totalTimeMs = 0L,
            peakMemoryBytes = 0L,
            energyMilliJoules = null,
            environment = EnvironmentSnapshot.unknown(),
            logFilePath = null,
            failed = true,
            errorMessage = reason
        )
    }
}

/**
 * 设备环境快照：在每次基准测试前后各拍一份，用于检测"环境漂移"对结果的影响。
 *
 * @property deviceSoC SoC 型号字符串（来自 [com.aibox.util.CpuInfo.socName]）。
 * @property cpuFreqCurrent 当前 CPU 主频（kHz；取所有核心的最大值，代表当前最高运行档位）。
 * @property thermalState 当前热档位（COOL / WARM / HOT / CRITICAL）；HOT/CRITICAL 时结果不可信。
 * @property backgroundCpuUsage 后台 CPU 占用百分比（0-100），采样自 /proc/stat。
 * @property chargingState 是否在充电。
 * @property batteryLevel 电池电量百分比（0-100）；-1 表示读不到。
 * @property totalRamMb 设备总 RAM（MB）。
 * @property freeRamMb 当前可用 RAM（MB）。
 */
data class EnvironmentSnapshot(
    val deviceSoC: String,
    val cpuFreqCurrent: Long,
    val thermalState: ThermalState,
    val backgroundCpuUsage: Int,
    val chargingState: Boolean,
    val batteryLevel: Int,
    val totalRamMb: Int,
    val freeRamMb: Int
) {

    companion object {

        /** "未知"环境快照，用于失败兜底。 */
        fun unknown(): EnvironmentSnapshot = EnvironmentSnapshot(
            deviceSoC = "unknown",
            cpuFreqCurrent = 0L,
            thermalState = ThermalState.COOL,
            backgroundCpuUsage = 0,
            chargingState = false,
            batteryLevel = -1,
            totalRamMb = 0,
            freeRamMb = 0
        )
    }
}

/**
 * 热档位枚举（与 [android.os.PowerManager.THERMAL_STATUS_*] 离散等级对齐，但简化为 4 档便于 UI 渲染）。
 *
 * - COOL：热档位 0-1（NONE / LIGHT），可正常跑长测。
 * - WARM：热档位 2（MODERATE），可继续跑，但需关注降频。
 * - HOT：热档位 3（SEVERE），开始明显降频；基准结果需注明。
 * - CRITICAL：热档位 4+（CRITICAL / EMERGENCY / SHUTDOWN），不可信结果，应中止重跑。
 */
enum class ThermalState { COOL, WARM, HOT, CRITICAL }
