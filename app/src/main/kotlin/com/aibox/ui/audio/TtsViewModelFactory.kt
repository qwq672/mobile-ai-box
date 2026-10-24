package com.aibox.ui.audio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.aibox.domain.audio.AudioProviderRegistry

/**
 * [TtsViewModel] 的 ViewModelProvider.Factory（D2）。
 *
 * 用法：
 * ```
 * viewModel(factory = TtsViewModelFactory(container.audioProviderRegistry))
 * ```
 */
class TtsViewModelFactory(
    private val registry: AudioProviderRegistry
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass == TtsViewModel::class.java) {
            "TtsViewModelFactory can only create TtsViewModel, got $modelClass"
        }
        return TtsViewModel(registry) as T
    }
}
