package com.aibox.domain.inference

import com.aibox.util.outcome.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * 推理后端统一接口（v2）。
 *
 * 与 V1 [Backend] 并存：新功能（多模态 / 多任务 / 多框架）用 V2，旧功能（LLM 聊天）继续用 V1。
 * 后续可逐步把 V1 实现包装成 V2 适配器，统一到 V2 入口。
 *
 * 设计原则：
 * 1. **零异常约定**：所有可失败方法返回 [Outcome]，不抛异常。调用方用 `onFailure` 处理错误。
 * 2. **不可变**：所有 data class + `copy()`；运行期不改 [LoadedModel] 字段。
 * 3. **多任务 / 多模态**：通过 [InferenceRequest.taskType] + [ModalityInput] 子类组合表达。
 * 4. **能力声明**：[describeCapabilities] 让 [BackendManagerV2] 做路由决策。
 * 5. **生命周期**：[loadModel] → [infer] / [streamInfer] → [unload]，与 V1 一致。
 * 6. **优化开关**：[applyOptimizations] 在 [loadModel] 之前调用，后端据此配置预加载策略。
 */
interface InferenceBackendV2 {

    /** 后端唯一名称（机器可读），如 "llamaCpp" / "litert" / "mlc"。用作 [BackendManagerV2] 的注册键。 */
    fun getName(): String

    /** 用户可见的显示名，如 "llama.cpp (内置)" / "LiteRT-LM"。 */
    fun getDisplayName(): String

    /** 版本号，如 "b3950" / "0.1.0"。用于能力差异诊断。 */
    fun getVersion(): String

    /** 能力声明，让 [BackendManagerV2] 路由请求。不可变，运行期不变。 */
    fun describeCapabilities(): BackendCapabilities

    /**
     * 加载模型。
     *
     * @param modelPath 模型文件绝对路径。
     * @param config 推理配置；包含 [InferenceConfigV2.optimizations] 与 [InferenceConfigV2.frameworkExtras]。
     * @return 成功返回 [LoadedModel]；失败返回 [Outcome.Failure]，reason 描述原因（如"模型格式不支持"）。
     */
    fun loadModel(modelPath: String, config: InferenceConfigV2): Outcome<LoadedModel>

    /**
     * 同步推理（一次性返回完整结果）。
     *
     * 调用方需保证 [request] 的 taskType 与 [loadedModel] 的能力声明一致；不一致时后端返回 Failure。
     *
     * @param request 推理请求。
     * @param loadedModel 由 [loadModel] 返回的模型句柄。
     * @return 成功返回 [InferenceResult]（可能是 Text / Image / Audio / Multimodal / Embedding）；失败返回 Failure。
     */
    fun infer(request: InferenceRequest, loadedModel: LoadedModel): Outcome<InferenceResult>

    /**
     * 流式推理。
     *
     * 返回一个冷流 [Flow]，订阅时开始推理，每次产出一个 [InferenceResult.StreamChunk]，
     * 最后一块的 [InferenceResult.StreamChunk.done] = true。中途取消（cancel）即中断推理。
     *
     * 注：本方法本身不抛异常；如加载 / 推理失败，flow 应通过异常或 done chunk 体现，
     * 但 [InferenceBackendV2] 的零异常约定要求：错误通过 emit 一个 done=true 且 partial 为
     * [ModalityOutput.Text] 描述错误原因的 chunk 体现（或后续扩展专用 ErrorChunk，本版本不引入）。
     */
    fun streamInfer(request: InferenceRequest, loadedModel: LoadedModel): Flow<InferenceResult.StreamChunk>

    /** 释放模型资源；调用后 [LoadedModel] 不可再用。幂等（重复调用安全）。 */
    fun unload(model: LoadedModel)

    /**
     * 应用优化开关到后端（在 [loadModel] 之前调用）。
     *
     * 后端据此配置预加载策略、KV cache 分配方式、采样器构建时机等。
     * 不支持的开关静默忽略，不报错。
     */
    fun applyOptimizations(flags: OptimizationFlags)
}

/**
 * 已加载的模型句柄（v2）。
 *
 * 由 [InferenceBackendV2.loadModel] 成功时返回；调用方持此句柄调 [InferenceBackendV2.infer] /
 * [InferenceBackendV2.streamInfer]，最后调 [InferenceBackendV2.unload]。
 *
 * 不可变 data class；运行期不修改字段。如需更新配置（如调温度），通过新的 [InferenceRequest.config] 传入，
 * 后端按需重新构建采样器，不改本句柄。
 *
 * @property id 实例 ID（UUID）；同一模型可加载多份时各不相同。
 * @property modelPath 模型文件绝对路径。
 * @property backendName 加载它的后端名称，与 [InferenceBackendV2.getName] 一致。
 * @property config 加载时使用的配置（含 [InferenceConfigV2.optimizations] 与 [InferenceConfigV2.frameworkExtras]）。
 * @property capabilities 该后端声明的能力，与 [InferenceBackendV2.describeCapabilities] 一致；冗余存一份便于路由后无需回查。
 * @property loadedAt 加载完成时间戳（System.currentTimeMillis）。
 */
data class LoadedModel(
    val id: String,
    val modelPath: String,
    val backendName: String,
    val config: InferenceConfigV2,
    val capabilities: BackendCapabilities,
    val loadedAt: Long = System.currentTimeMillis()
)
