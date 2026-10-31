package com.aibox.ui.modules

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.aibox.domain.module.ModuleDiscoveryService

/**
 * [ModulesViewModel] 的 Factory，注入 [ModuleDiscoveryService]。
 *
 * 用法：
 * ```
 * viewModel(factory = ModulesViewModelFactory(LocalContext.current))
 * ```
 */
class ModulesViewModelFactory(
    private val context: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ModulesViewModel::class.java)) {
            val discoveryService = ModuleDiscoveryService(context.applicationContext)
            return ModulesViewModel(discoveryService) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
