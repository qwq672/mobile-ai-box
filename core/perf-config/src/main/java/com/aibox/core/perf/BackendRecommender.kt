package com.aibox.core.perf

import com.aibox.core.inference.NpuVendor

/**
 * 后端推荐器 — 根据用户场景和设备能力推荐最合适的推理后端。
 *
 * 反屎山原则：
 * - 决策逻辑独立成纯 Kotlin 类（便于单元测试）
 * - 推荐理由可解释（[BackendRecommendation.reason]），UI 可显示
 * - 不固定单一答案：返回排序后的 [BackendRecommendation] 列表，UI 显示 Top N
 *
 * 应用场景：
 *   1. 首次启动 onboarding："想用 AIBox 做什么？" → 推荐合适后端
 *   2. 用户加载模型后：根据模型量化格式推荐能加速的后端
 *   3. 自动调优：把推荐列表作为 [BACKEND_SWITCH] 维度的初始候选
 */
public class BackendRecommender(
    private val deviceInfo: DeviceCapability,
) {

    public fun recommendForUseCase(useCase: UseCase, installedBackends: List<String>): List<BackendRecommendation> {
        val scored = installedBackends.map { backendId ->
            val score = scoreBackend(backendId, useCase)
            BackendRecommendation(
                backendId = backendId,
                score = score.value,
                reason = score.reason,
                rank = 0,  // 稍后填
            )
        }.sortedByDescending { it.score }

        return scored.mapIndexed { index, rec -> rec.copy(rank = index + 1) }
    }

    private fun scoreBackend(backendId: String, useCase: UseCase): ScoredBackend {
        val baseScore = when {
            // NPU 后端
            backendId == "npu-snpe" && deviceInfo.hexagonVendor == NpuVendor.QUALCOMM_HEXAGON -> {
                ScoredBackend(95, "你的设备有 Hexagon V${deviceInfo.hexagonVersion} NPU，最适合本地推理")
            }
            backendId == "npu-nnapi" && deviceInfo.hasNnapiSupport -> {
                ScoredBackend(75, "通用 NNAPI 后端，零成本即可用 NPU 加速")
            }
            backendId == "npu-hiai" && deviceInfo.hexagonVendor == NpuVendor.HUAWEI_HIAI -> {
                ScoredBackend(90, "你的设备是华为 HiAI NPU")
            }

            // CPU/GPU 后端
            backendId == "llama.cpp" -> {
                ScoredBackend(60, "通用 CPU 后端，开箱即用，兼容性最好")
            }
            backendId == "mlc" && deviceInfo.hasVulkanSupport -> {
                ScoredBackend(70, "MLC 支持 Vulkan，GPU 加速比 CPU 快 2-3x")
            }
            backendId == "mnn" -> {
                ScoredBackend(55, "MNN 适合多模态推理（生图/视觉）")
            }
            backendId == "onnx" -> {
                ScoredBackend(50, "ONNX Runtime 适合企业模型转换场景")
            }
            backendId == "tflite" -> {
                ScoredBackend(40, "TensorFlow Lite 适合嵌入式小模型")
            }

            else -> ScoredBackend(20, "未知后端")
        }

        // 按使用场景加权
        val useCaseAdjustment = when (useCase) {
            UseCase.CHAT -> when (backendId) {
                "npu-snpe", "npu-nnapi" -> 5
                "llama.cpp" -> 0
                "mlc" -> 3
                else -> -5
            }
            UseCase.IMAGE_GENERATION -> when (backendId) {
                "mnn" -> 15
                "tflite" -> 10
                "npu-snpe" -> 5
                else -> -10
            }
            UseCase.AUDIO_TTS -> when (backendId) {
                "onnx" -> 10
                "mnn" -> 5
                else -> 0
            }
            UseCase.AUDIO_STT -> when (backendId) {
                "onnx" -> 15
                "tflite" -> 10
                else -> -5
            }
            UseCase.AGENT -> 0  // Agent 主要看 LLM 速度
            UseCase.TRANSLATE -> 0
        }

        return baseScore.copy(value = baseScore.value + useCaseAdjustment)
    }
}

/** 设备能力描述 */
public data class DeviceCapability(
    public val hexagonVendor: NpuVendor?,
    public val hexagonVersion: String?,
    public val hasNnapiSupport: Boolean,
    public val hasVulkanSupport: Boolean,
    public val cpuCores: Int,
    public val memoryMb: Long,
)

/** 使用场景（推荐时用作加权信号） */
public enum class UseCase {
    CHAT,
    IMAGE_GENERATION,
    AUDIO_TTS,
    AUDIO_STT,
    AGENT,
    TRANSLATE,
}

/** 后端推荐结果 */
public data class BackendRecommendation(
    public val backendId: String,
    public val score: Int,         // 0..100
    public val reason: String,    // 可向用户解释的推荐理由
    public val rank: Int,          // 1=最佳推荐
)

private data class ScoredBackend(val value: Int, val reason: String)
