// InferRequest.aidl
package com.aibox.plugin;

/**
 * 推理请求（AIDL parcelable）。
 *
 * - taskId: 请求 UUID，对应 [com.aibox.domain.inference.InferenceRequest.taskId]。
 * - taskType: 任务类型，用 int 表达 [com.aibox.domain.inference.TaskType] 序数。
 * - modelId: 模型 ID，对应 [com.aibox.domain.inference.InferenceRequest.modelId]。
 * - inputJsons: 多模态输入列表，每项是 [com.aibox.domain.inference.ModalityInput] 的 JSON 序列化字符串。
 *   AIDL 不能直传 sealed class，故每项用 JSON 表达；插件侧各自反序列化。
 * - configJson: 推理配置 JSON（覆盖模型加载时的默认 InferenceConfigV2）。
 * - stream: 是否流式输出；true 走 IPluginInterface.streamInfer + IStreamCallback，false 走 infer。
 * - sessionId: 推理会话 ID（可空），用于关联会话独立日志。
 */
parcelable InferRequest {
    String taskId;
    int taskType;
    String modelId;
    List<String> inputJsons;
    String configJson;
    boolean stream;
    String sessionId;
}
