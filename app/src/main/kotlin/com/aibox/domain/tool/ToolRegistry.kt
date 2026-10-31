package com.aibox.domain.tool

import com.aibox.util.log.LogSystem
import com.aibox.util.outcome.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 工具注册中心（B1 工具调用抽象层）。
 *
 * - 所有工具通过 [register] 注册（[ToolBootstrap] 在 App 启动时统一注册内置工具）
 * - LLM 通过 [getActiveToolsForLLM] 拿到可用工具的 function calling schema
 * - 用户通过 [ToolSettingsRepository] 控制每个工具的启用 / 禁用
 * - 高级工具执行前自动调 [ToolContext.confirmCallback]，除非在自动确认白名单内
 *
 * 设计原则：
 * - 注册 / 查询 / 注销都线程安全（[mutableMapOf] + ConcurrentHashMap 风格；当前用 synchronized
 *   保护注册表，因为注册主要发生在启动期，并发量低）
 * - 执行入口 [execute] 是统一闸门：检查开关 → 检查确认 → 执行（带耗时统计 + 错误兜底）
 * - 所有日志走 [LogSystem]，tag 前缀 `aibox.tool.registry`
 */
class ToolRegistry(
    private val toolSettingsRepository: ToolSettingsRepository
) {
    /** 已注册的工具表（id -> Tool）；用 synchronized 保护并发读写 */
    private val tools = mutableMapOf<String, Tool>()

    /** 注册一个工具。若 id 重复则覆盖旧实现（用于插件热更新）。 */
    @Synchronized
    fun register(tool: Tool) {
        tools[tool.id] = tool
        LogSystem.info(TAG, "Tool registered: ${tool.id} (${tool.displayName}, category=${tool.category})")
    }

    /** 注销工具。不存在则 no-op。 */
    @Synchronized
    fun unregister(toolId: String) {
        val removed = tools.remove(toolId)
        if (removed != null) {
            LogSystem.info(TAG, "Tool unregistered: $toolId")
        }
    }

    /** 按 id 取工具；不存在返回 null。 */
    @Synchronized
    fun getTool(id: String): Tool? = tools[id]

    /** 列出所有已注册工具（不区分开关、不区分分类）。返回快照 list。 */
    @Synchronized
    fun listAllTools(): List<Tool> = tools.values.toList()

    /** 获取用户启用的工具（含开关状态过滤）。 */
    suspend fun getEnabledTools(): List<Tool> {
        val enabledIds = toolSettingsRepository.getEnabledToolIds()
        val snapshot = listAllTools()
        return snapshot.filter { it.id in enabledIds }
    }

    /** 获取基础工具（不区分开关，按 category 过滤）。 */
    fun getBasicTools(): List<Tool> = listAllTools().filter { it.category == ToolCategory.BASIC }

    /** 获取高级工具（不区分开关，按 category 过滤）。 */
    fun getAdvancedTools(): List<Tool> = listAllTools().filter { it.category == ToolCategory.ADVANCED }

    /**
     * Observable 流：当前启用的工具列表（随 [toolSettingsRepository.enabledToolsFlow] 实时变化）。
     *
     * UI（如工具设置页）可 collect 这个 flow 自动响应开关变化。
     * 注：每次设置变化都重新查 tools 表快照，避免持有过期 Tool 引用。
     */
    val enabledToolsFlow: Flow<List<Tool>> = toolSettingsRepository.enabledToolsFlow.map { enabledIds ->
        listAllTools().filter { it.id in enabledIds }
    }

    /** 转成 LLM function calling schema（仅启用的工具）。 */
    suspend fun getActiveToolsForLLM(): List<ToolFunctionSchema> {
        return getEnabledTools().map { it.toFunctionSchema() }
    }

    /**
     * 执行工具，自动处理开关 + 确认。
     *
     * 流程：
     * 1. 工具不存在 → [Outcome.Failure] ("Tool not found")
     * 2. 工具被禁用 → [Outcome.Failure] ("Tool disabled")，记录 warn 日志
     * 3. 高级工具且 [requiresConfirmation]：
     *    - 若在自动确认白名单 → 跳过确认
     *    - 否则调 [ToolContext.confirmCallback]；用户拒绝 → [Outcome.Failure] ("User declined")
     * 4. 执行 [Tool.execute]，记录耗时 + 结果；捕获所有异常转 [Outcome.Failure]
     *
     * 注：返回的 [Outcome] 是工具执行结果；[Outcome.Success] 中的 [ToolResult.success]
     * 仍可能为 false（表示工具正常返回但业务失败，如搜索无结果）。
     */
    suspend fun execute(
        toolId: String,
        params: Map<String, Any>,
        context: ToolContext
    ): Outcome<ToolResult> {
        val tool = getTool(toolId)
            ?: return Outcome.Failure("Tool not found: $toolId")

        // 1. 检查开关
        val enabled = toolSettingsRepository.isToolEnabled(toolId)
        if (!enabled) {
            LogSystem.warn(TAG, "Tool $toolId called but disabled in settings")
            return Outcome.Failure("Tool disabled: $toolId")
        }

        // 2. 检查高级工具确认
        if (tool.requiresConfirmation) {
            val autoConfirm = toolSettingsRepository.isAutoConfirmed(toolId)
            if (!autoConfirm) {
                val confirmed = context.confirmCallback(
                    "确认执行 ${tool.displayName}?\n参数: ${formatParams(params)}",
                    params
                )
                if (!confirmed) {
                    LogSystem.info(TAG, "User declined to execute $toolId")
                    return Outcome.Failure("User declined")
                }
                LogSystem.info(TAG, "User confirmed execution of $toolId")
            } else {
                LogSystem.debug(TAG, "Tool $toolId auto-confirmed (in whitelist), skipping dialog")
            }
        }

        // 3. 执行（带耗时统计 + 异常兜底）
        LogSystem.info(TAG, "Executing tool $toolId with params: $params")
        val startTime = System.currentTimeMillis()
        return try {
            val result = tool.execute(params, context)
            val duration = System.currentTimeMillis() - startTime
            val success = result.getOrNull()?.success
            LogSystem.info(TAG, "Tool $toolId finished in ${duration}ms, success=$success")
            result
        } catch (e: Throwable) {
            // 工具实现违反"零异常约定"，兜底捕获
            LogSystem.error(TAG, "Tool $toolId execution threw (contract violation)", e)
            Outcome.Failure("Tool execution error: ${e.message}", e)
        }
    }

    /** 把参数 map 格式化为可读字符串（用于确认弹窗展示）。 */
    private fun formatParams(params: Map<String, Any>): String {
        if (params.isEmpty()) return "(无参数)"
        return params.entries.joinToString(", ") { "${it.key}=${it.value}" }
    }

    companion object {
        private const val TAG = "aibox.tool.registry"
    }
}

/**
 * LLM function calling schema（OpenAI tools 数组项的格式）。
 *
 * @param name 工具 id（与 [Tool.id] 一致）
 * @param description 工具描述（与 [Tool.description] 一致）
 * @param parameters JSON schema 字符串（由 [ToolParameterSchema.toJsonSchema] 生成）
 */
data class ToolFunctionSchema(
    val name: String,
    val description: String,
    val parameters: String  // JSON schema string
)

/**
 * 把 [Tool] 转成 [ToolFunctionSchema]（给 LLM function calling 用）。
 *
 * 顶层扩展函数，避免 [Tool] 接口本身承担 schema 转换职责。
 */
fun Tool.toFunctionSchema(): ToolFunctionSchema {
    return ToolFunctionSchema(
        name = id,
        description = description,
        parameters = getParameterSchema().toJsonSchema()
    )
}
