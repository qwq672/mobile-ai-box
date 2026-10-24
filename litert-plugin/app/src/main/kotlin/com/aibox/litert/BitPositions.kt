package com.aibox.litert

/**
 * 宿主侧枚举 bit 位映射(本工程不依赖宿主 Kotlin 源码,故本地复制 ordinal 值)。
 *
 * 这些 ordinal 与宿主 `com.aibox.domain.inference.TaskType / Modality / BackendFeature`
 * 枚举的声明顺序严格对齐。任一宿主侧枚举值新增 / 重排都需要同步本文件,否则
 * `BackendCapabilitiesParcel.supportedTasksBitmask` 等字段会出现错位。
 *
 * 对应关系(由宿主 P1.2 的 AIDL 文档 + BackendCapabilities.kt 描述确认):
 *
 *  - [TaskType] (宿主 domain/inference/TaskType.kt):
 *      TEXT_GENERATION=0 / TEXT_CLASSIFICATION=1 / EMBEDDING=2 /
 *      IMAGE_GENERATION=3 / IMAGE_EDITING=4 / IMAGE_UNDERSTANDING=5 /
 *      AUDIO_TRANSCRIPTION=6 / AUDIO_GENERATION=7 /
 *      MULTIMODAL_UNDERSTANDING=8 / MULTIMODAL_GENERATION=9
 *
 *  - [Modality]:
 *      TEXT=0 / IMAGE=1 / AUDIO=2 / VIDEO=3
 *
 *  - [BackendFeature] (宿主 domain/inference/BackendCapabilities.kt):
 *      FLASH_ATTENTION=0 / VULKAN_GPU=1 / OPENCL_GPU=2 / HEXAGON_NPU=3 /
 *      TENSOR_GTP=4 / MTK_APU=5 /
 *      STREAMING=6 / BATCHED_INFERENCE=7 / KV_CACHE_QUANTIZATION=8 /
 *      MULTIMODAL=9 / FUNCTION_CALLING=10 / GUIDED_GENERATION=11 /
 *      SPECULATIVE_DECODING=12
 *
 * 提供本工程的 [supportedTasksBitmask] / [supportedInputModalitiesBitmask] /
 * [supportedOutputModalitiesBitmask] / [featuresBitmask] 常量,
 * 让 [LiteRTDescriptor.describeCapabilities] 单点引用,避免散落在各处。
 *
 * 长期方案:发布 aibox-plugin-api AAR 时把这些 ordinal 封装为常量类,
 * 让所有插件工程依赖 AAR 而非本地复制。
 */
object BitPositions {

    // === TaskType ordinal ===
    const val TASK_TEXT_GENERATION = 0
    const val TASK_TEXT_CLASSIFICATION = 1
    const val TASK_EMBEDDING = 2
    const val TASK_IMAGE_GENERATION = 3
    const val TASK_IMAGE_EDITING = 4
    const val TASK_IMAGE_UNDERSTANDING = 5
    const val TASK_AUDIO_TRANSCRIPTION = 6
    const val TASK_AUDIO_GENERATION = 7
    const val TASK_MULTIMODAL_UNDERSTANDING = 8
    const val TASK_MULTIMODAL_GENERATION = 9

    // === Modality ordinal ===
    const val MODALITY_TEXT = 0
    const val MODALITY_IMAGE = 1
    const val MODALITY_AUDIO = 2
    const val MODALITY_VIDEO = 3

    // === BackendFeature ordinal ===
    const val FEATURE_FLASH_ATTENTION = 0
    const val FEATURE_VULKAN_GPU = 1
    const val FEATURE_OPENCL_GPU = 2
    const val FEATURE_HEXAGON_NPU = 3
    const val FEATURE_TENSOR_GTP = 4
    const val FEATURE_MTK_APU = 5
    const val FEATURE_STREAMING = 6
    const val FEATURE_BATCHED_INFERENCE = 7
    const val FEATURE_KV_CACHE_QUANTIZATION = 8
    const val FEATURE_MULTIMODAL = 9
    const val FEATURE_FUNCTION_CALLING = 10
    const val FEATURE_GUIDED_GENERATION = 11
    const val FEATURE_SPECULATIVE_DECODING = 12

    // === SettingField type ordinal(对应宿主 SettingField.FieldType) ===
    const val SETTING_TYPE_SWITCH = 0
    const val SETTING_TYPE_DROPDOWN = 1
    const val SETTING_TYPE_SLIDER = 2
    const val SETTING_TYPE_INPUT = 3
    const val SETTING_TYPE_GROUP = 4

    // === LiteRT 后端能力聚合 bitmask ===
    // 与 plugin.json 的 capabilities 对齐;此处为权威值(供 AIDL 用),
    // plugin.json 是次要版本(供宿主 PackageManager 扫描前快速过滤)。
    //
    // 文本生成 + 多模态理解 + 文本向量化(embedding,Gemma 也支持)。
    val supportedTasksBitmask: Int =
        (1 shl TASK_TEXT_GENERATION) or
        (1 shl TASK_MULTIMODAL_UNDERSTANDING) or
        (1 shl TASK_EMBEDDING)

    // 输入模态:文本 + 图像(Siglip 编码)。
    val supportedInputModalitiesBitmask: Int =
        (1 shl MODALITY_TEXT) or (1 shl MODALITY_IMAGE)

    // 输出模态:仅文本(Gemma 不输出图像 / 音频)。
    val supportedOutputModalitiesBitmask: Int = (1 shl MODALITY_TEXT)

    // 特性:Hexagon NPU 加速 + 流式输出 + 多模态 + 函数调用(Gemma 通过 chat template 支持)。
    val featuresBitmask: Int =
        (1 shl FEATURE_HEXAGON_NPU) or
        (1 shl FEATURE_STREAMING) or
        (1 shl FEATURE_MULTIMODAL) or
        (1 shl FEATURE_FUNCTION_CALLING)
}
