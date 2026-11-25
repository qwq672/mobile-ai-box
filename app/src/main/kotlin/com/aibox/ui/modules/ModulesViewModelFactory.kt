package com.aibox.ui.modules

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.aibox.AIBoxApp
import com.aibox.domain.module.ModuleDiscoveryService
import com.aibox.domain.shortcut.ShortcutRepository

/**
 * [ModulesViewModel] 的 Factory，注入 [ModuleDiscoveryService] 及全局单例服务。
 *
 * 用法：
 * ```
 * viewModel(factory = ModulesViewModelFactory(LocalContext.current))
 * ```
 *
 * 增强（2026-11-25）：从 [AIBoxApp] 容器取 PluginManager / [ShortcutRepository] 单例——
 * 避免 ViewModel 自己 new 导致"启用状态"与全局运行态不一致（插件绑定状态在
 * PluginManager 内存里，必须操作同一个实例）。
 */
class ModulesViewModelFactory(
    private val context: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ModulesViewModel::class.java)) {
            val appContext = context.applicationContext
            val discoveryService = ModuleDiscoveryService(appContext)
            val container = AIBoxApp.instance.container
            return ModulesViewModel(
                discoveryService = discoveryService,
                appContext = appContext,
                pluginManager = container.pluginManager,
                shortcutRepository = container.shortcutRepository
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
