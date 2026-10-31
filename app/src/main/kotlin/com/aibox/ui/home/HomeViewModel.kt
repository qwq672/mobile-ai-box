package com.aibox.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibox.data.model.ModelInfo
import com.aibox.data.repo.ModelRepository
import com.aibox.domain.shortcut.Shortcut
import com.aibox.domain.shortcut.ShortcutRepository
import com.aibox.domain.shortcut.ShortcutTargetType
import com.aibox.infra.benchmark.BenchmarkStorage
import com.aibox.infra.plugin.PluginManager
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 主页 ViewModel（E 主页 Hybrid 设计）。
 *
 * 聚合 4 类数据：
 * 1. [shortcuts]：用户保存的快捷方式列表（来自 [ShortcutRepository]，按 lastUsedAt 排序）。
 * 2. [recentModels]：最近使用的模型列表（来自 [ModelRepository.observeAll]，已按 lastUsedAt DESC 排序）。
 * 3. [functionStats]：功能区统计（聊天模型数 / 图像模型数 / 基准测试数 / 已启用插件数 / 翻译快捷方式数）。
 *
 * 数据组合策略：
 * - [shortcuts] 直接 stateIn（shortcutRepository 是 Flow）。
 * - [recentModels] 限制最多 5 条（首页"最近模型"区只展示前几条，避免长列表）。
 * - [functionStats] 用 [combine] 把多个 Flow 合并：
 *     - 模型数：从 [ModelRepository.observeAll] 取（按 name 启发式判断是否图像模型）。
 *     - 基准测试数：从 [BenchmarkStorage.observeAll] 取 size。
 *     - 插件数：[PluginManager.listActive] 是非 Flow 接口，用 [pluginCountFlow] 缓存 + [refreshPluginCount] 手动刷新。
 *     - 翻译快捷方式数：从 [ShortcutRepository.shortcuts] 计算。
 *
 * @param shortcutRepository 快捷方式仓库
 * @param modelRepository 模型仓库
 * @param benchmarkStorage 基准测试存储
 * @param pluginManager 插件管理器（非 Flow 接口；用 [pluginCountFlow] 缓存）
 */
class HomeViewModel(
    private val shortcutRepository: ShortcutRepository,
    private val modelRepository: ModelRepository,
    private val benchmarkStorage: BenchmarkStorage,
    private val pluginManager: PluginManager
) : ViewModel() {

    private val tag = "aibox.ui.home"

    /** 所有快捷方式（按 lastUsedAt 降序；repository 已排序）。 */
    val shortcuts: StateFlow<List<Shortcut>> =
        shortcutRepository.shortcuts.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    /** 最近模型（最多 5 条，按 lastUsedAt DESC）。 */
    val recentModels: StateFlow<List<ModelInfo>> =
        modelRepository.observeAll()
            .map { list -> list.take(RECENT_MODEL_LIMIT) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList()
            )

    /** 全部模型数（用于功能区"聊天"tile 副标题）。 */
    private val allModels: StateFlow<List<ModelInfo>> =
        modelRepository.observeAll().stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    /** 全部基准测试记录（用于计数）。 */
    private val allBenchmarks: StateFlow<List<com.aibox.data.db.BenchmarkResultEntity>> =
        benchmarkStorage.observeAll().stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    /** 插件计数缓存（[PluginManager.listActive] 非 Flow；init + [refreshPluginCount] 手动刷新）。 */
    private val _pluginCount = MutableStateFlow(0)
    val pluginCount: StateFlow<Int> = _pluginCount.asStateFlow()

    init {
        refreshPluginCount()
        LogSystem.info(tag, "HomeViewModel init")
    }

    /**
     * 重新查询插件数；插件安装 / 卸载事件后由 UI 调用。
     */
    fun refreshPluginCount() {
        _pluginCount.value = runCatching { pluginManager.listActive().size }
            .onFailure { LogSystem.warn(tag, "refreshPluginCount failed: ${it.message}") }
            .getOrDefault(0)
    }

    /**
     * 功能区统计：聚合 [allModels] + [allBenchmarks] + [pluginCount] + [shortcuts] 计算 5 个数字。
     */
    val functionStats: StateFlow<FunctionStats> = combine(
        allModels, allBenchmarks, pluginCount, shortcuts
    ) { models, benchmarks, pluginCountVal, scList ->
        FunctionStats(
            chatModelCount = models.count { isChatModel(it) },
            imageModelCount = models.count { isImageModel(it) },
            benchmarkCount = benchmarks.size,
            moduleCount = pluginCountVal,
            translateCount = scList.count { it.targetType == ShortcutTargetType.TRANSLATE }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = FunctionStats()
    )

    /**
     * 标记快捷方式被使用（点击时调）；让 [shortcuts] 重新排序把最近使用的放前面。
     */
    fun touchShortcutUsage(id: String) {
        viewModelScope.launch {
            runCatching { shortcutRepository.touchUsage(id) }
                .onFailure { LogSystem.warn(tag, "touchUsage failed: ${it.message}") }
        }
    }

    companion object {
        /** 首页"最近模型"区最多显示的模型条数。 */
        private const val RECENT_MODEL_LIMIT = 5

        /**
         * 启发式判断模型是否为聊天模型。
         *
         * 当前 [ModelInfo] 没有 task 支持字段，所有 GGUF 文本模型都视为聊天模型；
         * 图像模型（含 SD / Stable Diffusion 关键字）会被排除。
         */
        private fun isChatModel(m: ModelInfo): Boolean = !isImageModel(m)

        /**
         * 启发式判断模型是否为图像生成模型。
         *
         * 通过 [ModelInfo.name] / [ModelInfo.arch] 中是否包含 "sd" / "stable-diffusion" / "image" 关键字判断。
         * 仅为 UI 显示用途；真实图像生成功能等模块装了才有。
         */
        private fun isImageModel(m: ModelInfo): Boolean {
            val n = (m.name + " " + (m.arch ?: "")).lowercase()
            return n.contains("sd") || n.contains("stable-diffusion") || n.contains("stable_diffusion") ||
                n.contains("image") || n.contains("sdxl") || n.contains("flux")
        }
    }
}

/**
 * 功能区统计：5 个数字对应 5 个 tile 的副标题。
 */
data class FunctionStats(
    val chatModelCount: Int = 0,
    val imageModelCount: Int = 0,
    val benchmarkCount: Int = 0,
    val moduleCount: Int = 0,
    val translateCount: Int = 0
)
