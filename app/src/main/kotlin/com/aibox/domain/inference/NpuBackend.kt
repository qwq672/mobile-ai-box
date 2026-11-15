package com.aibox.domain.inference

import com.aibox.infra.jni.NativeBridge
import com.aibox.infra.jni.StringCallback
import com.aibox.util.CpuInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * NPU 后端 — ggml-hexagon（高通 Hexagon HTP）。
 *
 * 不需要 QNN SDK：host 侧直接通过 FastRPC 把 `libggml-htp-vXX.so` 加载到 CDSP。
 * 但 APK 里必须带对应 DSP arch 的 skel（`assets/hexagon/`），否则加载会失败。
 * 如果 NPU 不可用，InferenceEngine 自动回退到 Vulkan/OpenCL/CPU。
 */
class NpuBackend(
    private val native: NativeBridge,
    private val mem: MemoryOptimizer,
    private val governor: ThermalGovernor,
    private val cpuInfo: CpuInfo,
    private val appContext: android.content.Context? = null
) : Backend {

    override val type = BackendType.NPU
    private var loaded = false
    private var stats = BackendStats(0f, 0, 0, 0L, 0, type)

    override fun isAvailable(): Boolean {
        return try {
            // assets 检查很便宜（只列目录）；没有 skel 就不用往下走。
            val hasSkel = appContext?.assets?.list("hexagon")?.any { it.endsWith(".so") } == true
            if (!hasSkel) return false
            // 必须先把 skel 部署到私有目录并写入 ADSP_LIBRARY_PATH 再探测：
            // hexagon 后端首次初始化拿不到 skel 会失败，ggml 会把「0 个 HTP 设备」
            // 的结果注册下去，之后即使补上路径也一直是 N/A。
            HexagonAssets.prepare(appContext)?.let { native.setAdspLibraryPath(it) }
            native.npuAvailable()
        } catch (e: Throwable) {
            false
        }
    }

    override suspend fun load(modelPath: String, config: InferenceConfig): Boolean = withContext(Dispatchers.Default) {
        if (!isAvailable()) return@withContext false
        // HTP 的 skel 必须先落到私有目录并写进 ADSP_LIBRARY_PATH，
        // 否则 FastRPC 在 DSP 侧找不到 kernel，加载必失败。
        val skelDir = HexagonAssets.prepare(appContext) ?: run {
            com.aibox.util.log.LogSystem.warn("aibox.hexagon", "没有可用的 HTP skel，NPU 后端跳过")
            return@withContext false
        }
        runCatching { native.setAdspLibraryPath(skelDir) }
            .onFailure { com.aibox.util.log.LogSystem.warn("aibox.hexagon", "setAdspLibraryPath 失败: ${it.message}") }
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
