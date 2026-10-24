package com.aibox.ui.tool

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibox.domain.tool.Tool
import com.aibox.domain.tool.ToolRegistry
import com.aibox.domain.tool.ToolSettingsRepository
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 工具设置面板 ViewModel（B7 工具设置面板）。
 *
 * 职责：
 * - 暴露所有已注册工具（[tools]）：基础工具 / 高级工具分类
 * - 暴露用户启用列表（[enabledToolIds]）：StateFlow，UI 用 collectAsStateWithLifecycle 自动响应
 * - 暴露自动确认白名单（[autoConfirmIds]）：仅高级工具有"自动确认"二级开关
 * - 提供 [toggleTool] / [toggleAutoConfirm] 写回 [ToolSettingsRepository]
 *
 * 设计原则：
 * - **不缓存工具列表**：[tools] 直接调 [ToolRegistry.listAllTools]（@Synchronized 保护）；
 *   工具注册主要在启动期完成，运行时变化少（除非热插拔插件），故用 [stateIn] 把一次性快照 publish 给 UI
 * - **enabledIds / autoConfirmIds 来自 DataStore**：自动响应 UI 切换；toggle 写回 DataStore
 * - **零异常约定**：toggle 内 runCatching 兜底，失败仅记 error 日志（DataStore 写失败极少见）
 *
 * 关于 [tools] StateFlow 的说明：
 * - 当前实现用 `flowOf(registry.listAllTools())` 一次性 publish；
 * - 工具表运行时若有变化（如插件热加载），需要手动调用 refresh() 重新 publish
 * - 后续若需要自动响应工具表变化，可改为 channelFlow + 监听 [ToolRegistry] 注册事件
 */
class ToolSettingsViewModel(
    private val toolRegistry: ToolRegistry,
    private val toolSettingsRepository: ToolSettingsRepository
) : ViewModel() {

    /** 已注册的所有工具快照（按 id 字母序排序，保证 UI 列表稳定）。 */
    private val _tools = kotlinx.coroutines.flow.MutableStateFlow<List<Tool>>(emptyList())
    val tools: StateFlow<List<Tool>> = _tools

    /** 当前启用的工具 id 集合（从 DataStore 流式订阅）。 */
    val enabledToolIds: StateFlow<Set<String>> = toolSettingsRepository.enabledToolsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptySet()
        )

    /** 当前自动确认白名单（从 DataStore 流式订阅）。 */
    val autoConfirmIds: StateFlow<Set<String>> = toolSettingsRepository.autoConfirmToolsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptySet()
        )

    init {
        refresh()
    }

    /** 重新从 [toolRegistry] 拉取工具列表快照。运行时若有插件热加载可调用。 */
    fun refresh() {
        val snapshot = toolRegistry.listAllTools().sortedBy { it.id }
        _tools.value = snapshot
        LogSystem.info(TAG, "refresh: ${snapshot.size} tools loaded")
    }

    /** 切换工具启用 / 禁用。 */
    fun toggleTool(toolId: String, enabled: Boolean) {
        LogSystem.info(TAG, "toggleTool: $toolId -> $enabled")
        viewModelScope.launch {
            runCatching { toolSettingsRepository.setToolEnabled(toolId, enabled) }
                .onFailure { LogSystem.error(TAG, "setToolEnabled failed for $toolId", it) }
        }
    }

    /** 切换高级工具的自动确认白名单。基础工具调用此方法无意义（不会进入确认流程）。 */
    fun toggleAutoConfirm(toolId: String, autoConfirm: Boolean) {
        LogSystem.info(TAG, "toggleAutoConfirm: $toolId -> $autoConfirm")
        viewModelScope.launch {
            runCatching { toolSettingsRepository.setAutoConfirm(toolId, autoConfirm) }
                .onFailure { LogSystem.error(TAG, "setAutoConfirm failed for $toolId", it) }
        }
    }

    companion object {
        private const val TAG = "aibox.ui.tool.settings"
    }
}
