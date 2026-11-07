package com.pocketllm.domain.inference

import com.pocketllm.infra.jni.NativeBridge
import com.pocketllm.infra.jni.StringCallback
import com.pocketllm.util.CpuInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OpenCL 后端 —— 老 GPU / 部分 Mali/Adreno 设备 fallback。
 *
 * 内存模型与 Vulkan 类似，区别在底层 backend 字符串不同。
 */
class OpenClBackend(
    private val native: NativeBridge,
    private val mem: MemoryOptimizer,
    private val governor: ThermalGovernor,
    private val cpuInfo: CpuInfo
) : Backend {

    override val type = BackendType.OPENCL
    private var loaded = false
    private var stats = BackendStats(0f, 0, 0, 0L, 0, type)

    override fun isAvailable(): Boolean = native.openclAvailable()

    override suspend fun load(modelPath: String, config: InferenceConfig): Boolean = withContext(Dispatchers.Default) {
        val threads = config.effectiveThreads(cpuInfo.bigCores)
        // nGpuLayers=0 (UI 显示「自动」) 时给一个保守默认值 12，
        // 同 VulkanBackend 理由；OpenCL 在 Mali/Adreno GPU 上 12 层比较稳定。
        val gpuLayers = if (config.nGpuLayers == 0) 12 else config.nGpuLayers
        val ok = native.llamaLoad(
            modelPath = modelPath,
            backend = type.id,
            nGpuLayers = gpuLayers,
            threads = threads,
            physicalBatch = config.physicalBatch,
            batch = config.batch,
            contextLength = config.contextLength,
            kvQuant = config.kvQuant
        )
        loaded = ok
        if (ok) native.llamaSetSampler(
            config.temperature, config.topK, config.topP, config.repeatPenalty
        )
        ok
    }

    override fun isLoaded() = loaded

    override suspend fun completion(
        prompt: String,
        onToken: (String) -> Unit,
        onStopReason: (String) -> Unit
    ) = withContext(Dispatchers.Default) {
        if (!loaded) { onStopReason("no_model"); return@withContext }
        val tokCb = object : StringCallback {
            override fun onValue(value: String) { onToken(value) }
        }
        val stopCb = object : StringCallback {
            override fun onValue(value: String) { onStopReason(value) }
        }
        native.llamaCompletion(prompt, tokCb, stopCb)
    }

    override suspend fun completionChat(
        messages: List<NativeBridge.ChatMsg>,
        thinkingMode: Boolean,
        onToken: (String) -> Unit,
        onStopReason: (String) -> Unit
    ) = withContext(Dispatchers.Default) {
        if (!loaded) { onStopReason("no_model"); return@withContext }
        val tokCb = object : StringCallback {
            override fun onValue(value: String) { onToken(value) }
        }
        val stopCb = object : StringCallback {
            override fun onValue(value: String) { onStopReason(value) }
        }
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
