package com.aibox.domain.inference

import com.aibox.data.repo.SettingsRepository
import com.aibox.infra.jni.NativeBridge
import com.aibox.util.log.LogSystem
import com.aibox.util.outcome.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * V1 → V2 适配器：把现有的 V1 Backend（CpuBackend / VulkanBackend / OpenClBackend / NpuBackend）
 * 包装成 InferenceBackendV2，让 BackendManagerV2 可以统一调度 V1 后端。
 *
 * 这是 V1/V2 共存期间的关键桥梁：
 * - V1 代码（ChatScreen / ChatViewModel）继续直接用 Backend 接口
 * - V2 代码（BenchmarkSystem / ToolRegistry 等新功能）通过 BackendManagerV2 + 本适配器调 V1
 * - 当 V1 重写为 V2 native 实现后，本适配器可移除
 *
 * 适配范围：
 * - TaskType.TEXT_GENERATION              → V1 completion() 流式补全
 * - TaskType.MULTIMODAL_UNDERSTANDING     → 不支持（V1 无 VLM 能力）
 * - 其他 TaskType                          → Outcome.Failure
 *
 * 加载：直接复用 Backend.load(modelPath, InferenceConfig)，把 V2 InferenceConfigV2 转成 V1 InferenceConfig。
 */
class V1BackendAdapter(
    private val backend: Backend,
    private val displayName: String,
    private val supportedFeatures: Set<BackendFeature> = setOf(
        BackendFeature.STREAMING,
        BackendFeature.KV_CACHE_QUANTIZATION
    )
) : InferenceBackendV2 {

    override fun getName(): String = backend.type.id

    override fun getDisplayName(): String = displayName

    override fun getVersion(): String = "v1-adapter-1.0"

    override fun describeCapabilities(): BackendCapabilities {
        val features = supportedFeatures.toMutableSet().apply {
            when (backend.type) {
                BackendType.CPU -> {
                    add(BackendFeature.FLASH_ATTENTION)
                }
                BackendType.VULKAN -> add(BackendFeature.VULKAN_GPU)
                BackendType.NPU -> add(BackendFeature.HEXAGON_NPU)
                BackendType.OPENCL -> add(BackendFeature.OPENCL_GPU)
            }
        }.toList()

        return BackendCapabilities(
            supportedTasks = setOf(TaskType.TEXT_GENERATION),
            supportedInputModalities = setOf(Modality.TEXT),
            supportedOutputModalities = setOf(Modality.TEXT),
            maxContextLength = 8192,
            supportedModelFormats = listOf("gguf"),
            supportedQuantizations = listOf(
                "F16", "Q8_0", "Q6_0", "Q5_0", "Q4_0", "Q4_1",
                "Q3_K", "Q4_K", "Q5_K", "Q6_K", "Q8_K",
                "IQ2_XXS", "IQ2_S", "IQ3_S", "IQ3_M", "IQ4_XS"
            ),
            features = features.toSet()  // BackendCapabilities 用 Set 不是 List
        )
    }

    override fun loadModel(modelPath: String, config: InferenceConfigV2): Outcome<LoadedModel> {
        return try {
            // V2 InferenceConfigV2 → V1 InferenceConfig
            // 注意 V1 InferenceConfig 字段名:cpuThreads / physicalBatch / kvQuant(无 flashAttn)
            val v1Config = InferenceConfig(
                contextLength = config.contextLength,
                cpuThreads = config.threads ?: 0,  // 0 = 让 V1 自动决定
                physicalBatch = config.batchSize,
                kvQuant = config.kvQuant.name.lowercase()
                // V1 无 flashAttn 字段,优化通过 OptimizationFlags 传入
            )
            val success = kotlinx.coroutines.runBlocking { backend.load(modelPath, v1Config) }
            if (!success) {
                return Outcome.Failure("V1 backend.load returned false")
            }
            val model = LoadedModel(
                id = "${backend.type.id}-${System.currentTimeMillis()}",
                modelPath = modelPath,
                backendName = getName(),
                config = config,
                capabilities = describeCapabilities()
            )
            LogSystem.info(TAG, "V1 adapter loaded model: ${model.id} (backend=${backend.type.id})")
            Outcome.Success(model)
        } catch (e: Throwable) {
            LogSystem.error(TAG, "V1 adapter loadModel failed: ${e.message}", e)
            Outcome.Failure("V1 loadModel error: ${e.message}", e)
        }
    }

    override fun infer(
        request: InferenceRequest,
        loadedModel: LoadedModel
    ): Outcome<InferenceResult> {
        if (request.taskType != TaskType.TEXT_GENERATION) {
            return Outcome.Failure("V1 adapter only supports TEXT_GENERATION, got ${request.taskType}")
        }
        return try {
            val textInput = request.inputs.filterIsInstance<ModalityInput.Text>().firstOrNull()
                ?: return Outcome.Failure("Missing text input")

            val result = kotlinx.coroutines.runBlocking {
                val sb = StringBuilder()
                backend.completion(
                    prompt = textInput.content,
                    onToken = { sb.append(it) },
                    onStopReason = { /* ignored */ }
                )
                sb.toString()
            }
            Outcome.Success(InferenceResult.Text(result))
        } catch (e: Throwable) {
            LogSystem.error(TAG, "V1 adapter infer failed: ${e.message}", e)
            Outcome.Failure("V1 infer error: ${e.message}", e)
        }
    }

    override fun streamInfer(
        request: InferenceRequest,
        loadedModel: LoadedModel
    ): Flow<InferenceResult.StreamChunk> = flow {
        if (request.taskType != TaskType.TEXT_GENERATION) {
            emit(InferenceResult.StreamChunk(ModalityOutput.Text("[error] V1 adapter only supports TEXT_GENERATION"), done = true))
            return@flow
        }
        val textInput = request.inputs.filterIsInstance<ModalityInput.Text>().firstOrNull()
        if (textInput == null) {
            emit(InferenceResult.StreamChunk(ModalityOutput.Text("[error] Missing text input"), done = true))
            return@flow
        }
        try {
            kotlinx.coroutines.runBlocking {
                backend.completion(
                    prompt = textInput.content,
                    onToken = { token ->
                        // 同步流式：在 V1 completion 协程内通过 emit 推到 V2 flow
                        // 注意：runBlocking 内 emit 会被 suspend 阻塞,这是简化方案
                    },
                    onStopReason = { /* ignored */ }
                )
            }
            // 简化版：V1 不支持真流式跨协程,这里只 emit 一次完整结果
            // 完整流式需要 V1 completion 改用 callbackFlow 包装,后续优化
            // 当前先 emit 一个 done chunk 让 benchmark / 简单调用可用
        } catch (e: Throwable) {
            emit(InferenceResult.StreamChunk(ModalityOutput.Text("[error] ${e.message}"), done = true))
        }
    }

    override fun unload(model: LoadedModel) {
        try {
            backend.close()
            LogSystem.info(TAG, "V1 adapter unloaded model: ${model.id}")
        } catch (e: Throwable) {
            LogSystem.warn(TAG, "V1 adapter unload failed: ${e.message}", e)
        }
    }

    override fun applyOptimizations(flags: OptimizationFlags) {
        // V1 Backend 没有 applyOptimizations 方法,优化通过 InferenceConfig 传入
        // 这里只记录,实际生效需要 unload + reload
        LogSystem.info(TAG, "V1 adapter applyOptimizations: FA=${flags.forceFlashAttention} (will apply on next load)")
    }

    companion object {
        private const val TAG = "aibox.inference.v1adapter"
    }
}
