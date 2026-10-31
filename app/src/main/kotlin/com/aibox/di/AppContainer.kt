package com.aibox.di

import android.content.Context
import com.aibox.BuildConfig
import com.aibox.data.repo.ChatRepository
import com.aibox.data.repo.ModelRepository
import com.aibox.data.repo.SettingsRepository
import com.aibox.domain.audio.AudioProviderRegistry
import com.aibox.domain.audio.SystemAsrProvider
import com.aibox.domain.audio.SystemTtsProvider
import com.aibox.domain.download.DownloadManager
import com.aibox.domain.download.HfMirrorSource
import com.aibox.domain.download.HuggingFaceSource
import com.aibox.domain.download.ModelScopeSource
import com.aibox.domain.download.ModelSource
import com.aibox.domain.inference.BackendManagerV2
import com.aibox.domain.inference.InferenceEngine
import com.aibox.domain.inference.ThermalGovernor
import com.aibox.domain.optimization.OptimizationManager
import com.aibox.domain.optimization.OptimizationSettingsRepository
import com.aibox.domain.search.SearchEngine
import com.aibox.domain.search.SearchEngineFactory
import com.aibox.domain.shortcut.ShortcutRepository
import com.aibox.domain.tool.ToolBootstrap
import com.aibox.domain.tool.ToolExecutor
import com.aibox.domain.tool.ToolRegistry
import com.aibox.domain.tool.ToolSettingsRepository
import com.aibox.infra.benchmark.BenchmarkSystem
import com.aibox.infra.jni.NativeBridge
import com.aibox.infra.plugin.PluginManager
import com.aibox.util.CpuInfo
import com.aibox.util.ThermalMonitor
import com.aibox.util.log.LogLevel
import com.aibox.util.log.LogSystem

/**
 * 手写 DI 容器（不引入 Koin/Dagger 减小体积）。
 * App 启动时构造一次，全局共享。
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val thermalMonitor: ThermalMonitor = ThermalMonitor().also {
        ThermalMonitor.appContext = appContext
        it.start()
    }

    val cpuInfo: CpuInfo = CpuInfo
    val nativeBridge: NativeBridge = NativeBridge()

    val inferenceEngine: InferenceEngine = InferenceEngine(
        nativeBridge = nativeBridge,
        thermalMonitor = thermalMonitor,
        cpuInfo = cpuInfo,
        appContext = appContext
    )

    val modelRepository: ModelRepository = ModelRepository(appContext)
    val settingsRepository: SettingsRepository = SettingsRepository(appContext)
    val chatRepository: ChatRepository = ChatRepository(appContext)
    val downloadManager: DownloadManager = DownloadManager(appContext)

    /** 三下载源 */
    val downloadSources: List<ModelSource> = listOf(
        HfMirrorSource(),     // 默认放第一
        HuggingFaceSource(),
        ModelScopeSource()
    )

    fun sourceById(id: String): ModelSource =
        downloadSources.first { it.id == id }

    // ===================================================
    // E：快捷方式持久化（主页 Hybrid 设计）
    // ===================================================

    /**
     * 快捷方式持久化仓库（E 主页 Hybrid 设计）。
     *
     * 用独立 DataStore 文件名空间 `shortcut_store`，存储"用户预存的功能入口配置"。
     * 主页"快捷方式"区横滑列表 + 编辑器通过本仓库读写；详见 [ShortcutRepository] 类注释。
     */
    val shortcutRepository: ShortcutRepository = ShortcutRepository(appContext)

    // ===================================================
    // P1.1 / P1.2 / P2：V2 后端 + 插件 + 基准测试系统
    // ===================================================

    /**
     * V2 后端管理器（P1.1 落地）。
     *
     * 当前为空容器；启动时可注册内置 V2 后端（如 llama.cpp 适配器）或插件后端（P1.2 PluginManager）。
     * 与 V1 [InferenceEngine] 并存，互不干扰。
     *
     * 启动时自动注册 V1 → V2 适配器（[com.aibox.domain.inference.V1BackendAdapter]），
     * 让 V1 CPU / Vulkan / OpenCL / NPU 后端都能被 BackendManagerV2 调用，
     * 这样 V2 基准测试系统能直接跑现有 V1 后端,无需等 V1 重写为 V2 native。
     */
    val backendManager: BackendManagerV2 = BackendManagerV2().also { bm ->
        // 注册 V1 → V2 适配器
        for (type in com.aibox.domain.inference.BackendType.entries) {
            try {
                val v1Backend = inferenceEngine.getBackend(type)
                if (v1Backend != null && v1Backend.isAvailable()) {
                    val adapter = com.aibox.domain.inference.V1BackendAdapter(
                        backend = v1Backend,
                        displayName = type.displayNameZh
                    )
                    val descriptor = com.aibox.domain.inference.V1BackendDescriptor(type)
                    bm.registerBackend(adapter, descriptor)
                }
            } catch (e: Throwable) {
                com.aibox.util.log.LogSystem.warn(
                    "aibox.di",
                    "Failed to register V1 adapter for ${type.id}: ${e.message}",
                    e
                )
            }
        }
    }

    /**
     * 插件生命周期管理器（P1.2 落地）。
     *
     * 在宿主启动时构造一次：扫描已安装的 AIBox 插件 APK，按需在用户启用时绑定 service、
     * 注册到 [backendManager]。构造时不主动扫描；扫描在 [PluginManager.discoverPlugins] 被显式调用时触发。
     *
     * 日志回调把插件的 (level, tag, msg) 转发到宿主 [LogSystem]，统一进 ring buffer + 文件 sink。
     * `currentAiboxVersion` 取 [BuildConfig.VERSION_NAME]，用于 [com.aibox.infra.plugin.PluginCompatibilityChecker] 的 minAiboxVersion 校验。
     */
    val pluginManager: PluginManager = PluginManager(
        context = appContext,
        backendManager = backendManager,
        logCallback = { level, tag, msg -> LogSystem.log(LogLevel.fromAndroid(level), tag, msg) },
        currentAiboxVersion = BuildConfig.VERSION_NAME
    )

    /**
     * 基准测试系统统一入口（P2）。
     *
     * 持有 [com.aibox.infra.benchmark.EnvironmentMonitor] /
     * [com.aibox.infra.benchmark.EnergyProfiler] /
     * [com.aibox.infra.benchmark.BasicBenchmark] /
     * [com.aibox.infra.benchmark.AdvancedBenchmark] /
     * [com.aibox.infra.benchmark.BackendComparison] /
     * [com.aibox.infra.benchmark.BenchmarkStorage]，
     * 供 [com.aibox.ui.benchmark.BenchmarkViewModel] 调用。
     */
    val benchmarkSystem: BenchmarkSystem = BenchmarkSystem(
        context = appContext,
        thermalMonitor = thermalMonitor,
        backendManager = backendManager
    )

    // ===================================================
    // P3：速度/能耗优化（通用，带开关）
    // ===================================================

    /**
     * 散热感知器（V1 [ThermalGovernor]，P3 复用给 [OptimizationManager]）。
     *
     * 与 V1 [InferenceEngine] 共享同一 [thermalMonitor] 实例；P3 的 [OptimizationManager] /
     * [com.aibox.domain.optimization.EnergyOptimizer] 通过它读热档位。
     */
    val thermalGovernor: ThermalGovernor = ThermalGovernor(thermalMonitor)

    /**
     * 优化设置独立 DataStore 持久化（P3）。
     *
     * 与 [SettingsRepository] 用不同的 DataStore 文件名空间（`optimization` vs `settings`），
     * 避免污染现有 17 个 key。提供 [OptimizationSettings] 的 read / update / set API。
     */
    val optimizationSettingsRepository: OptimizationSettingsRepository =
        OptimizationSettingsRepository(appContext)

    /**
     * 优化管理器（P3）：统一优化入口。
     *
     * 串联 [com.aibox.domain.optimization.TtftOptimizer] /
     * [com.aibox.domain.optimization.TgOptimizer] /
     * [com.aibox.domain.optimization.EnergyOptimizer]，
     * 由 [com.aibox.AIBoxApp.onCreate] 在 container 初始化后调 [OptimizationManager.onAppStart]。
     *
     * 子 optimizer 由 OptimizationManager 内部按最新 settings 重建，外部不直接持有。
     */
    val optimizationManager: OptimizationManager = OptimizationManager(
        settingsRepository = optimizationSettingsRepository,
        thermalMonitor = thermalMonitor,
        backendManagerV2 = backendManager,
        modelRepository = modelRepository,
        thermalGovernor = thermalGovernor
    )

    /**
     * 统一搜索引擎（A1）：聚合 Chat / ChatMessage / Model / Setting / Tool / Module / Benchmark 7 个域。
     *
     * 由 [SearchEngineFactory.create] 在容器构造时一次性装配；ViewModel 通过
     * `AIBoxApp.instance.container.searchEngine` 获取，不重复构造。
     */
    val searchEngine: SearchEngine = SearchEngineFactory.create(this)

    // ===================================================
    // B1：工具调用抽象层
    // ===================================================

    /**
     * 工具设置独立 DataStore 持久化（B1）。
     *
     * 与 [settingsRepository] / [optimizationSettingsRepository] 用不同 DataStore 文件名空间
     * （`tool_settings` vs `settings` / `optimization`），避免污染现有 key。
     *
     * 存储内容：
     * - 每个工具的启用状态（基础工具默认开，高级工具默认关）
     * - 高级工具的自动确认白名单（默认空，需用户在设置面板手动加）
     */
    val toolSettingsRepository: ToolSettingsRepository = ToolSettingsRepository(appContext)

    /**
     * 工具注册中心（B1）：所有工具通过 register() 注册。
     *
     * - 持有 [toolSettingsRepository] 引用以读开关 / 自动确认白名单
     * - [ToolRegistry.execute] 是统一执行闸门：开关检查 → 高级工具确认 → 执行 → 异常兜底
     * - [getActiveToolsForLLM] 给 LLM function calling 暴露启用工具的 schema
     */
    val toolRegistry: ToolRegistry = ToolRegistry(toolSettingsRepository)

    /**
     * 工具执行器（B1）：AgentRunner 与 [toolRegistry] 之间的薄适配层。
     *
     * - [ToolExecutor.executeFunctionCall] 执行单个 LLM function call
     * - [ToolExecutor.executeMultiple] 并行执行多个 LLM function call（OpenAI / Anthropic 支持）
     */
    val toolExecutor: ToolExecutor = ToolExecutor(toolRegistry)

    /**
     * 工具注册引导（B1 工具调用抽象层 + B2-B5 基础工具 + C1-C5 高级工具）：在 App 启动后调用
     * [ToolBootstrap.registerBuiltinTools] 注册内置工具。
     *
     * - B2-B5 已落地：[com.aibox.domain.tool.builtin.DateTimeTool] /
     *   [com.aibox.domain.tool.builtin.CalculatorTool] /
     *   [com.aibox.domain.tool.builtin.WebSearchTool] /
     *   [com.aibox.domain.tool.builtin.UrlReaderTool] /
     *   [com.aibox.domain.tool.builtin.DeviceInfoBasicTool]
     * - C1-C5 已落地：[com.aibox.domain.tool.builtin.FileReadTool] /
     *   [com.aibox.domain.tool.builtin.FileWriteTool] /
     *   [com.aibox.domain.tool.builtin.EmailTool] /
     *   [com.aibox.domain.tool.builtin.DeviceInfoAdvancedTool] /
     *   [com.aibox.domain.tool.builtin.PhoneControlTool]
     *
     * 高级工具默认关闭，需用户在工具设置面板手动启用；执行前需确认（除非加入自动确认白名单）。
     * 调用方在 [com.aibox.AIBoxApp.onCreate] Phase 6 调起。
     */
    val toolBootstrap: ToolBootstrap = ToolBootstrap(
        registry = toolRegistry,
        settingsRepository = settingsRepository,
        appContext = appContext,
        toolSettingsRepository = toolSettingsRepository
    )

    // ===================================================
    // D：ASR / TTS 抽象 + 系统后端（默认 fallback）
    // ===================================================

    /**
     * ASR / TTS provider 注册中心（D1 + D2）。
     *
     * 宿主启动时注册内置 [SystemAsrProvider] / [SystemTtsProvider]（id="system"），
     * UI 通过 [AudioProviderRegistry.getDefaultAsrProvider] / [getDefaultTtsProvider]
     * 取默认 fallback；后续插件若提供 ASR / TTS，由 PluginManager 调 [registerAsr] / [registerTts] 注册。
     *
     * [SystemTtsProvider] 在构造时即异步 init TextToSpeech 引擎，避免首次合成时白屏等待。
     */
    val audioProviderRegistry: AudioProviderRegistry = AudioProviderRegistry().apply {
        registerAsr(SystemAsrProvider(appContext))
        registerTts(SystemTtsProvider(appContext))
    }
}
