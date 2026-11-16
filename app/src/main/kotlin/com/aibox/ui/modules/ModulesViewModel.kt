package com.aibox.ui.modules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibox.AIBoxApp
import com.aibox.domain.module.DiscoveredModule
import com.aibox.domain.module.ModuleDiscoveryService
import com.aibox.infra.module.loader.ModuleGraphService
import com.aibox.infra.plugin.PluginDiagnostic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 模块管理页 ViewModel。
 *
 * - 启动时调 [ModuleDiscoveryService.discoverAll] 扫描所有 AIBox 模块 APK
 * - 同时诊断已知后端插件（LiteRT / ONNX / MNN），显示卡在哪一步
 * - 提供 refresh() 让用户手动重新扫描（安装新模块后用）
 */
class ModulesViewModel(
    private val discoveryService: ModuleDiscoveryService,
    private val appContext: android.content.Context
) : ViewModel() {

    private val _modules = MutableStateFlow<List<DiscoveredModule>>(emptyList())
    val modules: StateFlow<List<DiscoveredModule>> = _modules

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _diagnostics = MutableStateFlow<List<PluginDiagnostic>>(emptyList())
    val diagnostics: StateFlow<List<PluginDiagnostic>> = _diagnostics

    private val _dependencyReport = MutableStateFlow<ModuleGraphService.Report?>(null)
    val dependencyReport: StateFlow<ModuleGraphService.Report?> = _dependencyReport

    init {
        refresh()
    }

    /** 重新扫描所有 AIBox 模块（用户安装新模块后用）。 */
    fun refresh() {
        viewModelScope.launch {
            _scanning.value = true
            try {
                val graphService = ModuleGraphService(appContext)
                val result = withContext(Dispatchers.IO) {
                    val mods = discoveryService.discoverAll()
                    val pm = AIBoxApp.instance.container.pluginManager
                    // 先尝试自动绑定一次，再诊断，这样 active 状态是真实的
                    runCatching { pm.syncInstalledBackendPlugins() }
                    val diag = runCatching { pm.diagnoseKnown() }.getOrDefault(emptyList())
                    val packages = (mods.map { it.packageName } +
                        diag.filter { it.installed }.map { it.packageName }).distinct()
                    val report = runCatching { graphService.scan(packages) }.getOrNull()
                    Triple(mods, diag, report)
                }
                _modules.value = result.first
                _diagnostics.value = result.second
                _dependencyReport.value = result.third
            } finally {
                _scanning.value = false
            }
        }
    }
}
