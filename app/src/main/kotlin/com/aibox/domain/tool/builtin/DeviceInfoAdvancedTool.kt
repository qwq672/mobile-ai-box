package com.aibox.domain.tool.builtin

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import com.aibox.domain.tool.ParamSpec
import com.aibox.domain.tool.ParamType
import com.aibox.domain.tool.Tool
import com.aibox.domain.tool.ToolCategory
import com.aibox.domain.tool.ToolContext
import com.aibox.domain.tool.ToolParameterSchema
import com.aibox.domain.tool.ToolResult
import com.aibox.domain.tool.ToolSettingsRepository
import com.aibox.util.log.LogSystem
import com.aibox.util.outcome.Outcome

/**
 * DeviceInfoAdvancedTool（C3 高级工具）。
 *
 * 获取设备高级信息：电池电压/温度、传感器列表、已安装用户应用列表。
 *
 * - 工具 ID：`device_info_advanced`（默认关闭，需用户手动启用）
 * - 分类：[ToolCategory.ADVANCED]，需用户确认
 * - **依赖基础设备信息工具的开关**：若 `device_info_basic` 未启用 → 拒绝执行
 *
 * 隐私原则：不读 IMEI/Android ID/序列号；应用列表只返回包名+标签；传感器只返回型号+厂商。
 * 各系统读取失败 → 单段返回 unknown/empty，整体仍 SUCCESS（局部失败用日志记录）。
 */
class DeviceInfoAdvancedTool(
    private val appContext: Context,
    private val toolSettingsRepository: ToolSettingsRepository
) : Tool {
    override val id: String = "device_info_advanced"
    override val displayName: String = "设备高级信息"
    override val description: String =
        "获取设备的高级信息:电池电压温度、传感器列表、已安装用户应用。依赖基础设备信息工具的开关。"
    override val category: ToolCategory = ToolCategory.ADVANCED
    override val icon: String = "🔧"

    override fun getParameterSchema(): ToolParameterSchema = ToolParameterSchema(
        required = emptyList(),
        optional = listOf(
            ParamSpec(
                name = "include_sensors",
                type = ParamType.BOOLEAN,
                description = "包含传感器列表,默认 true",
                default = true
            ),
            ParamSpec(
                name = "include_apps",
                type = ParamType.BOOLEAN,
                description = "包含已安装应用列表,默认 true",
                default = true
            )
        )
    )

    override suspend fun execute(
        params: Map<String, Any>,
        context: ToolContext
    ): Outcome<ToolResult> {
        // 1. 检查基础设备信息工具是否启用
        val basicEnabled = try {
            toolSettingsRepository.isToolEnabled(BASIC_TOOL_ID)
        } catch (e: Throwable) {
            LogSystem.warn(TAG, "isToolEnabled('$BASIC_TOOL_ID') failed: ${e.message}")
            false
        }
        if (!basicEnabled) {
            return Outcome.Failure("需要先启用基础设备信息工具(device_info_basic)")
        }

        val includeSensors = (params["include_sensors"] as? Boolean) ?: true
        val includeApps = (params["include_apps"] as? Boolean) ?: true

        return try {
            val battery = readBatteryAdvanced()
            val sensors = if (includeSensors) readSensorList() else emptyList()
            val apps = if (includeApps) readUserApps() else emptyList()

            val output = buildString {
                appendLine("=== 电池(高级) ===")
                if (battery != null) {
                    appendLine("电压: ${battery.voltage} mV")
                    appendLine("温度: ${battery.temperature} °C")
                    appendLine("技术: ${battery.technology}")
                    appendLine("健康: ${battery.health}")
                } else {
                    appendLine("读取失败")
                }
                if (includeSensors) {
                    appendLine()
                    appendLine("=== 传感器(${sensors.size} 个) ===")
                    sensors.take(MAX_SENSOR_DISPLAY).forEach {
                        appendLine("- ${it.name} (${it.typeName}) vendor=${it.vendor}")
                    }
                    if (sensors.size > MAX_SENSOR_DISPLAY) {
                        appendLine("... 还有 ${sensors.size - MAX_SENSOR_DISPLAY} 个未显示")
                    }
                }
                if (includeApps) {
                    appendLine()
                    appendLine("=== 已安装用户应用(${apps.size} 个) ===")
                    apps.take(MAX_USER_APPS).forEach {
                        appendLine("- ${it.label} (${it.packageName})")
                    }
                    if (apps.size > MAX_USER_APPS) {
                        appendLine("... 还有 ${apps.size - MAX_USER_APPS} 个未显示")
                    }
                }
            }

            LogSystem.info(TAG, "DeviceInfoAdvanced: battery=${battery != null} sensors=${sensors.size} apps=${apps.size}")

            Outcome.Success(
                ToolResult(
                    success = true,
                    output = output,
                    structuredData = AdvancedDeviceInfoSnapshot(battery, sensors, apps),
                    executionTimeMs = 0
                )
            )
        } catch (e: Throwable) {
            LogSystem.error(TAG, "DeviceInfoAdvanced error: ${e.message}", e)
            Outcome.Failure("DeviceInfoAdvancedTool error: ${e.message}", e)
        }
    }

    private fun readBatteryAdvanced(): BatteryAdvancedSnapshot? {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(null, filter)
            } ?: return null
            val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)   // mV
            val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)  // 0.1°C
            val technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "unknown"
            val health = when (intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
                BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                else -> "unknown"
            }
            BatteryAdvancedSnapshot(voltage, if (temp >= 0) temp / 10.0 else -1.0, technology, health)
        } catch (_: Throwable) {
            null
        }
    }

    private fun readSensorList(): List<SensorSnapshot> {
        return try {
            val sm = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
                ?: return emptyList()
            sm.getSensorList(Sensor.TYPE_ALL).map { sensor ->
                SensorSnapshot(
                    name = sensor.name ?: "",
                    vendor = sensor.vendor ?: "",
                    typeName = sensorTypeName(sensor.type),
                    version = sensor.version
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun readUserApps(): List<AppSnapshot> {
        return try {
            val pm = appContext.packageManager
            val all = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            all.filter {
                (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                    (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            }.map {
                val label = try { pm.getApplicationLabel(it).toString() } catch (_: Throwable) { it.packageName }
                AppSnapshot(packageName = it.packageName, label = label)
            }.sortedBy { it.label.lowercase() }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun sensorTypeName(type: Int): String = when (type) {
        Sensor.TYPE_ACCELEROMETER -> "accelerometer"
        Sensor.TYPE_GYROSCOPE -> "gyroscope"
        Sensor.TYPE_MAGNETIC_FIELD -> "magnetic"
        Sensor.TYPE_LIGHT -> "light"
        Sensor.TYPE_PROXIMITY -> "proximity"
        Sensor.TYPE_PRESSURE -> "pressure"
        Sensor.TYPE_RELATIVE_HUMIDITY -> "humidity"
        Sensor.TYPE_AMBIENT_TEMPERATURE -> "ambient_temp"
        Sensor.TYPE_GRAVITY -> "gravity"
        Sensor.TYPE_LINEAR_ACCELERATION -> "linear_accel"
        Sensor.TYPE_ROTATION_VECTOR -> "rotation_vector"
        Sensor.TYPE_STEP_COUNTER -> "step_counter"
        Sensor.TYPE_STEP_DETECTOR -> "step_detector"
        else -> "type_$type"
    }

    companion object {
        private const val TAG = "aibox.tool.device_info_advanced"
        private const val BASIC_TOOL_ID = "device_info_basic"
        private const val MAX_SENSOR_DISPLAY = 20
        private const val MAX_USER_APPS = 50
    }
}

/** 电池高级快照（[DeviceInfoAdvancedTool] 输出）。 */
data class BatteryAdvancedSnapshot(
    val voltage: Int,             // mV；-1 表示读不到
    val temperature: Double,      // °C；-1 表示读不到
    val technology: String,
    val health: String
)

/** 单个传感器快照。 */
data class SensorSnapshot(
    val name: String,
    val vendor: String,
    val typeName: String,
    val version: Int
)

/** 已安装用户应用快照。 */
data class AppSnapshot(
    val packageName: String,
    val label: String
)

/** 高级设备信息快照（结构化数据）。 */
data class AdvancedDeviceInfoSnapshot(
    val battery: BatteryAdvancedSnapshot?,
    val sensors: List<SensorSnapshot>,
    val apps: List<AppSnapshot>
)
