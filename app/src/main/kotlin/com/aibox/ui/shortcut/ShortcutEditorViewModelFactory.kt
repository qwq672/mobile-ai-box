package com.aibox.ui.shortcut

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.aibox.data.repo.ModelRepository
import com.aibox.domain.shortcut.ShortcutRepository

/**
 * [ShortcutEditorViewModel] 的 ViewModelProvider.Factory。
 *
 * 由于构造器需要 [ShortcutRepository] + [ModelRepository] 两个参数（来自 [com.aibox.di.AppContainer]），
 * 需要自定义 factory 注入。
 *
 * 用法：
 * ```
 * viewModel(factory = ShortcutEditorViewModelFactory(container.shortcutRepository, container.modelRepository))
 * ```
 */
class ShortcutEditorViewModelFactory(
    private val shortcutRepository: ShortcutRepository,
    private val modelRepository: ModelRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass == ShortcutEditorViewModel::class.java) {
            "ShortcutEditorViewModelFactory can only create ShortcutEditorViewModel, got $modelClass"
        }
        return ShortcutEditorViewModel(shortcutRepository, modelRepository) as T
    }
}
