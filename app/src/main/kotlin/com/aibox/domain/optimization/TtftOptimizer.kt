package com.aibox.domain.optimization

import com.aibox.data.model.ModelInfo
import com.aibox.data.repo.ModelRepository
import com.aibox.domain.inference.BackendManagerV2
import com.aibox.domain.inference.InferenceBackendV2
import com.aibox.domain.inference.InferenceConfigV2
import com.aibox.domain.inference.InferenceRequest
import com.aibox.domain.inference.LoadedModel
import com.aibox.domain.inference.ModalityInput
import com.aibox.domain.inference.OptimizationFlags
import com.aibox.domain.inference.TaskType
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

/**
 * TTFT 优化器（P3）：通用代码层优化降低首 token 延迟。
 *
 * 通用优化（所有模型一视同仁）：
 * - **模型预加载**（[preloadOnAppStart]）：App 启动时异步预加载 lastUsed 模型，避开用户首次点击"开始对话"时的冷启动延迟。
 * - **加载后 dummy 推理预热**（[warmupAfterLoad]）：加载完成后立即跑 1 token 假推理，初始化所有内部状态
 *   （ggml graph 编译、tokenizer 单例、采样器对象、KV cache 内存布局），避免首次真实推理承担这些固定开销。
 * - **流式首 token 立即返回**（[streamFirstTokenImmediately]）：对 backend 的流式 Flow 做一层透传包装，
 *   语义上声明"首 token 一就绪就 push 不等批次"；后端可读 [OptimizationFlags.streamFirstTokenImmediately]
 *   决定是否启用 batched streaming。
 *
 * 通用约定（P3 核心约束）：
 * - **不针对任何特定模型做特殊优化**：Qwen / LFM / Hunyuan / SmolLM 全部走同一 [preloadOnAppStart] / [warmupAfterLoad] 路径。
 * - 优化都有开关（[OptimizationSettings.preloadModel] / [OptimizationSettings.warmupAfterLoad] / [OptimizationSettings.streamFirstTokenImmediately]），用户可关闭单个。
 * - **不依赖自动调优**：不动 threads / KV 类型 / batch 选择；这些由 [InferenceConfigV2] 与自动调优模块决定。
 *
 * @param backendManager V2 后端管理器，用于查找后端实例与已加载模型注册表
 * @param modelRepository 模型仓库，用于查 lastUsed 模型
 * @param settings 优化设置（注入而非引用 repository，避免循环依赖；调用方负责在设置变化时重新调用受影响方法）
 */
class TtftOptimizer(
    private val backendManager: BackendManagerV2,
    private val modelRepository: ModelRepository,
    private val settings: OptimizationSettings
) {
    private val tag = "aibox.opt.ttft"

    /**
     * App 启动时调用：异步预加载常用模型。
     *
     * 流程：
     * 1. 若 [OptimizationSettings.preloadModel] = false，直接 return（用户关闭了预加载）。
     * 2. 读 `modelRepository.lastUsed()`（suspend）；无 lastUsed 模型则 return。
     * 3. 选 backend：所有 V2 后端走相同代码路径，取第一个已注册的；无后端则 return。
     * 4. 异步调 `backend.loadModel(path, defaultConfig)`，不阻塞 UI；加载结果写入 BackendManagerV2 的 loaded registry。
     *
     * 不抛异常：所有失败路径仅 LogSystem.warn，不影响 App 启动。
     */
    suspend fun preloadOnAppStart() {
        if (!settings.preloadModel) {
            LogSystem.debug(tag, "preloadModel disabled by user, skip")
            return
        }
        val lastModel: ModelInfo = modelRepository.lastUsed() ?: run {
            LogSystem.info(tag, "no lastUsed model, skip preload")
            return
        }
        val backendName = backendManager.listBackends().firstOrNull()?.getName() ?: run {
            LogSystem.info(tag, "no V2 backend registered, skip preload")
            return
        }
        val backend: InferenceBackendV2 = backendManager.getBackend(backendName) ?: run {
            LogSystem.warn(tag, "backend '$backendName' not found (race), skip preload")
            return
        }

        LogSystem.info(tag, "preloading lastUsed model '${lastModel.name}' on backend '$backendName' (path=${lastModel.filePath})")
        withContext(Dispatchers.IO) {
            // 应用当前 TG 优化 flags（让后端在 loadModel 前知道 preallocateKV / prebuildSampler / etc）
            backend.applyOptimizations(settings.toFlags())
            val config = InferenceConfigV2(
                taskType = TaskType.TEXT_GENERATION,
                optimizations = settings.toFlags()
            )
            val outcome = runCatching { backend.loadModel(lastModel.filePath, config) }
                .getOrElse { return@withContext LogSystem.warn(tag, "loadModel threw: ${it.message}", it) }
            val loaded: LoadedModel = outcome.getOrNull() ?: run {
                LogSystem.warn(tag, "preload loadModel failed: ${outcome.reasonOrNull() ?: "unknown"}")
                return@withContext
            }
            // 注册到 BackendManagerV2 的 loaded registry，便于 EnergyOptimizer idle unload 与 CrashHandler 报告
            backendManager.trackLoaded(backendName, loaded)
            LogSystem.info(tag, "preload done: model='${lastModel.name}' backend='$backendName' loadedAt=${loaded.loadedAt}")
            // 加载后立即跑 dummy 推理预热
            warmupAfterLoad(backend, loaded)
        }
    }

    /**
     * 加载完成后立即跑 dummy 推理，初始化所有内部状态。
     *
     * 调用时机：
     * - 由 [preloadOnAppStart] 在加载成功后自动调用。
     * - 由 [OptimizationManager.onModelLoaded] 在用户主动加载新模型时也调用一次。
     *
     * 行为：
     * - 若 [OptimizationSettings.warmupAfterLoad] = false，跳过。
     * - 用 1 token 假推理（inputs = [" "]，maxTokens = 1）热身；纯通用路径，所有模型走相同代码。
     * - 任何异常 try-catch，仅记日志，不抛出（warmup 失败不应影响后续真实推理）。
     */
    suspend fun warmupAfterLoad(backend: InferenceBackendV2, model: LoadedModel) {
        if (!settings.warmupAfterLoad) {
            LogSystem.debug(tag, "warmupAfterLoad disabled by user, skip")
            return
        }
        LogSystem.info(tag, "warming up model '${model.id}' (1 token dummy infer)")
        withContext(Dispatchers.IO) {
            val warmupReq = InferenceRequest(
                taskId = "warmup_${model.id}",
                taskType = TaskType.TEXT_GENERATION,
                modelId = model.id,
                inputs = listOf(ModalityInput.Text(" ")),  // 单空格，所有 tokenizer 都至少产生 1 token
                config = model.config.copy(maxTokens = 1),
                stream = false,
                sessionId = null
            )
            val outcome = runCatching { backend.infer(warmupReq, model) }
                .getOrElse { return@withContext LogSystem.warn(tag, "warmup infer threw: ${it.message}", it) }
            outcome
                .onSuccess { LogSystem.info(tag, "warmup done for model '${model.id}'") }
                .onFailure { reason, cause ->
                    LogSystem.warn(tag, "warmup infer failed (non-fatal): $reason", cause)
                }
        }
    }

    /**
     * 流式输出首 token 立即返回（不等批次）。
     *
     * 这是 [InferenceBackendV2.streamInfer] 的通用包装：
     * - 透传所有 chunk（包括首 token）。
     * - 在第一个非 done chunk emit 之前打日志（便于诊断"首 token 是否被攒批"）。
     * - 后端实现可读 [OptimizationFlags.streamFirstTokenImmediately] 自行决定是否 batching；
     *   本方法仅作"声明意图"的统一入口，让所有调用方走相同代码路径，不针对任何模型特殊处理。
     *
     * 用法：`val stream = ttft.streamFirstTokenImmediately(backend.streamInfer(req, model))`
     */
    fun streamFirstTokenImmediately(upstream: Flow<com.aibox.domain.inference.InferenceResult.StreamChunk>):
            Flow<com.aibox.domain.inference.InferenceResult.StreamChunk> {
        if (!settings.streamFirstTokenImmediately) {
            // 用户关闭了"首 token 立即返回"，直接透传，不做任何额外日志
            return upstream
        }
        var seenFirst = false
        return upstream.onEach { chunk ->
            if (!seenFirst && !chunk.done) {
                seenFirst = true
                LogSystem.debug(tag, "first stream chunk received, emitting immediately (streamFirstTokenImmediately=true)")
            }
        }
    }
}
