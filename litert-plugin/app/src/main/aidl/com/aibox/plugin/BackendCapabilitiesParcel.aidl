// BackendCapabilitiesParcel.aidl
package com.aibox.plugin;

/**
 * 后端能力声明（AIDL parcelable）。
 *
 * 跨进程传递的"插件能力",对应 Kotlin 侧 [com.aibox.domain.inference.BackendCapabilities]。
 *
 * - 支持任务 / 输入模态 / 输出模态均用 bitmask 表达（每位对应枚举序数）
 * - features 同样用 bitmask 表达，对应 [com.aibox.domain.inference.BackendFeature] 序数
 * - 不支持 Set<T>（AIDL 不支持），用 bitmask 是最紧凑的传递方式
 */
parcelable BackendCapabilitiesParcel {
    int supportedTasksBitmask;
    int supportedInputModalitiesBitmask;
    int supportedOutputModalitiesBitmask;
    int maxContextLength;
    int maxImageResolution;
    List<String> supportedModelFormats;
    List<String> supportedQuantizations;
    int featuresBitmask;
}
