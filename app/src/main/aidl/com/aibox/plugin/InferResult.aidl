// InferResult.aidl
package com.aibox.plugin;

/**
 * 推理结果（AIDL parcelable）。
 *
 * - success: 是否成功；false 时 errorMessage 描述原因。
 * - resultJson: 成功时返回的 [com.aibox.domain.inference.InferenceResult] JSON 序列化字符串。
 *   AIDL 不能直传 sealed class（含 Text/Image/Audio/Multimodal/Embedding 子类），
 *   故用 JSON 字符串表达；宿主侧 PluginBackendAdapter 反序列化为具体子类。
 * - errorMessage: 失败原因；成功时为空字符串。
 */
parcelable InferResult {
    boolean success;
    String resultJson;
    String errorMessage;
}
