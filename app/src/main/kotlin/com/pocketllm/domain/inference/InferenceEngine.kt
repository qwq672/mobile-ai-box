package com.pocketllm.domain.inference

import android.util.Log
import com.pocketllm.infra.jni.NativeBridge
import com.pocketllm.util.CpuInfo
import com.pocketllm.util.ThermalMonitor
import com.pocketllm.util.loge
import com.pocketllm.util.logi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 推理引擎统一入口。
 *
 * - 维护当前 Backend 实例，按 [InferenceConfig.backend] 切换。
 * - 把 [MemoryOptimizer] 与 [ThermalGovernor] 注入到当前 Backend。
 * - 暴露 stats 流，UI 实时显示。
 * - 后端回退顺序：用户选择 → NPU → Vulkan → OpenCL → CPU
 *   某一级后端加载失败时自动 fallback 到下一级，保证推理总可用。
 *
 * 单例，App 生命周期内唯一。线程安全。
 */
class InferenceEngine(
    private val nativeBridge: NativeBridge,
    private val thermalMonitor: ThermalMonitor,
    private val cpuInfo: CpuInfo,
    private val appContext: android.content.Context? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(BackendStats(0f, 0, 0, 0L, 0, BackendType.CPU))
    val stats: StateFlow<BackendStats> = _stats.asStateFlow()

    private val memoryOptimizer = MemoryOptimizer(nativeBridge)
    private val thermalGovernor = ThermalGovernor(thermalMonitor)

    @Volatile private var currentBackend: Backend? = null
    @Volatile private var currentModelPath: String? = null
    @Volatile private var effectiveBackend: BackendType = BackendType.CPU

    /**
     * 后端回退优先级链（从高到低）。
     * 用户选的 backend 放最前，后面按 NPU → Vulkan → OpenCL → CPU 排。
     * 加载失败时按顺序尝试下一个。
     */
    private fun fallbackChain(userChoice: BackendType): List<BackendType> {
        val chain = LinkedHashSet<BackendType>()
        chain.add(userChoice)
        // 按用户要求的顺序：NPU → Vulkan → OpenCL → CPU
        chain.add(BackendType.NPU)
        chain.add(BackendType.VULKAN)
        chain.add(BackendType.OPENCL)
        chain.add(BackendType.CPU)  // CPU 永远兜底
        return chain.toList()
    }

    /** 切换后端 + 加载模型。按回退链尝试，全部失败才报错。 */
    suspend fun loadModel(modelPath: String, config: InferenceConfig): Result<Unit> = withContext(Dispatchers.Default) {
        mutex.withLock {
            runCatching {
                val tuned = thermalGovernor.tuneConfig(config)
                val chain = fallbackChain(config.backend)
                logi("InferenceEngine: loadModel ${modelPath.substringAfterLast('/')} chain=$chain")

                var lastError: String? = null
                var loadedBackend: Backend? = null
                var loadedType: BackendType = BackendType.CPU

                for (type in chain) {
                    if (!isBackendAvailable(type)) {
                        logi("InferenceEngine: $type not available, skip")
                        continue
                    }
                    logi("InferenceEngine: trying $type ...")
                    runCatching {
                        // 每次尝试都先卸载旧的
                        currentBackend?.close()
                        currentBackend = null
                        val backend = createBackend(type)
                        if (!backend.isAvailable()) {
                            error("后端 $type isAvailable()=false")
                        }
                        val ok = backend.load(modelPath, tuned)
                        if (!ok) error("load() returned false")
                        loadedBackend = backend
                        loadedType = type
                        logi("InferenceEngine: $type load OK")
                    }.onFailure {
                        lastError = "$type: ${it.message}"
                        loge("InferenceEngine: $type failed: ${it.message}", it)
                    }
                    if (loadedBackend != null) break
                }

                if (loadedBackend == null) {
                    error("所有后端加载失败：$lastError")
                }

                currentBackend = loadedBackend
                currentModelPath = modelPath
                effectiveBackend = loadedType
                _state.value = _state.value.copy(
                    loaded = true,
                    modelPath = modelPath,
                    backend = loadedType,
                    config = tuned,
                    error = null
                )
                logi("InferenceEngine: loaded ${modelPath.substringAfterLast('/')} via ${loadedType}")
                Unit
            }.onFailure {
                Log.e("InferenceEngine", "load failed", it)
                _state.value = _state.value.copy(loaded = false, error = it.message)
            }
        }
    }

    suspend fun completion(
        prompt: String,
        onToken: (String) -> Unit,
        onStop: (String) -> Unit
    ) {
        val backend = currentBackend ?: run {
            onStop("no_model"); return
        }
        if (!backend.isLoaded()) { onStop("no_model"); return }
        thermalGovernor.beforeCompletion(backend)
        withInferenceBoost {
            try {
                backend.completion(prompt, onToken, onStop)
            } catch (t: Throwable) {
                Log.e("InferenceEngine", "completion threw", t)
                onStop("exception: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    suspend fun completionChat(
        messages: List<com.pocketllm.infra.jni.NativeBridge.ChatMsg>,
        thinkingMode: Boolean,
        onToken: (String) -> Unit,
        onStop: (String) -> Unit
    ) {
        val backend = currentBackend ?: run {
            onStop("no_model"); return
        }
        if (!backend.isLoaded()) { onStop("no_model"); return }
        thermalGovernor.beforeCompletion(backend)
        withInferenceBoost {
            try {
                backend.completionChat(messages, thinkingMode, onToken, onStop)
            } catch (t: Throwable) {
                Log.e("InferenceEngine", "completionChat threw", t)
                onStop("exception: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    /**
     * 推理期间的性能优化包装：
     * 1. 持有 PARTIAL_WAKE_LOCK，防止设备 doze 导致推理中断
     * 2. 提升当前线程优先级到 URGENT_DISPLAY，减少调度延迟
     */
    private suspend inline fun <T> withInferenceBoost(block: () -> T): T {
        // 获取 wake lock
        val pm = appContext?.getSystemService(android.content.Context.POWER_SERVICE)
                as? android.os.PowerManager
        var wakeLock: android.os.PowerManager.WakeLock? = null
        try {
            wakeLock = pm?.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "pocketllm:infer"
            )?.apply { acquire(60_000) } // 60 秒超时，避免泄漏
        } catch (_: Throwable) {}

        // 提升线程优先级
        val oldPriority = try {
            android.os.Process.getThreadPriority(android.os.Process.myTid())
        } catch (_: Throwable) { 0 }
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        } catch (_: Throwable) {}

        try {
            return block()
        } finally {
            try { android.os.Process.setThreadPriority(oldPriority) } catch (_: Throwable) {}
            try { wakeLock?.release() } catch (_: Throwable) {}
        }
    }

    fun interrupt() {
        currentBackend?.interrupt()
    }

    suspend fun unload() = mutex.withLock {
        currentBackend?.close()
        currentBackend = null
        currentModelPath = null
        memoryOptimizer.releaseAll()
        _state.value = EngineState()
    }

    /** 启动后 1s 一次刷新 stats（UI 用） */
    init {
        scope.launch {
            while (true) {
                val b = currentBackend
                if (b != null && b.isLoaded()) {
                    val s = b.stats()
                    _stats.value = s.copy(
                        thermalPercent = thermalMonitor.thermalPercent(),
                        backend = effectiveBackend
                    )
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun createBackend(type: BackendType): Backend = when (type) {
        BackendType.CPU    -> CpuBackend(nativeBridge, memoryOptimizer, thermalGovernor, cpuInfo)
        BackendType.VULKAN -> VulkanBackend(nativeBridge, memoryOptimizer, thermalGovernor, cpuInfo)
        BackendType.NPU    -> NpuBackend(nativeBridge, memoryOptimizer, thermalGovernor, cpuInfo)
        BackendType.OPENCL -> OpenClBackend(nativeBridge, memoryOptimizer, thermalGovernor, cpuInfo)
    }

    /** 探测某后端在当前设备/构建下是否可用（不创建实例，避免资源泄漏） */
    private fun isBackendAvailable(type: BackendType): Boolean = when (type) {
        BackendType.CPU    -> true
        BackendType.VULKAN -> runCatching { nativeBridge.vulkanAvailable() }.getOrDefault(false)
        BackendType.NPU    -> runCatching { nativeBridge.npuAvailable() }.getOrDefault(false)
        BackendType.OPENCL -> runCatching { nativeBridge.openclAvailable() }.getOrDefault(false)
    }
}

data class EngineState(
    val loaded: Boolean = false,
    val modelPath: String? = null,
    val backend: BackendType = BackendType.CPU,
    val config: InferenceConfig = InferenceConfig(),
    val error: String? = null
)
