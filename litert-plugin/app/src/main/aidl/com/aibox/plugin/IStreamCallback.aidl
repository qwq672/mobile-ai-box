// IStreamCallback.aidl
package com.aibox.plugin;

/**
 * 流式推理回调接口：插件用此把推理过程逐 token 回传宿主。
 *
 * 调用顺序：
 * 1. 多次 onChunk(partialJson, done=false)，每次 partialJson 是一个
 *    [com.aibox.domain.inference.ModalityOutput] 的 JSON 序列化字符串。
 * 2. 最终一次 onChunk("", done=true) 表示流结束。
 * 3. 任何异常或中断通过 onError(reason) 通知宿主；调用后插件不应再回调本接口。
 *
 * 回调线程模型：插件可能在 binder 工作线程中回调，宿主侧不阻塞。
 * 宿主侧 [com.aibox.infra.plugin.PluginBackendAdapter] 把回调桥接到 Kotlin Flow。
 */
interface IStreamCallback {
    void onChunk(String partialJson, boolean done);
    void onError(String reason);
}
