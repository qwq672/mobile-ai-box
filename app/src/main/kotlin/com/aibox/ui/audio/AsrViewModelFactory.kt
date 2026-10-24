package com.aibox.ui.audio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.aibox.domain.audio.AudioProviderRegistry

/**
 * [AsrViewModel] 的 ViewModelProvider.Factory（D1）。
 *
 * 由于 [AsrViewModel] 构造器需要 [AudioProviderRegistry]（来自
 * [com.aibox.di.AppContainer]），需自定义 factory 注入。
 *
 * 用法：
 * ```
 * viewModel(factory = AsrViewModelFactory(container.audioProviderRegistry))
 * ```
 */
class AsrViewModelFactory(
    private val registry: AudioProviderRegistry
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass == AsrViewModel::class.java) {
            "AsrViewModelFactory can only create AsrViewModel, got $modelClass"
        }
        return AsrViewModel(registry) as T
    }
}
