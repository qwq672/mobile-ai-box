package com.aibox.ui.modules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibox.domain.module.DiscoveredModule
import com.aibox.domain.module.ModuleDiscoveryService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 模块管理页 ViewModel。
 *
 * - 启动时调 [ModuleDiscoveryService.discoverAll] 扫描所有 AIBox 模块 APK
 * - 提供 refresh() 让用户手动重新扫描（安装新模块后用）
 */
class ModulesViewModel(
    private val discoveryService: ModuleDiscoveryService
) : ViewModel() {

    private val _modules = MutableStateFlow<List<DiscoveredModule>>(emptyList())
    val modules: StateFlow<List<DiscoveredModule>> = _modules

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    init {
        refresh()
    }

    /** 重新扫描所有 AIBox 模块（用户安装新模块后用）。 */
    fun refresh() {
        viewModelScope.launch {
            _scanning.value = true
            try {
                _modules.value = discoveryService.discoverAll()
            } finally {
                _scanning.value = false
            }
        }
    }
}
