package com.aibox.feature.agent

import com.aibox.core.common.Outcome
import com.aibox.core.tool.Tool
import com.aibox.core.tool.ToolRegistry
import com.aibox.core.tool.ToolResult
import kotlinx.coroutines.flow.Flow

/**
 * 本地 Agent — 完全离线的工具调用框架（v2 重构）。
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * v2 重要变更（按用户决策）：
 *
 * 工具调用拆分两层（避免屎山）：
 *   - 工具契约在 [core:tool-api]（任何 feature 可复用）
 *   - 内置工具实现在 [feature:agent]（M5 实现，见 [BuiltinTools]）
 *   - 高级工具通过插件 APK 分发（M2 起支持）
 *
 * Agent 与 Tool 关系：
 *   - Agent 是"决策者"：基于 LLM 决定调用哪个工具
 *   - Tool 是"执行者"：执行实际操作（文件、计算、邮件等）
 *   - Agent 只通过 [ToolRegistry] 访问工具，不直接 import 工具实现
 *
 * 敏感操作两层防护（用户已要求）：
 *   - 第一层：[Tool.sensitivity] 标记，UI 层判断是否需弹确认
 *   - 第二层：Agent 调用工具前向 [toolExecutionGate] 提交，gate 拒绝则不执行
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 */
public interface Agent {

    public fun run(task: AgentTask): Flow<AgentEvent>
}

public data class AgentTask(
    public val userMessage: String,
    public val availableToolIds: List<String> = emptyList(),
    public val backendId: String? = null,
    public val personaCompositionId: String? = null,
    public val maxIterations: Int = 10,
    public val maxLatencyMs: Long = 60_000,
    public val requireConfirmationForSensitive: Boolean = true,
)

/**
 * Agent 执行事件 — 流式输出，UI 实时渲染。
 *
 * [AgentEvent.Thinking]   : LLM 推理中（思考过程）
 * [AgentEvent.ToolCallRequested] : LLM 决定调用某个工具（UI 弹确认）
 * [AgentEvent.ToolCallApproved] : 用户批准工具调用
 * [AgentEvent.ToolCallRejected] : 用户拒绝工具调用
 * [AgentEvent.ToolResult]: 工具执行结果
 * [AgentEvent.Reply]     : 最终回复给用户的文本
 * [AgentEvent.Done]      : 任务完成
 * [AgentEvent.Failed]    : 失败（超时 / 工具报错 / LLM 异常）
 */
public sealed class AgentEvent {
    public data class Thinking(val partial: String) : AgentEvent()
    public data class ToolCallRequested(val toolId: String, val args: Map<String, String>, val reason: String) : AgentEvent()
    public data class ToolCallApproved(val toolId: String) : AgentEvent()
    public data class ToolCallRejected(val toolId: String) : AgentEvent()
    public data class ToolResult(val toolId: String, val result: ToolResult) : AgentEvent()
    public data class Reply(val text: String) : AgentEvent()
    public data class Done(val totalIterations: Int, val durationMs: Long, val toolCalls: Int) : AgentEvent()
    public data class Failed(val reason: String, val atIteration: Int) : AgentEvent()
}

/**
 * 工具执行网关 — UI 通过此接口审批敏感工具调用。
 *
 * 实现路径：
 *   - 默认：[DefaultToolExecutionGate] 弹 Compose 对话框
 *   - 测试：[AutoApproveGate] 全部允许（仅单元测试用）
 */
public interface ToolExecutionGate {
    public suspend fun approve(tool: Tool, args: Map<String, String>): Boolean
}

/**
 * Agent 默认实现（M5 里程碑实现）。
 *
 * 工作流程：
 *   1. 接收 [AgentTask]，构建 system prompt
 *   2. 把 [ToolRegistry.list] 中所有 tool 的 schema 注入 LLM 上下文
 *   3. LLM 推理 → 输出 [AgentEvent.Thinking] 流
 *   4. 解析 LLM 输出，若包含 `<tool_call id="..." args="..."/>` → 进入工具调用流程
 *   5. 触发 [AgentEvent.ToolCallRequested]，等待 [ToolExecutionGate.approve]
 *   6. 批准 → 调 [Tool.execute] → 推 [AgentEvent.ToolResult]
 *   7. 把工具结果作为 context 反馈给 LLM，回到步骤 3
 *   8. 直到 LLM 输出终止标记或 [AgentTask.maxIterations] 用尽
 */
public class DefaultAgent(
    private val toolRegistry: ToolRegistry,
    private val gate: ToolExecutionGate,
) : Agent {
    override fun run(task: AgentTask): Flow<AgentEvent> = kotlinx.coroutines.flow.flow {
        // TODO M5: 完整实现
        emit(AgentEvent.Failed("Agent implementation pending M5", atIteration = 0))
    }
}
