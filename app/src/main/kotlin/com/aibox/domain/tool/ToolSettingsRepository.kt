package com.aibox.domain.tool

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 工具设置独立 DataStore（B1 工具调用抽象层）。
 *
 * 与 [com.aibox.data.repo.SettingsRepository] / [com.aibox.domain.optimization.OptimizationSettingsRepository]
 * 用不同的 DataStore 文件名空间（`tool_settings` vs `settings` / `optimization`），
 * 避免与现有 key 共享同一文件造成 namespace 污染，也便于以后单独导出 / 清空工具设置。
 *
 * 存储内容：
 * - 每个工具的启用状态（[enabledToolsFlow]：`Set<String>` of tool ids）
 * - 高级工具的自动确认白名单（[autoConfirmToolsFlow]：`Set<String>` of tool ids）
 *
 * 默认值策略：
 * - 启用列表缺省 = [defaultEnabledTools]（5 个基础工具，详见注释）
 * - 自动确认列表缺省 = emptySet（高级工具默认不自动确认，需用户每次确认）
 *
 * 并发安全：
 * - DataStore 内部串行化 edit；多个 setToolEnabled / setAutoConfirm 并发不会丢失更新
 * - getEnabledToolIds / isToolEnabled / isAutoConfirmed 用 [Flow.first] 一次性快照
 *   （与 [com.aibox.domain.optimization.OptimizationSettingsRepository.current] 同模式）
 */

private val Context.toolSettingsDataStore by preferencesDataStore("tool_settings")

class ToolSettingsRepository(private val context: Context) {

    /**
     * 前缀常量（保留备用）。
     *
     * 当前 schema 用 stringSet 一次性存所有 id；若未来需要 per-tool 单独 boolean key
     * （便于按工具单独 export / migrate），可改用这两个前缀构造的 booleanPreferencesKey。
     */
    @Suppress("unused")
    private val TOOL_ENABLED_PREFIX = "tool_enabled_"

    @Suppress("unused")
    private val TOOL_AUTOCONFIRM_PREFIX = "tool_autoconfirm_"

    private val enabledToolsKey = stringSetPreferencesKey("enabled_tool_ids")
    private val autoConfirmToolsKey = stringSetPreferencesKey("autoconfirm_tool_ids")

    /** Observable 流：当前启用的工具 id 集合。 */
    val enabledToolsFlow: Flow<Set<String>> = context.toolSettingsDataStore.data.map { prefs ->
        prefs[enabledToolsKey] ?: defaultEnabledTools()
    }

    /** Observable 流：当前自动确认白名单。 */
    val autoConfirmToolsFlow: Flow<Set<String>> = context.toolSettingsDataStore.data.map { prefs ->
        prefs[autoConfirmToolsKey] ?: emptySet()
    }

    /** 一次性快照：当前启用的工具 id 集合。 */
    suspend fun getEnabledToolIds(): Set<String> = enabledToolsFlow.first()

    /** 检查单个工具是否启用。 */
    suspend fun isToolEnabled(toolId: String): Boolean = toolId in getEnabledToolIds()

    /** 设置工具启用 / 禁用。 */
    suspend fun setToolEnabled(toolId: String, enabled: Boolean) {
        context.toolSettingsDataStore.edit { prefs ->
            val current = prefs[enabledToolsKey] ?: defaultEnabledTools()
            val next = if (enabled) current + toolId else current - toolId
            prefs[enabledToolsKey] = next
        }
        LogSystem.info(TAG, "Tool $toolId enabled=$enabled")
    }

    /** 一次性快照：检查工具是否在自动确认白名单。 */
    suspend fun isAutoConfirmed(toolId: String): Boolean = toolId in autoConfirmToolsFlow.first()

    /** 把高级工具加入 / 移出自动确认白名单。 */
    suspend fun setAutoConfirm(toolId: String, autoConfirm: Boolean) {
        context.toolSettingsDataStore.edit { prefs ->
            val current = prefs[autoConfirmToolsKey] ?: emptySet()
            val next = if (autoConfirm) current + toolId else current - toolId
            prefs[autoConfirmToolsKey] = next
        }
        LogSystem.info(TAG, "Tool $toolId autoConfirm=$autoConfirm")
    }

    /**
     * 默认启用的工具（基础工具默认开，高级工具默认关）。
     *
     * 这 5 个工具对应 [ToolBootstrap.registerBuiltinTools] 中的基础工具占位注册顺序：
     * - `datetime`：日期时间（DateTimeTool，B2 范畴）
     * - `calculator`：计算器（CalculatorTool，B2 范畴）
     * - `web_search`：联网搜索（WebSearchTool，B3）
     * - `url_reader`：URL 阅读（UrlReaderTool，B4）
     * - `device_info_basic`：基础设备信息（DeviceInfoBasicTool，B5）
     *
     * 高级工具（`file_read` / `file_write` / `email` / `device_info_advanced` / `phone_control`）
     * 默认不在此列表中，需要用户在设置面板手动启用。
     *
     * 注：若用户首次启动 App 时这些工具还未实现（B2-B5 尚未落地），开关数据已写入 DataStore，
     * 等 B2-B5 实现并 register 后即可被 [ToolRegistry.getEnabledTools] 命中。
     */
    private fun defaultEnabledTools(): Set<String> = setOf(
        "datetime",
        "calculator",
        "web_search",
        "url_reader",
        "device_info_basic"
    )

    companion object {
        private const val TAG = "aibox.tool.settings"
    }
}
