package com.pocketllm.domain.inference

import com.pocketllm.infra.jni.NativeBridge
import com.pocketllm.infra.jni.StringCallback
import com.pocketllm.util.CpuInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CPU 后端 —— 兜底，所有设备可用。
 *
 * 内存优化点：
 * - native 侧用 mmap 加载权重
 * - KV cache 量化
 * - 自动线程数（按大核数，不抢占小核导致系统卡顿）
 */
class CpuBackend(
    private val native: NativeBridge,
    private val mem: MemoryOptimizer,
    private val governor: ThermalGovernor,
    private val cpuInfo: CpuInfo
) : Backend {

    override val type = BackendType.CPU
    private var loaded = false
    private var stats = BackendStats(0f, 0, 0, 0L, 0, type)

    override fun isAvailable() = true   // CPU 永远可用

    override suspend fun load(modelPath: String, config: InferenceConfig): Boolean = withContext(Dispatchers.Default) {
        val threads = config.effectiveThreads(cpuInfo.bigCores)
        val ok = native.llamaLoad(
            modelPath = modelPath,
            backend = type.id,
            nGpuLayers = 0,
            threads = threads,
            physicalBatch = config.physicalBatch,
            batch = config.batch,
            contextLength = config.contextLength,
            kvQuant = config.kvQuant
        )
        loaded = ok
        if (ok) {
            native.llamaSetSampler(
                temperature = config.temperature,
                topK = config.topK,
                topP = config.topP,
                repeatPenalty = config.repeatPenalty
            )
        }
        ok
    }

    override fun isLoaded() = loaded

    override suspend fun completion(
        prompt: String,
        onToken: (String) -> Unit,
        onStopReason: (String) -> Unit
    ) = withContext(Dispatchers.Default) {
        if (!loaded) { onStopReason("no_model"); return@withContext }
        // 包装成 StringCallback（无 generic Java interface），C++ 端 GetMethodID 100% 可靠
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
    override fun close() {
        native.llamaUnload()
        loaded = false
    }
    override fun stats(): BackendStats = stats.copy(
        tokensPerSecond = native.llamaTokensPerSecond(),
        contextUsed = native.llamaContextUsed(),
        contextMax = native.llamaContextMax(),
        peakMemoryMb = mem.peakMemoryMb()
    )
}

