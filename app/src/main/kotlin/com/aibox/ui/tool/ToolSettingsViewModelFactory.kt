package com.aibox.ui.tool

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.aibox.domain.tool.ToolRegistry
import com.aibox.domain.tool.ToolSettingsRepository

/**
 * [ToolSettingsViewModel] 的 ViewModelProvider.Factory（B7）。
 *
 * 由于 [ToolSettingsViewModel] 构造器需要 [ToolRegistry] + [ToolSettingsRepository] 两个参数
 * （都来自 [com.aibox.di.AppContainer]），需要自定义 factory 注入。
 *
 * 用法：
 * ```
 * viewModel(factory = ToolSettingsViewModelFactory(container.toolRegistry, container.toolSettingsRepository))
 * ```
 */
class ToolSettingsViewModelFactory(
    private val toolRegistry: ToolRegistry,
    private val toolSettingsRepository: ToolSettingsRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass == ToolSettingsViewModel::class.java) {
            "ToolSettingsViewModelFactory can only create ToolSettingsViewModel, got $modelClass"
        }
        return ToolSettingsViewModel(toolRegistry, toolSettingsRepository) as T
    }
}
