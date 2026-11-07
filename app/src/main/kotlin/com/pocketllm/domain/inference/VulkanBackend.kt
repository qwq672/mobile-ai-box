package com.pocketllm.domain.inference

import com.pocketllm.infra.jni.NativeBridge
import com.pocketllm.infra.jni.StringCallback
import com.pocketllm.util.CpuInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Vulkan 后端 —— 几乎所有现代 Android 手机都可用。
 *
 * 内存优化点：
 * - KV cache 与 compute buffer 都在 GPU 显存（UMA 上是 DDR，但分配固定不抖动）
 * - nGpuLayers 控制有多少层走 GPU，剩余 CPU。用户可在 UI 调。
 * - pinned token buffer 避免每 token 一次 memcpy
 */
class VulkanBackend(
    private val native: NativeBridge,
    private val mem: MemoryOptimizer,
    private val governor: ThermalGovernor,
    private val cpuInfo: CpuInfo
) : Backend {

    override val type = BackendType.VULKAN
    private var loaded = false
    private var stats = BackendStats(0f, 0, 0, 0L, 0, type)

    override fun isAvailable(): Boolean = native.vulkanAvailable()

    override suspend fun load(modelPath: String, config: InferenceConfig): Boolean = withContext(Dispatchers.Default) {
        val threads = config.effectiveThreads(cpuInfo.bigCores)
        // nGpuLayers=0 (UI 显示「自动」) 时：
        // - 如果是 1-bit / ternary 模型（Bonsai Q1_0 等），全 offload 到 GPU（999）
        //   因为 CPU 1-bit kernel 极慢，GPU 快 10×
        // - 否则给保守默认 12（1.5B-3B 模型大部分层跑 GPU 而不爆显存）
        val is1BitModel = modelPath.contains("bonsai", ignoreCase = true) ||
                         modelPath.contains("q1_0", ignoreCase = true) ||
                         modelPath.contains("ternary", ignoreCase = true)
        val gpuLayers = when {
            config.nGpuLayers == 0 && is1BitModel -> 999  // 全 offload
            config.nGpuLayers == 0 -> 12
            else -> config.nGpuLayers
        }
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
