package com.pocketllm.util

import android.os.Build
import java.io.File

/**
 * CPU 信息工具。
 *
 * - 大核数：用于自动选择 CPU 线程数（不抢小核，避免系统卡顿与功耗浪费）
 * - 总核数：用于上限
 *
 * Android 没有公开 API 直接拿大核数，这里通过 /sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq
 * 拿到每个核的最高频率，超过 1.8GHz 视为大核。
 */
object CpuInfo {

    val totalCores: Int by lazy { Runtime.getRuntime().availableProcessors().coerceAtLeast(1) }

    val bigCores: Int by lazy {
        var count = 0
        for (i in 0 until totalCores) {
            val f = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            try {
                if (f.exists()) {
                    val khz = f.readText().trim().toLong()
                    if (khz >= 1_800_000) count++
                }
            } catch (_: Exception) { /* ignore */ }
        }
        // 兜底：如果没读到，按总核数一半
        if (count == 0) (totalCores / 2).coerceAtLeast(2) else count
    }

    /** SoC 名称，用于 UI 显示 */
    val socName: String by lazy {
        try {
            val hardware = File("/proc/cpuinfo").useLines { seq ->
                seq.firstOrNull { it.startsWith("Hardware") }?.split(":")?.lastOrNull()?.trim()
            }
            hardware ?: Build.HARDWARE
        } catch (_: Exception) {
            Build.HARDWARE
        }
    }
}
