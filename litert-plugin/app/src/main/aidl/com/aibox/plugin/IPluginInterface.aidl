// IPluginInterface.aidl
package com.aibox.plugin;

import com.aibox.plugin.PluginInfo;
import com.aibox.plugin.LoadModelRequest;
import com.aibox.plugin.LoadModelResult;
import com.aibox.plugin.InferRequest;
import com.aibox.plugin.InferResult;
import com.aibox.plugin.IStreamCallback;
import com.aibox.plugin.IPluginLogCallback;

/**
 * 插件主接口：宿主通过这个接口调用插件功能。
 *
 * 与 [IPluginDescriptor] 的关系：
 * - IPluginDescriptor 是"元信息"接口（只读，描述插件是什么 / 能做什么）。
 * - IPluginInterface 是"运行时"接口（加载模型 / 推理 / 卸载）。
 *
 * 生命周期：宿主在 [com.aibox.infra.plugin.PluginManager.enablePlugin] 时绑定到插件的
 * PluginService，通过 ServiceConnection 拿到 IBinder → asInterface() 转为本接口。
 * 调用顺序：
 * 1. setLogCallback 注入日志回调（让插件日志统一进宿主 LogSystem）。
 * 2. applyOptimizations 配置优化开关（可选）。
 * 3. loadModel 加载模型，拿到 modelHandle。
 * 4. infer / streamInfer 调用推理。
 * 5. unload 释放模型。
 *
 * 宿主侧用 [com.aibox.infra.plugin.PluginBackendAdapter] 把本接口适配为
 * [com.aibox.domain.inference.InferenceBackendV2]（V2 抽象层）。
 */
interface IPluginInterface {
    PluginInfo getPluginInfo();

    // === 模型加载 ===
    /**
     * 加载模型。
     *
     * @param request 加载请求（含模型路径 + 配置 JSON）。
     * @return 成功返回 LoadModelResult{success=true, modelHandle=...}；
     *         失败返回 LoadModelResult{success=false, errorMessage=...}。
     *         **本方法不抛 RemoteException 给业务层**：跨进程失败已封装到 result 字段。
     */
    LoadModelResult loadModel(in LoadModelRequest request);

    /**
     * 释放模型。
     *
     * @param modelHandle loadModel 返回的模型句柄。
     * 调用后该句柄不可再用；重复调用应安全（幂等）。
     */
    void unload(String modelHandle);

    // === 同步推理 ===
    /**
     * 一次性推理。
     *
     * @param request 推理请求。
     * @param modelHandle 加载时返回的模型句柄。
     * @return 成功返回 InferResult{success=true, resultJson=...}；
     *         失败返回 InferResult{success=false, errorMessage=...}。
     */
    InferResult infer(in InferRequest request, in String modelHandle);

    // === 流式推理 ===
    /**
     * 流式推理：用 callback 接收 chunks。
     *
     * 调用后插件应立即开始推理，每产出一块调一次 callback.onChunk(partialJson, done)；
     * 推理结束或出错时分别调 done=true 或 onError(reason)。
     * 本方法本身立即返回，推理在插件进程后台进行。
     */
    void streamInfer(in InferRequest request, in String modelHandle, in IStreamCallback callback);

    // === 优化开关 ===
    /**
     * 应用优化开关到插件（应在 loadModel 之前调用）。
     *
     * @param flags 优化开关 Map，键名见 [com.aibox.domain.inference.OptimizationFlags] 字段名，
     *             值为 Boolean。插件按自身能力尽力执行，不支持的字段静默忽略。
     */
    void applyOptimizations(in Map<String, String> flags);

    // === 日志回调注入 ===
    /**
     * 注入日志回调，让插件把日志发回宿主 LogSystem。
     *
     * 建议在 enablePlugin 后立即调用；插件调用 onLog 时，宿主桥到
     * [com.aibox.util.log.LogSystem.log]。
     */
    void setLogCallback(in IPluginLogCallback callback);
}
