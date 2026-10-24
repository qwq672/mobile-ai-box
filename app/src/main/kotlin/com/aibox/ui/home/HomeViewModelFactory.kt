package com.aibox.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.aibox.data.repo.ModelRepository
import com.aibox.domain.shortcut.ShortcutRepository
import com.aibox.infra.benchmark.BenchmarkStorage
import com.aibox.infra.plugin.PluginManager

/**
 * [HomeViewModel] 的 ViewModelProvider.Factory。
 *
 * 由于构造器需要 4 个参数（来自 [com.aibox.di.AppContainer]），需要自定义 factory 注入。
 *
 * 用法：
 * ```
 * viewModel(factory = HomeViewModelFactory(
 *     container.shortcutRepository,
 *     container.modelRepository,
 *     container.benchmarkSystem.storage,
 *     container.pluginManager
 * ))
 * ```
 */
class HomeViewModelFactory(
    private val shortcutRepository: ShortcutRepository,
    private val modelRepository: ModelRepository,
    private val benchmarkStorage: BenchmarkStorage,
    private val pluginManager: PluginManager
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass == HomeViewModel::class.java) {
            "HomeViewModelFactory can only create HomeViewModel, got $modelClass"
        }
        return HomeViewModel(shortcutRepository, modelRepository, benchmarkStorage, pluginManager) as T
    }
}
