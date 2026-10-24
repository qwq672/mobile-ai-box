// LoadModelResult.aidl
package com.aibox.plugin;

/**
 * 加载模型结果（AIDL parcelable）。
 *
 * - success: 是否成功；false 时 errorMessage 描述原因。
 * - modelHandle: 成功时返回的模型句柄字符串（插件自己定义格式），后续 infer / streamInfer / unload 用。
 *   对应 Kotlin 侧 [com.aibox.domain.inference.LoadedModel.id]；宿主侧 PluginBackendAdapter
 *   把句柄 + BackendCapabilities 等元信息组合成完整的 LoadedModel。
 * - errorMessage: 失败原因；成功时为空字符串。
 */
parcelable LoadModelResult {
    boolean success;
    String modelHandle;
    String errorMessage;
}
