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
        "获取设备当前位置（单次定位），返回经纬度、精度与坐标系；开启精确位置时附带海拔（WGS84 椭球高）、速度与方位（定位数据有才报）。室内可能失败。"
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

        // 海拔/速度/方位都是「有才报」：定位数据自己没有这些字段时留空，
        // 绝不拿推算值冒充，也不新增任何权限——有精确位置时系统自然会带这些字段。
        val hasAltitude = location.hasAltitude()
        val altitude = if (hasAltitude) location.altitude else 0.0
        val hasVerticalAccuracy = hasAltitude && location.hasVerticalAccuracy()
        val verticalAccuracy = if (hasVerticalAccuracy) location.verticalAccuracyMeters else 0f
        // 速度过滤：66.7 m/s ≈ 240 km/h 这类假速度，来自缓存位置残留或粗略/被动 provider。
        // 规则：① 缓存位置（非本次 fresh fix）一律不报速度；
        //      ② 只有精确 GPS 的本次定位才信 speed；粗略/被动定位的 speed 不可靠，不报；
        //      ③ 速度值超出合理上限（高铁~100 m/s，>MAX_PLAUSIBLE_SPEED 视为异常）不报。
        // 这样既不新增权限，也避免把缓存里的旧/错速度当成"当前速度"。
        val speedPlausible = location.speed in 0f..MAX_PLAUSIBLE_SPEED
        val hasSpeed = !cached && precise &&
            location.provider == LocationManager.GPS_PROVIDER &&
            location.hasSpeed() && speedPlausible
        val speed = if (hasSpeed) location.speed else 0f
        val hasBearing = !cached && location.hasBearing()
        val bearing = if (hasBearing) location.bearing else 0f
        if (location.hasSpeed() && !hasSpeed) {
            LogSystem.info(
                TAG,
                "speed suppressed: raw=${location.speed} cached=$cached precise=$precise " +
                    "provider=${location.provider} plausible=$speedPlausible"
            )
        }

        val output = buildString {
            appendLine("定位结果（${if (precise) "精确" else "粗略"}${if (cached) "，缓存位置" else ""}）：")
            appendLine("WGS84：${"%.6f".format(lat)}, ${"%.6f".format(lon)}")
            appendLine("GCJ-02（国内地图）：${"%.6f".format(gcj.first)}, ${"%.6f".format(gcj.second)}")
            appendLine("水平精度：约 ${location.accuracy.roundToIntOrZero()} 米")
            if (hasAltitude) {
                // Android 的 altitude 是 WGS84 椭球高（海拔近似），不是气压高度。
                appendLine("海拔（WGS84 椭球高）：${"%.1f".format(altitude)} 米" +
                    if (hasVerticalAccuracy) "（垂直精度 ±${verticalAccuracy.roundToIntOrZero()} 米）" else "")
            }
            if (hasSpeed) {
                appendLine("速度：${"%.1f".format(speed)} m/s（约 ${"%.0f".format(speed * 3.6)} km/h）")
            }
            if (hasBearing) {
                appendLine("方位：${"%.0f".format(bearing)}°（0=正北，顺时针）")
            }
            appendLine("来源：${location.provider ?: "unknown"} · 时间：$time")
            appendLine("提示：室内/隧道可能偏差较大；坐标仅供参考，不能替代专业定位设备。")
        }.trim()

        val structured = LinkedHashMap<String, Any>().apply {
            put("latitude", lat)
            put("longitude", lon)
            put("gcj02_lat", gcj.first)
            put("gcj02_lon", gcj.second)
            put("accuracy", location.accuracy)          // 水平精度
            put("provider", (location.provider ?: ""))
            put("time", location.time)
            if (hasAltitude) {
                put("altitude", altitude)               // WGS84 椭球高，米
                put("altitude_ref", "WGS84_ellipsoid")  // 明确基准，避免与气压高度混淆
            }
            if (hasVerticalAccuracy) put("vertical_accuracy", verticalAccuracy)
            if (hasSpeed) {
                put("speed_mps", speed)
                put("speed_kph", speed * 3.6)
            }
            if (hasBearing) put("bearing_deg", bearing)
        }
        return Outcome.Success(
            ToolResult(
                success = true,
                output = output,
                structuredData = structured
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
        /** 速度合理上限（m/s）：约 360 km/h，超过视为缓存残留/定位异常，不报。 */
        private const val MAX_PLAUSIBLE_SPEED = 100f
    }
}
