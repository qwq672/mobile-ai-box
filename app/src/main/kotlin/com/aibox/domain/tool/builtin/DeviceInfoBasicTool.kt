package com.aibox.domain.tool.builtin

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.aibox.domain.tool.DeviceInfo
import com.aibox.domain.tool.ParamSpec
import com.aibox.domain.tool.ParamType
import com.aibox.domain.tool.Tool
import com.aibox.domain.tool.ToolCategory
import com.aibox.domain.tool.ToolContext
import com.aibox.domain.tool.ToolParameterSchema
import com.aibox.domain.tool.ToolResult
import com.aibox.util.CpuInfo
import com.aibox.util.log.LogSystem
import com.aibox.util.outcome.Outcome
import java.io.File

/**
 * DeviceInfoBasicTool（B5 基础工具）。
 *
 * 获取当前设备的基础信息：型号 / Android 版本 / CPU / 内存 / 电量。
 * **不包含敏感信息**（不返回 IMEI / Android ID / 设备序列号 / 地理位置 / 应用列表）。
 *
 * - 工具 ID：`device_info_basic`（默认启用）
 * - 分类：[ToolCategory.BASIC]，自动执行，无副作用
 *
 * 数据来源：
 * - 设备型号：[Build.MANUFACTURER] + [Build.MODEL]
 * - Android 版本：[Build.VERSION.RELEASE] + [Build.VERSION.SDK_INT]
 * - ABI：[Build.SUPPORTED_ABIS]
 * - CPU：[CpuInfo]（已有 util） + 从 `/proc/cpuinfo` 读 Hardware
 * - RAM：[ActivityManager.MemoryInfo]（totalMem / availMem）
 * - 电量：[Intent.ACTION_BATTERY_CHANGED] sticky broadcast（电量百分比 + 充电状态）
 *
 * 设计原则：
 * - 零异常约定：读 /proc/cpuinfo / ActivityManager / BatteryManager 都用 try-catch 兜底，不抛出界
 * - 不读敏感权限字段（无 READ_PHONE_STATE / ACCESS_FINE_LOCATION）
 * - 复用 [ToolContext.deviceInfo]（AgentRunner 在调用前已构造的快照）+ 现读 RAM/电池（保证数据新鲜）
 * - [ToolBootstrap] 注册时传入 appContext，让工具能拿系统服务
 *
 * 输出：
 * - [ToolResult.output]：多行可读文本，按 设备 / CPU / 内存 / 电池 4 个 section 分组
 * - [ToolResult.structuredData]：[DeviceInfoSnapshot]，含全部字段便于 UI 渲染卡片
 *
 * 失败场景：理论上不应失败（每段读取都有 try-catch 兜底），最坏情况返回部分字段为 -1 / "unknown"
 */
class DeviceInfoBasicTool(
    private val appContext: Context
) : Tool {
    override val id: String = "device_info_basic"
    override val displayName: String = "设备基础信息"
    override val description: String =
        "获取当前设备的基础信息:型号、Android 版本、CPU、内存、电量。不包含敏感信息。"
    override val category: ToolCategory = ToolCategory.BASIC
    override val icon: String = "📱"

    override fun getParameterSchema(): ToolParameterSchema = ToolParameterSchema(
        required = emptyList(),
        optional = listOf(
            ParamSpec(
                name = "include_battery",
                type = ParamType.BOOLEAN,
                description = "包含电量信息,默认 true",
                default = true
            ),
            ParamSpec(
                name = "include_cpu",
                type = ParamType.BOOLEAN,
                description = "包含 CPU 信息,默认 true",
                default = true
            )
        )
    )

    override suspend fun execute(
        params: Map<String, Any>,
        context: ToolContext
    ): Outcome<ToolResult> {
        val includeBattery = (params["include_battery"] as? Boolean) ?: true
        val includeCpu = (params["include_cpu"] as? Boolean) ?: true

        return try {
            // 设备基础信息（不依赖任何开关，永远输出）
            val manufacturer = Build.MANUFACTURER ?: "unknown"
            val model = Build.MODEL ?: "unknown"
            val androidVersion = Build.VERSION.RELEASE ?: "unknown"
            val sdkInt = Build.VERSION.SDK_INT
            val abis = Build.SUPPORTED_ABIS?.toList() ?: emptyList()

            // CPU 信息（可关）
            val cpuInfo = if (includeCpu) {
                CpuSnapshot(
                    cores = CpuInfo.totalCores,
                    bigCores = CpuInfo.bigCores,
                    socName = CpuInfo.socName,
                    hardwareInfo = readCpuHardware()
                )
            } else null

            // 内存信息（永远输出，作为基础设备信息）
            val memSnapshot = readMemoryInfo()

            // 电池信息（可关）
            val batterySnapshot = if (includeBattery) readBatteryInfo() else null

            // 同时把 ToolContext.deviceInfo 合并进来（AgentRunner 提前构造的快照）
            val ctxDevice: DeviceInfo? = context.deviceInfo

            val output = buildString {
                appendLine("=== 设备基础信息 ===")
                appendLine("型号: $manufacturer $model")
                appendLine("Android: $androidVersion (SDK $sdkInt)")
                appendLine("ABI: ${abis.joinToString(", ")}")

                if (includeCpu && cpuInfo != null) {
                    appendLine()
                    appendLine("=== CPU ===")
                    appendLine("核心数: ${cpuInfo.cores}（大核: ${cpuInfo.bigCores}）")
                    appendLine("SoC 型号: ${cpuInfo.socName}")
                    if (cpuInfo.hardwareInfo.isNotBlank()) {
                        appendLine("Hardware: ${cpuInfo.hardwareInfo}")
                    }
                }

                appendLine()
                appendLine("=== 内存 ===")
                appendLine("总 RAM: ${memSnapshot.totalMb}MB")
                appendLine("可用 RAM: ${memSnapshot.availMb}MB")

                if (includeBattery && batterySnapshot != null) {
                    appendLine()
                    appendLine("=== 电池 ===")
                    if (batterySnapshot.level >= 0) {
                        appendLine("电量: ${batterySnapshot.level}%")
                    } else {
                        appendLine("电量: 读取失败")
                    }
                    appendLine("充电中: ${if (batterySnapshot.isCharging) "是" else "否"}")
                }
            }

            val snapshot = DeviceInfoSnapshot(
                manufacturer = manufacturer,
                model = model,
                androidVersion = androidVersion,
                sdkInt = sdkInt,
                abis = abis,
                cpu = cpuInfo,
                memory = memSnapshot,
                battery = batterySnapshot,
                contextDeviceInfo = ctxDevice
            )

            LogSystem.debug(TAG, "DeviceInfoBasic: model=$model, sdk=$sdkInt, cores=${cpuInfo?.cores}, ram=${memSnapshot.totalMb}MB")

            Outcome.Success(
                ToolResult(
                    success = true,
                    output = output,
                    structuredData = snapshot,
                    executionTimeMs = 0
                )
            )
        } catch (e: Throwable) {
            LogSystem.error(TAG, "DeviceInfoBasic error: ${e.message}", e)
            Outcome.Failure("DeviceInfoBasicTool error: ${e.message}", e)
        }
    }

    /**
     * 读 `/proc/cpuinfo` 的 `Hardware` 字段（SoC 标识）。
     *
     * 与 [CpuInfo.socName] 重复但独立调用便于单独 fail（部分设备 /proc/cpuinfo 不含 Hardware 行）。
     */
    private fun readCpuHardware(): String {
        return try {
            File("/proc/cpuinfo").useLines { seq ->
                seq.firstOrNull { it.startsWith("Hardware") }?.split(":")?.lastOrNull()?.trim()
            } ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    /** 通过 ActivityManager 读 RAM 信息（totalMem / availMem，转 MB）。 */
    private fun readMemoryInfo(): MemorySnapshot {
        return try {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return MemorySnapshot(-1, -1)
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            val totalMb = info.totalMem / (1024 * 1024)
            val availMb = info.availMem / (1024 * 1024)
            MemorySnapshot(totalMb, availMb)
        } catch (_: Throwable) {
            MemorySnapshot(-1, -1)
        }
    }

    /**
     * 读电量信息（sticky broadcast，立即返回，无需注册 receiver）。
     *
     * 用 `registerReceiver(null, filter)` 拿最后一次 sticky 广播；
     * Android 13+ (API 33+) 对非系统广播要求 flag，但 ACTION_BATTERY_CHANGED 是系统 sticky 广播，
     * 仍可无 flag 调用。为兼容 Android 14 targetSdk 34 的 lint 检查与未来收紧趋势，
     * 在 API 33+ 用 3 参 overload 显式传 `RECEIVER_NOT_EXPORTED`。
     */
    private fun readBatteryInfo(): BatterySnapshot? {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            // API 33+ 用带 flag 的 overload 避免 Android 14 lint 报错；
            // API 26-32 用 2 参 overload（系统 sticky 广播无 flag 要求）
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(null, filter)
            } ?: return null
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            BatterySnapshot(percent, isCharging)
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private const val TAG = "aibox.tool.device_info_basic"
    }
}

/** CPU 快照。 */
data class CpuSnapshot(
    val cores: Int,
    val bigCores: Int,
    val socName: String,
    val hardwareInfo: String
)

/** 内存快照（MB）。 */
data class MemorySnapshot(
    val totalMb: Long,
    val availMb: Long
)

/** 电池快照。 */
data class BatterySnapshot(
    val level: Int,        // 0..100；-1 表示读取失败
    val isCharging: Boolean
)

/**
 * 完整设备信息快照（DeviceInfoBasicTool 输出结构化数据）。
 *
 * 包含 [contextDeviceInfo]（[ToolContext] 调用时传入的 AgentRunner 预填快照）便于比对。
 */
data class DeviceInfoSnapshot(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkInt: Int,
    val abis: List<String>,
    val cpu: CpuSnapshot?,
    val memory: MemorySnapshot,
    val battery: BatterySnapshot?,
    val contextDeviceInfo: DeviceInfo?
)
