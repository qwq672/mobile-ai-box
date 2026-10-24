package com.aibox.domain.inference

import com.aibox.infra.plugin.SettingField
import com.aibox.infra.plugin.SettingOption

/**
 * V1 Backend 的 Descriptor：让 BackendManagerV2 能识别 V1 后端并自动注册。
 *
 * V1 后端不需要复杂的 settings schema（参数由 SettingsRepository 管理），
 * 这里只暴露一个最小的 settings 列表让 SchemaRenderer 能渲染基本界面。
 *
 * 真实的 V1 配置（threads / kvQuant / batch / context 等）仍走 V1 SettingsRepository；
 * 本 Descriptor 只提供 V2 侧的"我能干什么"声明。
 */
class V1BackendDescriptor(
    private val backendType: BackendType
) : BackendDescriptor {

    override fun getName(): String = backendType.id

    override fun getDisplayName(): String = backendType.displayNameZh

    override fun getVersion(): String = "v1-1.0"

    override fun describeCapabilities(): BackendCapabilities {
        val features = mutableSetOf<BackendFeature>().apply {
            add(BackendFeature.STREAMING)
            add(BackendFeature.KV_CACHE_QUANTIZATION)
            when (backendType) {
                BackendType.CPU -> add(BackendFeature.FLASH_ATTENTION)
                BackendType.VULKAN -> add(BackendFeature.VULKAN_GPU)
                BackendType.NPU -> add(BackendFeature.HEXAGON_NPU)
                BackendType.OPENCL -> add(BackendFeature.OPENCL_GPU)
            }
        }.toSet()

        return BackendCapabilities(
            supportedTasks = setOf(TaskType.TEXT_GENERATION),
            supportedInputModalities = setOf(Modality.TEXT),
            supportedOutputModalities = setOf(Modality.TEXT),
            maxContextLength = 8192,
            supportedModelFormats = listOf("gguf"),
            supportedQuantizations = listOf(
                "F16", "Q8_0", "Q6_0", "Q5_0", "Q4_0", "Q4_1",
                "Q3_K", "Q4_K", "Q5_K", "Q6_K", "Q8_K",
                "IQ2_XXS", "IQ2_S", "IQ3_S", "IQ3_M", "IQ4_XS"
            ),
            features = features
        )
    }

    override fun getSettingsSchema(): List<SettingField> = emptyList()  // V1 配置走 SettingsRepository,这里不暴露

    override fun getDefaultSettings(): Map<String, Any> = emptyMap()

    override fun parseSettings(values: Map<String, Any>): InferenceConfigV2 {
        // V1 后端不需要 parseSettings（参数由 V1 SettingsRepository 管）
        return InferenceConfigV2()
    }
}
