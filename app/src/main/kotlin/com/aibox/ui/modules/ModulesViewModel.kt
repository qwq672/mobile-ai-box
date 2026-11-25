package com.aibox.ui.modules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibox.AIBoxApp
import com.aibox.domain.module.DiscoveredModule
import com.aibox.domain.module.ModuleDiscoveryService
import com.aibox.domain.shortcut.PresetConfig
import com.aibox.domain.shortcut.Shortcut
import com.aibox.domain.shortcut.ShortcutRepository
import com.aibox.domain.shortcut.ShortcutTargetType
import com.aibox.infra.module.loader.ModuleGraphService
import com.aibox.infra.plugin.PluginDiagnostic
import com.aibox.infra.plugin.PluginManager
import com.aibox.util.outcome.Outcome
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
    private val appContext: android.content.Context,
    private val pluginManager: PluginManager,
    private val shortcutRepository: ShortcutRepository
) : ViewModel() {

    private val _modules = MutableStateFlow<List<DiscoveredModule>>(emptyList())
    val modules: StateFlow<List<DiscoveredModule>> = _modules

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _diagnostics = MutableStateFlow<List<PluginDiagnostic>>(emptyList())
    val diagnostics: StateFlow<List<PluginDiagnostic>> = _diagnostics

    private val _dependencyReport = MutableStateFlow<ModuleGraphService.Report?>(null)
    val dependencyReport: StateFlow<ModuleGraphService.Report?> = _dependencyReport

    /** 操作反馈（启用/禁用/添加主页），一次性 toast 文本。 */
    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage

    /** 管理态：允许批量选择模块。 */
    private val _manageMode = MutableStateFlow(false)
    val manageMode: StateFlow<Boolean> = _manageMode

    /** 批量选中的包名。 */
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected

    init {
        refresh()
    }

    /** 模块当前是否处于启用（已绑定）状态。 */
    fun isEnabled(packageName: String): Boolean = pluginManager.isActive(packageName)

    /** 切换单个模块启用状态；完成后重新扫描诊断。 */
    fun togglePlugin(packageName: String, enable: Boolean) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (enable) pluginManager.enablePlugin(packageName) else pluginManager.disablePlugin(packageName)
            }
            _actionMessage.value = when (result) {
                is Outcome.Success -> if (enable) "已启用 $packageName" else "已禁用 $packageName"
                is Outcome.Failure -> "操作失败：${result.reason}"
            }
            refresh()
        }
    }

    /** 批量把选中的插件设为指定启用状态。 */
    fun setSelectedEnabled(enable: Boolean) {
        val pkgs = _selected.value.toList()
        if (pkgs.isEmpty()) return
        viewModelScope.launch {
            var ok = 0
            var fail = 0
            withContext(Dispatchers.IO) {
                pkgs.forEach { pkg ->
                    val r = if (enable) pluginManager.enablePlugin(pkg) else pluginManager.disablePlugin(pkg)
                    if (r is Outcome.Success) ok++ else fail++
                }
            }
            _actionMessage.value = "已${if (enable) "启用" else "禁用"} $ok 个模块" +
                if (fail > 0) "，失败 $fail 个" else ""
            _selected.value = emptySet()
            _manageMode.value = false
            refresh()
        }
    }

    fun setManageMode(on: Boolean) {
        _manageMode.value = on
        if (!on) _selected.value = emptySet()
    }

    fun toggleSelected(packageName: String) {
        _selected.value = if (_selected.value.contains(packageName)) {
            _selected.value - packageName
        } else {
            _selected.value + packageName
        }
    }

    /** 把模块添加为主页快捷方式（targetType=PLUGIN_PAGE）。 */
    fun addToHome(module: DiscoveredModule) {
        viewModelScope.launch {
            runCatching {
                shortcutRepository.create(
                    Shortcut(
                        id = "", // create() 会覆盖
                        title = module.displayName,
                        subtitle = module.packageName,
                        icon = module.type.icon,
                        targetType = ShortcutTargetType.PLUGIN_PAGE,
                        presetConfig = PresetConfig(),
                        color = null
                    )
                )
            }.onSuccess {
                _actionMessage.value = "已添加到主页：${module.displayName}"
            }.onFailure { e ->
                _actionMessage.value = "添加失败：${e.message}"
            }
        }
    }

    fun consumeActionMessage() { _actionMessage.value = null }

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
