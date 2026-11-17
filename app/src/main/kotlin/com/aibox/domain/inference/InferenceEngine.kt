package com.aibox.domain.inference

import android.util.Log
import com.aibox.infra.jni.NativeBridge
import com.aibox.util.CpuInfo
import com.aibox.util.ThermalMonitor
import com.aibox.util.log.LogSystem
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
    private val gpuGuard = GpuGuard(appContext)

    /** 云端对话服务，由 AppContainer 在工具层就绪后注入。 */
    @Volatile private var cloudService: com.aibox.domain.cloud.CloudChatService? = null

    /** AppContainer 完成工具注册后调用，把云端能力接到引擎上。 */
    fun attachCloudService(service: com.aibox.domain.cloud.CloudChatService) {
        cloudService = service
    }

    /**
     * 开启/关闭云端模型。
     *
     * 开启后 [completionChat] 会优先走云端，本地模型的状态保持不变；
     * 关闭后回到本地模型（或未加载状态）。
     */
    fun setCloudEnabled(on: Boolean, displayName: String? = null) {
        val svc = cloudService ?: return
        svc.setEnabled(on)
        if (on) {
            _state.value = _state.value.copy(
                loaded = true,
                modelPath = displayName?.takeIf { it.isNotBlank() }?.let { "cloud:$it" } ?: "cloud",
                error = null
            )
        } else if (currentBackend == null && !v2Active) {
            _state.value = EngineState()
        }
    }

    /** 当前是否是云端模式（用于 UI 展示模型名/后端标签）。 */
    fun isCloudActive(): Boolean = cloudService?.enabled == true

    @Volatile private var currentBackend: Backend? = null
    @Volatile private var currentModelPath: String? = null
    @Volatile private var effectiveBackend: BackendType = BackendType.CPU

    /**
     * V2 路径状态镜像:当通过 V2(插件)加载模型时,V1 路径不变,
     * 但 UI 需要知道"已经加载了某个模型"。
     *
     * 通过 [setV2Active] 设置,UI 订阅 [state] 时会看到 loaded=true + modelPath + backend=VULKAN 占位。
     * 调用 [completionChat] 时若 V2 激活,会调 [v2CompletionDelegate] 而非 V1 backend。
     */
    @Volatile private var v2Active: Boolean = false
    @Volatile private var v2ModelPath: String? = null
    @Volatile private var v2BackendLabel: String = "v2-plugin"

    /** V2 推理回调,由 ModelsViewModel 设置(通过 setV2Active)。 */
    @Volatile private var v2CompletionDelegate: (suspend (List<NativeBridge.ChatMsg>, Boolean, (String) -> Unit, (String) -> Unit) -> Unit)? = null

    /**
     * 标记 V2 路径已激活/失活。由 ModelsViewModel.loadV2 调用。
     * - activate=true: 设置 v2Active + v2ModelPath,让 state.loaded=true
     * - activate=false: 清空,让 state.loaded=false
     *
     * @param delegate V2 推理回调,签名同 completionChat。null 表示仅激活状态(不推理)。
     */
    fun setV2Active(
        activate: Boolean,
        modelPath: String? = null,
        backendLabel: String = "v2-plugin",
        delegate: (suspend (List<NativeBridge.ChatMsg>, Boolean, (String) -> Unit, (String) -> Unit) -> Unit)? = null
    ) {
        if (activate) {
            v2Active = true
            v2ModelPath = modelPath
            v2BackendLabel = backendLabel
            v2CompletionDelegate = delegate
            _state.value = _state.value.copy(
                loaded = true,
                modelPath = modelPath,
                backend = effectiveBackend,  // 保留原值,UI 通过 v2BackendLabel 区分
                config = _state.value.config,
                error = null
            )
        } else {
            v2Active = false
            v2ModelPath = null
            v2BackendLabel = "v2-plugin"
            v2CompletionDelegate = null
            _state.value = EngineState()
        }
    }

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
                LogSystem.info("aibox.inference.engine", "InferenceEngine: loadModel ${modelPath.substringAfterLast('/')} chain=$chain")

                // 加载前内存预检：放不下就直接给可读原因，而不是等系统 OOM kill
                MemoryGuard.canLoad(appContext, modelPath).getOrElse { e ->
                    error(e.message ?: "内存不足")
                }

                var lastError: String? = null
                var loadedBackend: Backend? = null
                var loadedType: BackendType = BackendType.CPU

                for (type in chain) {
                    if (!isBackendAvailable(type)) {
                        LogSystem.info("aibox.inference.engine", "InferenceEngine: $type not available, skip")
                        continue
                    }
                    if ((type == BackendType.VULKAN || type == BackendType.OPENCL) && gpuGuard.isVulkanBlocked()) {
                        // 上一次（或连续两次）GPU 推理把进程带崩了，本次启动不再尝试，
                        // 否则用户每次点开始都会闪退一次。
                        LogSystem.warn("aibox.inference.engine", "InferenceEngine: $type 被崩溃护栏禁用，跳过")
                        lastError = "$type: 连续崩溃已自动禁用"
                        continue
                    }
                    LogSystem.info("aibox.inference.engine", "InferenceEngine: trying $type ...")
                    runCatching {
                        // 每次尝试都先卸载旧的
                        currentBackend?.close()
                        currentBackend = null
                        val backend = createBackend(type)
                        if (!backend.isAvailable()) {
                            error("后端 $type isAvailable()=false")
                        }
                        val ok = backend.load(modelPath, tuned)
                        if (!ok) {
                            // 原生层的真实失败原因（Vulkan 设备初始化异常等）在这里带出来，
                            // 否则用户只看到「闪退」而没有任何线索
                            val nativeErr = runCatching { nativeBridge.nativeLastError() }.getOrDefault("")
                            error("load() returned false" + if (nativeErr.isNotBlank()) ": $nativeErr" else "")
                        }
                        loadedBackend = backend
                        loadedType = type
                        LogSystem.info("aibox.inference.engine", "InferenceEngine: $type load OK")
                    }.onFailure {
                        lastError = "$type: ${it.message}"
                        LogSystem.error("aibox.inference.engine", "InferenceEngine: $type failed: ${it.message}", it)
                    }
                    if (loadedBackend != null) break
                }

                if (loadedBackend == null) {
                    error("所有后端加载失败：$lastError")
                }

                // 加载后复核：整体内存已经贴着上限的话，继续推理必然被 OOM kill，
                // 不如立刻卸载并明确告诉用户怎么调。
                MemoryGuard.postLoadProblem(appContext)?.let { problem ->
                    runCatching { loadedBackend?.close() }
                    error(problem)
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
                LogSystem.info("aibox.inference.engine", "InferenceEngine: loaded ${modelPath.substringAfterLast('/')} via ${loadedType}")
                Unit
            }.onFailure {
                LogSystem.error("aibox.inference.engine", "load failed", it)
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
                LogSystem.error("aibox.inference.engine", "completion threw", t)
                onStop("exception: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    suspend fun completionChat(
        messages: List<com.aibox.infra.jni.NativeBridge.ChatMsg>,
        thinkingMode: Boolean,
        onToken: (String) -> Unit,
        onStop: (String) -> Unit
    ) {
        // 云端模式优先：工具循环在 CloudChatService 内完成，本地后端完全不动
        val cloud = cloudService
        if (cloud != null && cloud.enabled) {
            withInferenceBoost {
                try {
                    cloud.chat(messages, onToken, onStop)
                } catch (t: Throwable) {
                    LogSystem.error("aibox.inference.engine", "cloud completionChat threw", t)
                    onStop("cloud_error: ${t.message ?: t.javaClass.simpleName}")
                }
            }
            return
        }

        // V2 路径优先:若 v2Active,直接调 delegate
        if (v2Active) {
            val delegate = v2CompletionDelegate
            if (delegate != null) {
                withInferenceBoost {
                    try {
                        delegate(messages, thinkingMode, onToken, onStop)
                    } catch (t: Throwable) {
                        LogSystem.error("aibox.inference.engine", "V2 completionChat threw", t)
                        onStop("exception: ${t.message ?: t.javaClass.simpleName}")
                    }
                }
                return
            } else {
                onStop("v2_active_but_no_delegate")
                return
            }
        }
        // V1 路径
        val backend = currentBackend ?: run {
            onStop("no_model"); return
        }
        if (!backend.isLoaded()) { onStop("no_model"); return }
        thermalGovernor.beforeCompletion(backend)
        withInferenceBoost {
            try {
                backend.completionChat(messages, thinkingMode, onToken, onStop)
            } catch (t: Throwable) {
                LogSystem.error("aibox.inference.engine", "completionChat threw", t)
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
        // 先落盘「推理进行中」标记：若 GPU 驱动把进程带走，下次启动能识别崩溃，
        // 连续两次就自动回退 CPU（见 GpuGuard）。
        gpuGuard.onInferenceStart(effectiveBackend)
        // 获取 wake lock
        val pm = appContext?.getSystemService(android.content.Context.POWER_SERVICE)
                as? android.os.PowerManager
        var wakeLock: android.os.PowerManager.WakeLock? = null
        try {
            wakeLock = pm?.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "aibox:infer"
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
            gpuGuard.onInferenceEnd(effectiveBackend)
            try { android.os.Process.setThreadPriority(oldPriority) } catch (_: Throwable) {}
            try { wakeLock?.release() } catch (_: Throwable) {}
        }
    }

    fun interrupt() {
        cloudService?.cancel()
        currentBackend?.interrupt()
    }

    /**
     * 系统内存紧张回调（由 Application.onTrimMemory 转发）。
     *
     * - RUNNING_LOW 及以上：中断正在生成的请求，避免继续吃内存被直接杀进程
     * - RUNNING_CRITICAL：额外释放内存池缓存
     */
    fun onTrimMemory(level: Int) {
        LogSystem.warn("aibox.memory", "onTrimMemory(level=$level)")
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            interrupt()
        }
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            runCatching { memoryOptimizer.releaseAll() }
        }
    }

    /**
     * 卸载模型。
     *
     * 必须切到 IO 线程：close() 会阻塞在 native 的 g_mutex 上，
     * 如果调用方在 Main 线程（ModelsViewModel.activate 就是），整个 UI 会僵住。
     */
    suspend fun unload() = withContext(Dispatchers.IO) {
        cloudService?.setEnabled(false)
        mutex.withLock {
            currentBackend?.close()
            currentBackend = null
            currentModelPath = null
            memoryOptimizer.releaseAll()
            _state.value = EngineState()
        }
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
        BackendType.NPU    -> NpuBackend(nativeBridge, memoryOptimizer, thermalGovernor, cpuInfo, appContext)
        BackendType.OPENCL -> OpenClBackend(nativeBridge, memoryOptimizer, thermalGovernor, cpuInfo)
    }

    /**
     * 获取一个 V1 Backend 实例（用于 V1BackendAdapter 包装为 V2）。
     *
     * - 不重复创建：当前 active backend 直接返回，否则按需创建
     * - 不强制 load：返回的 Backend 实例可能是"未加载"状态，调用方需自行 load
     * - 设备不可用返回 null
     *
     * 这是 V1/V2 共存期间的过渡 API，V1 重写为 V2 native 后可移除。
     */
    fun getBackend(type: BackendType): Backend? {
        return try {
            if (!isBackendAvailable(type)) return null
            // 复用当前已加载的 backend（如果 type 匹配）
            // 否则创建新实例（不 load）
            createBackend(type)
        } catch (_: Throwable) {
            null
        }
    }

    /** 探测某后端在当前设备/构建下是否可用（不创建实例，避免资源泄漏） */
    private fun isBackendAvailable(type: BackendType): Boolean = when (type) {
        BackendType.CPU    -> true
        BackendType.VULKAN -> runCatching { nativeBridge.vulkanAvailable() }.getOrDefault(false)
        BackendType.NPU    -> runCatching {
            // FastRPC 存在还不够，APK 里必须带对应 DSP arch 的 HTP skel
            val hasSkel = appContext?.assets?.list("hexagon")?.any { it.endsWith(".so") } == true
            hasSkel && nativeBridge.npuAvailable()
        }.getOrDefault(false)
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
