package com.aibox.core.diagnostics

import com.aibox.core.inference.HexagonVersion
import com.aibox.core.inference.NpuVendor

/**
 * NPU 可用性检测器 — 在宿主启动时和首次推理前调用。
 *
 * 与 [NpuCompatibilityDiagnostics] 区别：
 *   - 本类回答"NPU 在不在？"
 *   - 兼容性诊断回答"模型能不能用 NPU 跑？"
 *
 * 触发场景：
 *   1. App 首次启动：调用 [probe] → 如果有可用 NPU 但用户没启用，显示 onboarding 引导
 *   2. 用户进入设置页：再次 probe，刷新状态
 *   3. 用户加载模型后：结合兼容性诊断，决定是否提示
 */
public interface NpuAvailabilityProbe {
    /**
     * 同步检测 NPU 可用性。
     *
     * 实现注意：
     *   - 不阻塞调用方，最快返回（用缓存值）
     *   - 首次调用可能在 ServiceConnection 建立阶段，需 < 100ms 返回
     *   - 实测可用性耗时操作走 [probeAsync]
     */
    public fun probe(): NpuAvailability

    /**
     * 实测 NPU 可用性（加载小模型做一次小推理，验证 NPU 真能用）。
     */
    public suspend fun probeAsync(): NpuAvailability

    public companion object
}

/**
 * NPU 可用性结果。
 *
 * 三档：
 * - [Available]   : 检测到 NPU 且可用（含厂商信息）
 * - [Unavailable] : 设备有 NPU 但不可用（如 Hexagon SDK 未安装插件）
 * - [NotPresent]   : 设备没有 NPU
 */
public sealed class NpuAvailability {
    public data class Available(
        public val vendor: NpuVendor,
        public val hexagonVersion: HexagonVersion? = null,
        public val needsPluginInstall: Boolean = false,
    ) : NpuAvailability()

    public data class Unavailable(
        public val vendor: NpuVendor,
        public val reason: String,
    ) : NpuAvailability()

    public data object NotPresent : NpuAvailability()

    public val isAvailable: Boolean get() = this is Available
    public val needsPlugin: Boolean get() = this is Available && this.needsPluginInstall
}

/**
 * 默认实现：基于设备 SoC 推断 NPU 可用性。
 *
 * 与 HexagonDetector 协作：
 * - HexagonDetector 检测 SoC 型号 → 得到 Hexagon 版本
 * - 本类包装为 NpuAvailability：
 *   - 检测到 V73~V81 + 已装 npu-snpe 插件 → Available(needsPluginInstall=false)
 *   - 检测到 V73~V81 + 未装插件 → Available(needsPluginInstall=true)
 *   - 未检测到 → NotPresent
 *   - 检测到但 SDK 初始化失败 → Unavailable
 */
public class DefaultNpuAvailabilityProbe(
    private val deviceHexagonVersion: HexagonVersion?,
    private val installedPlugins: Set<String>,
) : NpuAvailabilityProbe {

    override fun probe(): NpuAvailability {
        val version = deviceHexagonVersion ?: return NpuAvailability.NotPresent
        val pluginInstalled = NPU_SNPE_PLUGIN_ID in installedPlugins
        return NpuAvailability.Available(
            vendor = NpuVendor.QUALCOMM_HEXAGON,
            hexagonVersion = version,
            needsPluginInstall = !pluginInstalled,
        )
    }

    override suspend fun probeAsync(): NpuAvailability = probe()  // 简化版，真实实现需要尝试加载小模型

    public companion object {
        /** Hexagon NPU 插件包名（与 NudgeDecider.HEXAGON_PLUGIN_ID 保持一致） */
        public const val NPU_SNPE_PLUGIN_ID: String = "com.aibox.plugins.npu_snpe"
    }
}
