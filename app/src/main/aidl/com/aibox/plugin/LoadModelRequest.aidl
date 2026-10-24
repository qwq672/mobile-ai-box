// LoadModelRequest.aidl
package com.aibox.plugin;

/**
 * 加载模型请求（AIDL parcelable）。
 *
 * - modelPath: 模型文件绝对路径。
 * - configJson: 推理配置 JSON（序列化后的 [com.aibox.domain.inference.InferenceConfigV2]）。
 *   用 JSON 字符串而非 parcelable 是因为 InferenceConfigV2 含 Map<String, Any> frameworkExtras，
 *   AIDL 不能直接序列化任意 Map；让 Kotlin 侧各自 JSON 序列化最稳妥。
 */
parcelable LoadModelRequest {
    String modelPath;
    String configJson;
}
