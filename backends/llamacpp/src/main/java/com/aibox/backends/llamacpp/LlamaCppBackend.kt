package com.aibox.backends.llamacpp

import com.aibox.core.logger.AILogger
import com.aibox.core.common.Outcome
import com.aibox.core.inference.BackendConfig
import com.aibox.core.inference.BackendMetrics
import com.aibox.core.inference.InferenceBackend
import com.aibox.core.inference.InferenceRequest
import com.aibox.core.inference.InferenceResult
import com.aibox.core.inference.InferCallbacks
import com.aibox.core.logger.LogModule
import com.aibox.core.logger.info
import com.aibox.core.logger.warn

/**
 * llama.cpp 后端 — 骨架实现。
 *
 * 真实实现将 JNI 调用 llama.cpp 的 native 库，已构建的 .so 放在 jniLibs/{arm64-v8a,x86_64}/ 下。
 *
 * 当前 stub 状态：所有方法返回未加载状态，便于宿主骨架编译运行。
 * 集成步骤（M1 里程碑）：
 * 1. git submodule add llama.cpp
 * 2. 编写 android-jni/CMakeLists.txt
 * 3. ./gradlew :backends:llamacpp:externalNativeBuildDebug
 * 4. 实现 [load]/[inferOnce]/[inferStreamed] 通过 JNI 调用 llama_context
 *
 * NPU 支持：llama.cpp 本身不直接用 NPU，但通过 Qualcomm 的 QNN delegate（M3 实现后接入）
 */
public class LlamaCppBackend(
    private val logger: AILogger,
) : InferenceBackend {

    override val backendId: String = "llama.cpp@0.1.0"
    override val displayName: String = "llama.cpp (本地 CPU/GPU)"
    override val capabilities: Set<InferenceBackend.CapabilityKind> = setOf(
        InferenceBackend.CapabilityKind.CHAT,
        InferenceBackend.CapabilityKind.EMBEDDING,
    )

    @Volatile private var loaded: Boolean = false

    override suspend fun load(config: BackendConfig): Outcome<Unit> {
        logger.info(LogModule.INFERENCE, "llama.cpp", "load: ${config.modelPath}")
        // TODO: JNI 调用 llama_model_load
        loaded = true
        return Outcome.Success(Unit)
    }

    override suspend fun unload(): Outcome<Unit> {
        loaded = false
        return Outcome.Success(Unit)
    }

    override suspend fun warmup(): Outcome<Unit> {
        if (!loaded) logger.warn(LogModule.INFERENCE, "llama.cpp", "warmup called but not loaded")
        return Outcome.Success(Unit)
    }

    override suspend fun inferOnce(request: InferenceRequest): Outcome<InferenceResult> {
        // TODO: JNI 调用 llama_decode + llama_sampler
        val result = InferenceResult(
            text = "[stub] llama.cpp not yet implemented",
            npuUsed = false,
        )
        return Outcome.Success(result)
    }

    override suspend fun inferStreamed(
        request: InferenceRequest,
        callbacks: InferCallbacks,
    ): Outcome<InferenceResult> {
        // TODO: 流式调用 llama_token_callback
        callbacks.onToken("[stub] ")
        callbacks.onToken("llama.cpp ")
        callbacks.onToken("not yet implemented")
        val result = InferenceResult(text = "[stub] llama.cpp not yet implemented")
        callbacks.onComplete(result)
        return Outcome.Success(result)
    }

    override fun cancel() {
        // TODO: 通过 native handle 中断当前推理
    }

    override fun metrics(): BackendMetrics = BackendMetrics(loaded = loaded)
}
