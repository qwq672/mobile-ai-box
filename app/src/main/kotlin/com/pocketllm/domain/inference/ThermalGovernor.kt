package com.pocketllm.domain.inference

import com.pocketllm.util.ThermalMonitor

/**
 * 热感知调度器。
 *
 * 设计哲学（与"强制提示用户降温"相反）：
 * 1. 实时读取 [ThermalMonitor.thermalPercent]（基于 PowerManager / sysfs）。
 * 2. 温度越高，越主动降档 —— 但降的是「内存调用强度」而非「直接打断推理」：
 *    - 75% 以下：原始配置
 *    - 75~85%：cpu_threads 减半、batch 减半（少 malloc、少 DDR 带宽）
 *    - 85~95%：再降 KV 量化到 q4_0（带宽再降一半）
 *    - 95%+：切回 CPU 后端（GPU/NPU 高速运行时是发热大头）
 * 3. antiRollback=true 时本调度器所有降级被禁用（用户自担风险）。
 *
 * 这样在不打断对话的前提下，让手机自然降温，且首 token 延迟变化很小。
 */
class ThermalGovernor(private val monitor: ThermalMonitor) {

    /**
     * 在 load 阶段调用：根据当前温度微调 config。
     * 返回的可能与传入相同（温度正常时）。
     */
    fun tuneConfig(config: InferenceConfig): InferenceConfig {
        if (config.antiRollback) return config  // 用户选择"防回退"，跳过降级

        val t = monitor.thermalPercent()
        var tuned = config

        if (t >= 95) {
            // 极热：回退到 CPU + 最小参数
            tuned = tuned.copy(
                backend = BackendType.CPU,
                cpuThreads = (tuned.cpuThreads.coerceAtLeast(2)) / 2,
                physicalBatch = (tuned.physicalBatch / 2).coerceAtLeast(128),
                batch = (tuned.batch / 2).coerceAtLeast(512),
                kvQuant = "q4_0"
            )
        } else if (t >= 85) {
            tuned = tuned.copy(
                kvQuant = "q4_0",
                physicalBatch = (tuned.physicalBatch * 2 / 3).coerceAtLeast(256)
            )
        } else if (t >= 75) {
            tuned = tuned.copy(
                cpuThreads = (tuned.cpuThreads.coerceAtLeast(2)) / 2,
                batch = (tuned.batch * 2 / 3).coerceAtLeast(1024)
            )
        }
        return tuned
    }

    /**
     * 在每次 completion 前调用。
     * 极热情况下直接 interrupt 等下一轮，让温度下降 1~2 度再继续 —— 这才是
     * 真正的"防回退"对立面：主动慢一拍，避免被系统 SoC throttling 强行打回 0。
     */
    fun beforeCompletion(backend: Backend) {
        val t = monitor.thermalPercent()
        if (t >= 95) {
            // 极热时小睡 200ms 让 SoC 降温，再继续 —— 比被打回 CPU 重算快得多
            try { Thread.sleep(200) } catch (_: InterruptedException) {}
        }
    }
}
