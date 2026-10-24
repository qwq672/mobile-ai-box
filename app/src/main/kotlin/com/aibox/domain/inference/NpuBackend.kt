package com.aibox.domain.inference

import com.aibox.infra.jni.NativeBridge
import com.aibox.infra.jni.StringCallback
import com.aibox.util.CpuInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * NPU 后端 — ggml-hexagon（高通 QNN HTP）。
 * 
 * 需要 QNN SDK 编译，运行时检查 libQnnHtp.so 是否存在。
 * 如果 NPU 不可用，InferenceEngine 自动回退到 Vulkan/OpenCL/CPU。
 */
class NpuBackend(
    private val native: NativeBridge,
    private val mem: MemoryOptimizer,
    private val governor: ThermalGovernor,
    private val cpuInfo: CpuInfo
) : Backend {

    override val type = BackendType.NPU
    private var loaded = false
    private var stats = BackendStats(0f, 0, 0, 0L, 0, type)

    override fun isAvailable(): Boolean {
        return try { native.npuAvailable() } catch (e: Throwable) { false }
    }

    override suspend fun load(modelPath: String, config: InferenceConfig): Boolean = withContext(Dispatchers.Default) {
        if (!isAvailable()) return@withContext false
        val gpuLayers = if (config.nGpuLayers == 0) Int.MAX_VALUE / 2 else config.nGpuLayers
        val threads = config.effectiveThreads(cpuInfo.bigCores)
        val ok = try {
            native.llamaLoad(
                modelPath = modelPath,
                backend = type.id,
                nGpuLayers = gpuLayers,
                threads = threads,
                physicalBatch = config.physicalBatch,
                batch = config.batch,
                contextLength = config.contextLength,
                kvQuant = config.kvQuant
            )
        } catch (e: Throwable) { false }
        loaded = ok
        if (ok) native.llamaSetSampler(config.temperature, config.topK, config.topP, config.repeatPenalty)
        ok
    }

    override fun isLoaded() = loaded

    override suspend fun completion(prompt: String, onToken: (String) -> Unit, onStopReason: (String) -> Unit) = withContext(Dispatchers.Default) {
        if (!loaded) { onStopReason("no_model"); return@withContext }
        val tokCb = object : StringCallback { override fun onValue(value: String) { onToken(value) } }
        val stopCb = object : StringCallback { override fun onValue(value: String) { onStopReason(value) } }
        native.llamaCompletion(prompt, tokCb, stopCb)
    }

    override suspend fun completionChat(messages: List<NativeBridge.ChatMsg>, thinkingMode: Boolean, onToken: (String) -> Unit, onStopReason: (String) -> Unit) = withContext(Dispatchers.Default) {
        if (!loaded) { onStopReason("no_model"); return@withContext }
        val tokCb = object : StringCallback { override fun onValue(value: String) { onToken(value) } }
        val stopCb = object : StringCallback { override fun onValue(value: String) { onStopReason(value) } }
        native.llamaCompletionChat(messages.toTypedArray(), thinkingMode, tokCb, stopCb)
    }

    override fun interrupt() = native.llamaInterrupt()
    override fun close() { native.llamaUnload(); loaded = false }
    override fun stats(): BackendStats = stats.copy(
        tokensPerSecond = native.llamaTokensPerSecond(),
        contextUsed = native.llamaContextUsed(),
        contextMax = native.llamaContextMax(),
        peakMemoryMb = mem.peakMemoryMb()
    )
}
