package com.aibox.domain.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

/**
 * 设备能力快照。
 *
 * P3/P4 的地基：MoE 流式模块与 Hexagon NPU 预填充模块在下载/启用前，
 * 都需要先知道内存、ABI、SoC 与 NPU 运行时是否存在，避免"装上跑不动"。
 *
 * 注意：
 * - [htpArchHint] 是**推断值**，来自 SoC 型号映射，只用于 UI 提示与模块筛选，
 *   真正能否加载仍以模块自身的探测结果为准。
 * - Android 没有公开的 UFS 代次 API，这里不猜存储等级。
 */
data class DeviceCapabilities(
    val totalRamMb: Long,
    val availableRamMb: Long,
    val primaryAbi: String,
    val socManufacturer: String,
    val socModel: String,
    val hardware: String,
    val htpArchHint: String?,
    val qnnRuntimePresent: Boolean,
    val hexagonSkelPresent: Boolean,
    /** 是否具备 MoE 流式（权重常驻 + 专家换页）的硬件前提。 */
    val supportsMoeStreaming: Boolean,
    /** 是否具备 NPU 预填充的硬件前提。 */
    val supportsNpuPrefill: Boolean
) {
    fun summary(): String = buildString {
        append("ram=").append(totalRamMb).append("MB/").append(availableRamMb).append("MB")
        append(" abi=").append(primaryAbi)
        append(" soc=").append(socManufacturer).append(' ').append(socModel)
        append(" hardware=").append(hardware)
        append(" htp=").append(htpArchHint ?: "unknown")
        append(" qnn=").append(qnnRuntimePresent)
        append(" skel=").append(hexagonSkelPresent)
        append(" moeStream=").append(supportsMoeStreaming)
        append(" npuPrefill=").append(supportsNpuPrefill)
    }
}

object DeviceCapabilitiesProbe {

    fun probe(context: Context): DeviceCapabilities {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mem = ActivityManager.MemoryInfo().also { info ->
            runCatching { am?.getMemoryInfo(info) }
        }
        val totalMb = mem.totalMem / (1024L * 1024L)
        val availMb = mem.availMem / (1024L * 1024L)
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL
        } else {
            Build.HARDWARE
        }
        val socMaker = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER
        } else {
            Build.MANUFACTURER
        }
        val htp = htpArchFor(socModel)
        val qnn = VENDOR_LIBS.any { File(it).exists() }
        val skel = htp != null && File("/vendor/lib64/libQnnHtp${htp.uppercase()}Skel.so").exists()
        val isArm64 = abi.contains("arm64")
        return DeviceCapabilities(
            totalRamMb = totalMb,
            availableRamMb = availMb,
            primaryAbi = abi,
            socManufacturer = socMaker.orEmpty(),
            socModel = socModel.orEmpty(),
            hardware = Build.HARDWARE.orEmpty(),
            htpArchHint = htp,
            qnnRuntimePresent = qnn,
            hexagonSkelPresent = skel,
            // 12GB 整机约为可用 9~10GB，30B-A3B Q4 需要流式；
            // 低于 11GB 总内存时不建议提供 MoE 流式模块。
            supportsMoeStreaming = isArm64 && totalMb >= 11_000L,
            supportsNpuPrefill = isArm64 && htp != null
        )
    }

    /**
     * SoC → Hexagon HTP 架构的保守映射，仅覆盖已知型号。
     * 不认识的 SoC 返回 null，调用方应视为"未知/不可用"。
     */
    private fun htpArchFor(socModel: String): String? {
        val s = socModel.uppercase()
        return when {
            s.contains("SM8850") -> "v81" // 骁龙 8 Elite Gen 5
            s.contains("SM8750") -> "v79" // 骁龙 8 Elite
            s.contains("SM8650") -> "v75" // 骁龙 8 Gen 3
            s.contains("SM8550") -> "v73" // 骁龙 8 Gen 2
            s.contains("SM8475") -> "v69" // 骁龙 8+ Gen 1
            s.contains("SM8450") -> "v69" // 骁龙 8 Gen 1
            else -> null
        }
    }

    private val VENDOR_LIBS = listOf(
        "/vendor/lib64/libQnnHtp.so",
        "/vendor/lib64/libQnnHtpV75Skel.so",
        "/vendor/lib64/libQnnHtpV79Skel.so",
        "/system/lib64/libQnnHtp.so"
    )
}
