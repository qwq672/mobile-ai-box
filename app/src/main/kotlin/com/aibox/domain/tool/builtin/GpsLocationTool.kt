package com.aibox.domain.tool.builtin

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.aibox.domain.tool.ParamSpec
import com.aibox.domain.tool.ParamType
import com.aibox.domain.tool.Tool
import com.aibox.domain.tool.ToolCategory
import com.aibox.domain.tool.ToolContext
import com.aibox.domain.tool.ToolOptionSpec
import com.aibox.domain.tool.ToolOptionType
import com.aibox.domain.tool.ToolParameterSchema
import com.aibox.domain.tool.ToolResult
import com.aibox.domain.tool.ToolSettingsRepository
import com.aibox.util.log.LogSystem
import com.aibox.util.outcome.Outcome
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * GPS 定位工具（高级工具，需确认；可加入自动确认白名单）。
 *
 * 设计取舍：
 * - **只做单次定位**，不做持续跟踪：持续 GPS 在低电量下是耗电大户
 * - 先读 last known，够新就直接用；否则发起一次定位并带超时
 * - 默认粗略定位（网络），用户可在工具选项里打开「精确位置」
 * - 同时输出 WGS84 与 GCJ-02（国内地图），避免坐标对不上
 */
class GpsLocationTool(
    private val context: Context,
    private val toolSettingsRepository: ToolSettingsRepository
) : Tool {
    override val id: String = "gps_location"
    override val displayName: String = "GPS 定位"
    override val description: String =
        "获取设备当前位置（单次定位），返回经纬度、精度与坐标系。室内可能失败。"
    override val category: ToolCategory = ToolCategory.ADVANCED
    override val icon: String = "📍"

    override val options: List<ToolOptionSpec> = listOf(
        ToolOptionSpec(
            key = "precise",
            title = "精确位置",
            description = "开启后使用 GPS 高精度定位，更准但更耗电；关闭时只使用网络粗略位置",
            type = ToolOptionType.BOOLEAN,
            defaultValue = "false"
        ),
        ToolOptionSpec(
            key = "timeout_seconds",
            title = "定位超时（秒）",
            description = "超时后返回最近一次已知位置或失败，避免一直等待",
            type = ToolOptionType.INTEGER,
            defaultValue = "8",
            min = 3,
            max = 20
        )
    )

    override fun getParameterSchema(): ToolParameterSchema = ToolParameterSchema(
        required = emptyList(),
        optional = listOf(
            ParamSpec(
                name = "reason",
                type = ParamType.STRING,
                description = "可选：说明为什么需要定位（给用户看的确认理由）"
            )
        )
    )

    override suspend fun execute(
        params: Map<String, Any>,
        context: ToolContext
    ): Outcome<ToolResult> {
        val opts = runCatching { toolSettingsRepository.getOptions(id) }.getOrDefault(emptyMap())
        val precise = opts["precise"]?.toBooleanStrictOrNull() ?: false
        val timeoutSec = (opts["timeout_seconds"]?.toIntOrNull() ?: 8).coerceIn(3, 20)

        val permission = if (precise) {
            Manifest.permission.ACCESS_FINE_LOCATION
        } else {
            Manifest.permission.ACCESS_COARSE_LOCATION
        }
        val granted = ContextCompat.checkSelfPermission(this.context, permission) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            return Outcome.Failure(
                "缺少定位权限（${if (precise) "精确位置" else "粗略位置"}）。" +
                    "请到系统设置 → 应用 → AIBox → 权限 → 位置 中允许。"
            )
        }

        val lm = this.context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return Outcome.Failure("设备没有定位服务")

        val providerOrder = if (precise) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        }

        // 1) 先用足够新的 last known
        val last = providerOrder
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 60_000) {
            return success(last, precise, cached = true)
        }

        // 2) 发起一次定位，带超时
        val provider = providerOrder.firstOrNull { p ->
            runCatching { lm.isProviderEnabled(p) }.getOrDefault(false)
        } ?: return last?.let { success(it, precise, cached = true) }
            ?: Outcome.Failure("定位服务不可用（GPS / 网络定位均已关闭）")

        LogSystem.info(TAG, "request single location: provider=$provider timeout=${timeoutSec}s precise=$precise")
        val fresh = withTimeoutOrNull(timeoutSec * 1000L) {
            awaitSingleLocation(lm, provider)
        }
        val result = fresh ?: last
        return result?.let { success(it, precise, cached = fresh == null) }
            ?: Outcome.Failure("定位超时（${timeoutSec}s），室内或信号弱时可稍后重试")
    }

    private suspend fun awaitSingleLocation(
        lm: LocationManager,
        provider: String
    ): Location? = suspendCancellableCoroutine { cont ->
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                runCatching { lm.removeUpdates(this) }
                if (cont.isActive) cont.resume(location)
            }

            @Deprecated("Deprecated in API 29")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
        try {
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        } catch (e: Throwable) {
            runCatching { lm.removeUpdates(listener) }
            if (cont.isActive) cont.resume(null)
        }
    }

    private fun success(location: Location, precise: Boolean, cached: Boolean): Outcome<ToolResult> {
        val lat = location.latitude
        val lon = location.longitude
        val gcj = wgs84ToGcj02(lat, lon)
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(location.time))
        val output = buildString {
            appendLine("定位结果（${if (precise) "精确" else "粗略"}${if (cached) "，缓存位置" else ""}）：")
            appendLine("WGS84：${"%.6f".format(lat)}, ${"%.6f".format(lon)}")
            appendLine("GCJ-02（国内地图）：${"%.6f".format(gcj.first)}, ${"%.6f".format(gcj.second)}")
            appendLine("精度：约 ${location.accuracy.roundToIntOrZero()} 米")
            appendLine("来源：${location.provider ?: "unknown"} · 时间：$time")
            appendLine("提示：室内/隧道可能偏差较大；坐标仅供参考，不能替代专业定位设备。")
        }.trim()
        return Outcome.Success(
            ToolResult(
                success = true,
                output = output,
                structuredData = mapOf(
                    "latitude" to lat,
                    "longitude" to lon,
                    "gcj02_lat" to gcj.first,
                    "gcj02_lon" to gcj.second,
                    "accuracy" to location.accuracy,
                    "provider" to (location.provider ?: ""),
                    "time" to location.time
                )
            )
        )
    }

    private fun Float.roundToIntOrZero(): Int = if (isFinite()) toInt() else 0

    /**
     * WGS84 → GCJ-02 偏移（国内地图坐标）。
     * 标准公开算法，仅本地计算，不涉及网络。
     */
    private fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
        val a = 6378245.0
        val ee = 0.00669342162296594323
        if (lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271) {
            return lat to lon // 国外不偏移
        }
        fun transformLat(x: Double, y: Double): Double {
            var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
            ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
            ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
            ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0
            return ret
        }
        fun transformLon(x: Double, y: Double): Double {
            var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
            ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
            ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
            ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
            return ret
        }
        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * Math.PI
        var magic = Math.sin(radLat)
        magic = 1 - ee * magic * magic
        val sqrtMagic = Math.sqrt(magic)
        dLat = (dLat * 180.0) / ((a * (1 - ee)) / (magic * sqrtMagic) * Math.PI)
        dLon = (dLon * 180.0) / (a / sqrtMagic * Math.cos(radLat) * Math.PI)
        return (lat + dLat) to (lon + dLon)
    }

    companion object {
        private const val TAG = "aibox.tool.gps"
    }
}
