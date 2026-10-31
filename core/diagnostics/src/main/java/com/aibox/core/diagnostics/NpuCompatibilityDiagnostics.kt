package com.aibox.core.diagnostics

import com.aibox.core.inference.HexagonVersion
import com.aibox.core.inference.NpuVendor
import com.aibox.core.inference.QuantizationKind

/**
 * NPU 兼容性诊断器 — 集中处理所有"模型量化 vs NPU 能力"的判定。
 *
 * 反屎山原则：
 * - 把"NPU 能跑什么"的判定逻辑独立成纯 Kotlin 类（无 Android 依赖，便于单元测试）
 * - 所有兼容性矩阵集中在 [QuantizationNpuMatrix]，禁止散落
 * - 提示文案在外部生成，本类只返回 [CompatibilityVerdict] 枚举，让 UI 决定怎么显示
 *
 * 模块归属决策（按用户要求）：
 * - 本模块属于核心层（core/diagnostics），宿主和插件都能调用
 * - 因为这个诊断对"是否要弹提示"和"是否要装加速插件"都关键
 * - 插件层也能 import 这个接口，自己决定如何提示
 *
 * 设计参考：docs/NUDGE_AND_DIAGNOSTICS.md
 */
public class NpuCompatibilityDiagnostics {

    /**
     * 给定 NPU 厂商和模型量化，返回兼容性判定。
     */
    public fun diagnose(
        vendor: NpuVendor,
        quantization: QuantizationKind,
        hexagonVersion: HexagonVersion? = null,
    ): CompatibilityVerdict {
        val matrix = QuantizationNpuMatrix.forVendor(vendor, hexagonVersion)
        return matrix.check(quantization)
    }

    /**
     * 批量诊断：用于"模型市场"展示时，把每种量化都标注一下兼容性。
     */
    public fun diagnoseBatch(
        vendor: NpuVendor,
        quantizations: List<QuantizationKind>,
        hexagonVersion: HexagonVersion? = null,
    ): Map<QuantizationKind, CompatibilityVerdict> {
        return quantizations.associateWith { diagnose(vendor, it, hexagonVersion) }
    }
}

/**
 * 兼容性判定结果（4 档，覆盖用户两个文案场景）。
 *
 * - [FullSupport]   : 量化是 NPU 原生支持的（如 INT8 对应 V75）
 * - [PartialSupport]: 能跑但不是最优（如 Q8_0 在 Hexagon 上需 fallback 到 Q4_K_S）
 * - [QuantUnsupported]: 用户文案"该量化模型可能无法启用 NPU 加速"
 * - [FormatUnsupported]: 用户文案"该量化格式不受支持，速度会显著变慢"
 */
public sealed class CompatibilityVerdict {
    public data object FullSupport : CompatibilityVerdict()
    public data class PartialSupport(val reason: String) : CompatibilityVerdict()
    public data class QuantUnsupported(val npuVendor: NpuVendor) : CompatibilityVerdict() {
        /** 用户文案（中文） */
        public fun messageCn(): String = "该量化的模型可能无法成功启用 NPU 加速（${npuVendor.displayName()}）。"
        /** 用户文案（英文） */
        public fun messageEn(): String = "This quantization may fail to enable NPU acceleration (${npuVendor.displayName()})."
    }
    public data class FormatUnsupported(val format: String) : CompatibilityVerdict() {
        public fun messageCn(): String = "该模型的量化格式不受支持，速度可能会显著变慢。"
        public fun messageEn(): String = "The model's quantization format is unsupported; performance may degrade significantly."
    }
}

/** NPU 厂商中文名 */
public fun NpuVendor.displayName(): String = when (this) {
    NpuVendor.QUALCOMM_HEXAGON -> "高通 Hexagon"
    NpuVendor.HUAWEI_HIAI -> "华为 HiAI"
    NpuVendor.MEDIATEK_NEUROPILOT -> "联发科 NeuroPilot"
    NpuVendor.GOOGLE_NNAPI -> "通用 NNAPI"
}

/**
 * 量化 × NPU 兼容矩阵。
 *
 * 数据来源依据（公开文档）：
 *
 * 1. 高通 Hexagon（V73~V81）原生支持 INT8 / INT16
 *    - Q4_0 / Q4_1 / Q4_K / Q5_K 在 Hexagon 上需 CPU 解量化，NPU 不加速
 *    - F32 / F16 完全不支持，必须 CPU 兜底
 *
 * 2. 华为 HiAI 主推 INT8，FP16 可用但慢
 *
 * 3. 联发科 NeuroPilot INT8 最优，FP16 兜底
 *
 * 4. 通用 NNAPI 因厂商不同，INT8 大多支持，Q4_K/Q5_K 兼容性差
 */
public object QuantizationNpuMatrix {

    public fun forVendor(
        vendor: NpuVendor,
        hexagonVersion: HexagonVersion?,
    ): Matrix = when (vendor) {
        NpuVendor.QUALCOMM_HEXAGON -> HexagonMatrix(hexagonVersion ?: HexagonVersion.V73)
        NpuVendor.HUAWEI_HIAI -> HiAiMatrix
        NpuVendor.MEDIATEK_NEUROPILOT -> NeuroPilotMatrix
        NpuVendor.GOOGLE_NNAPI -> NnapiMatrix
    }

    public interface Matrix {
        public fun check(q: QuantizationKind): CompatibilityVerdict
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Hexagon 矩阵（V73~V81）
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    public class HexagonMatrix(private val version: HexagonVersion) : Matrix {
        override fun check(q: QuantizationKind): CompatibilityVerdict = when (q) {
            // 原生支持
            QuantizationKind.INT8,
            QuantizationKind.INT16,
            QuantizationKind.INT4 -> CompatibilityVerdict.FullSupport

            // 部分：能跑但不最优
            QuantizationKind.Q8_0 ->
                CompatibilityVerdict.PartialSupport("Q8_0 需在线性层 fallback 到 INT8，部分加速")
            QuantizationKind.Q4_0, QuantizationKind.Q4_1 ->
                CompatibilityVerdict.PartialSupport("Q4_0/Q4_1 需 CPU 解量化，加速有限")
            QuantizationKind.Q4_K, QuantizationKind.Q5_K ->
                CompatibilityVerdict.QuantUnsupported(NpuVendor.QUALCOMM_HEXAGON)

            // 完全不支持，必须 CPU
            QuantizationKind.F32, QuantizationKind.F16, QuantizationKind.BF16 ->
                CompatibilityVerdict.FormatUnsupported(format = q.name)
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 华为 HiAI 矩阵
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    public object HiAiMatrix : Matrix {
        override fun check(q: QuantizationKind): CompatibilityVerdict = when (q) {
            QuantizationKind.INT8, QuantizationKind.INT16 -> CompatibilityVerdict.FullSupport
            QuantizationKind.F16 -> CompatibilityVerdict.PartialSupport("HiAI FP16 支持但性能不如 INT8")
            QuantizationKind.INT4 -> CompatibilityVerdict.PartialSupport("INT4 兼容性视设备")
            QuantizationKind.Q4_0, QuantizationKind.Q4_1, QuantizationKind.Q4_K,
            QuantizationKind.Q5_K, QuantizationKind.Q8_0 ->
                CompatibilityVerdict.QuantUnsupported(NpuVendor.HUAWEI_HIAI)
            QuantizationKind.F32, QuantizationKind.BF16 ->
                CompatibilityVerdict.FormatUnsupported(format = q.name)
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 联发科 NeuroPilot 矩阵
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    public object NeuroPilotMatrix : Matrix {
        override fun check(q: QuantizationKind): CompatibilityVerdict = when (q) {
            QuantizationKind.INT8, QuantizationKind.INT16 -> CompatibilityVerdict.FullSupport
            QuantizationKind.F16 -> CompatibilityVerdict.PartialSupport("NeuroPilot FP16 兜底")
            QuantizationKind.INT4 -> CompatibilityVerdict.PartialSupport("INT4 视设备型号")
            QuantizationKind.Q4_0, QuantizationKind.Q4_1, QuantizationKind.Q4_K,
            QuantizationKind.Q5_K, QuantizationKind.Q8_0 ->
                CompatibilityVerdict.QuantUnsupported(NpuVendor.MEDIATEK_NEUROPILOT)
            QuantizationKind.F32, QuantizationKind.BF16 ->
                CompatibilityVerdict.FormatUnsupported(format = q.name)
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 通用 NNAPI 矩阵（保守）
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    public object NnapiMatrix : Matrix {
        override fun check(q: QuantizationKind): CompatibilityVerdict = when (q) {
            QuantizationKind.INT8 -> CompatibilityVerdict.FullSupport
            QuantizationKind.F16, QuantizationKind.INT16 ->
                CompatibilityVerdict.PartialSupport("NNAPI FP16 性能取决于厂商实现")
            QuantizationKind.INT4 -> CompatibilityVerdict.PartialSupport("INT4 NNAPI 1.x 后支持")
            QuantizationKind.Q4_0, QuantizationKind.Q4_1, QuantizationKind.Q4_K,
            QuantizationKind.Q5_K, QuantizationKind.Q8_0 ->
                CompatibilityVerdict.QuantUnsupported(NpuVendor.GOOGLE_NNAPI)
            QuantizationKind.F32, QuantizationKind.BF16 ->
                CompatibilityVerdict.FormatUnsupported(format = q.name)
        }
    }
}
