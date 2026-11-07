package com.pocketllm.domain.inference

import com.pocketllm.infra.jni.NativeBridge
import com.pocketllm.infra.jni.StringCallback
import com.pocketllm.util.CpuInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * NPU 后端 —— 基于 ggml-hexagen（高通 QNN HTP / 联发科 APU）。
 *
 * 内存优化点（重点）：
 * - NPU 推理本身极快，但「数据在 NPU↔CPU 之间往返」是发烫大头
 * - 因此把尽可能多的层 offload 到 NPU（nGpuLayers 大），减少切换
 * - NPU 内部权重常驻 HTP memory，避免每次推理重新 prepare graph
 * - 仅在 antiRollback=true 时启用最高性能档，否则配合 ThermalGovernor 软降
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

    /** NPU 是否可用：检查 libQnnHtp.so / ggml-hexagen .so 是否加载成功 */
    override fun isAvailable(): Boolean = native.npuAvailable()

    override suspend fun load(modelPath: String, config: InferenceConfig): Boolean = withContext(Dispatchers.Default) {
        // NPU 默认全 offload，除非用户显式设了层数
        val gpuLayers = if (config.nGpuLayers == 0) Int.MAX_VALUE / 2 else config.nGpuLayers
        val threads = config.effectiveThreads(cpuInfo.bigCores)
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
