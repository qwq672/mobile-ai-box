package com.pocketllm.util

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 热感知监控。
 *
 * 数据来源（按优先级，取先拿到的）：
 * 1. /sys/class/thermal/thermal_zoneN/temp（sysfs，分辨率高，需扫描所有 zone 找 CPU/SKIN）
 * 2. PowerManager.currentThermalStatus（API 29+，离散等级 0-6）
 *
 * 如果都拿不到，返回 -1，UI 显示 "热度 —" 而不是误导性的 "热度 0%"。
 */
class ThermalMonitor {

    /** -1 = 未读到，0..100 = 热档位 */
    private val _thermalPercent = MutableStateFlow(-1)
    val thermalPercent: StateFlow<Int> = _thermalPercent.asStateFlow()

    private var job: Job? = null
    private var sysfsPaths: List<String> = emptyList()
    @Volatile private var pm: PowerManager? = null

    fun start() {
        if (job?.isActive == true) return
        sysfsPaths = findAllTempZones()
        pm = (appContext?.getSystemService(Context.POWER_SERVICE) as? PowerManager)
        logi("ThermalMonitor start: sysfs_zones=${sysfsPaths.size} pm=${if (pm != null) "yes" else "no"}")
        job = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                _thermalPercent.value = readPercent()
                delay(2000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun thermalPercent(): Int = _thermalPercent.value

    private fun readPercent(): Int {
        // 1. sysfs（分辨率高，优先）
        for (path in sysfsPaths) {
            try {
                val raw = File(path).readText().trim()
                if (raw.isEmpty() || raw == "0") continue
                val milli = raw.toLong()
                if (milli <= 0) continue
                val celsius = milli / 1000.0
                // 35°C=0%, 65°C=100%（手机 CPU 满载通常到 60-70°C）
                val pct = ((celsius - 35) * 100 / 30).toInt().coerceIn(0, 100)
                if (pct >= 0) return pct
            } catch (_: Exception) { /* try next */ }
        }

        // 2. PowerManager.currentThermalStatus (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = this.pm ?: return -1
            return try {
                val status = pm.currentThermalStatus
                // THERMAL_STATUS_NONE=0, LIGHT=1, MODERATE=2, SEVERE=3, CRITICAL=4, EMERGENCY=5, SHUTDOWN=6
                (status * 100 / 6).coerceIn(0, 100)
            } catch (_: Exception) { -1 }
        }

        return -1
    }

    /**
     * 扫描所有 thermal_zone，按温度从高到低排序。
     * 优先用 CPU zone（推理主要热源），其次 SKIN（手机表面温度）。
     */
    private fun findAllTempZones(): List<String> {
        val base = File("/sys/class/thermal")
        if (!base.exists()) return emptyList()
        val zones = base.listFiles { f -> f.isDirectory && f.name.startsWith("thermal_zone") }
            ?: return emptyList()
        return zones.mapNotNull { zone ->
            try {
                val tempFile = File(zone, "temp")
                val typeFile = File(zone, "type")
                if (!tempFile.exists()) return@mapNotNull null
                val temp = tempFile.readText().trim().toLongOrNull() ?: return@mapNotNull null
                if (temp <= 0) return@mapNotNull null
                val type = if (typeFile.exists()) typeFile.readText().trim().lowercase() else ""
                Triple(tempFile.absolutePath, type, temp)
            } catch (_: Exception) { null }
        }.sortedByDescending { it.third }.map { it.first }
    }

    companion object {
        @Volatile var appContext: Context? = null
    }
}

