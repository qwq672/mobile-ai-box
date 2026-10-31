package com.aibox.ui.tool

import com.aibox.domain.tool.ToolResult

/**
 * 工具调用 UI 状态（B6 工具调用 UI）。
 *
 * 生命周期：
 * - [CONFIRMING]：高级工具执行前等待用户确认（弹 [ToolConfirmDialog]）
 * - [RUNNING]：用户已确认（或自动确认白名单命中 / 基础工具跳过确认）后正在执行
 * - [SUCCESS]：工具执行成功完成（[ToolInvocation.result] 非空）
 * - [FAILED]：工具执行失败（[ToolInvocation.error] 含失败原因；用户可点"重试"）
 *
 * 设计原则：
 * - 状态机线性推进，FAILED → CONFIRMING（用户重试时）属合理回退
 * - 每个状态对应 [ToolInvocationBubble] 的不同视觉（色调 + 状态 chip + 内容）
 */
enum class ToolState {
    /** 高级工具等待用户确认执行（弹 [ToolConfirmDialog]）。 */
    CONFIRMING,

    /** 工具执行中（[ToolInvocation.progress] 可能为 null 表示进度不可知，UI 显示不确定进度条）。 */
    RUNNING,

    /** 工具执行成功（[ToolInvocation.result] 含 [ToolResult]）。 */
    SUCCESS,

    /** 工具执行失败（[ToolInvocation.error] 含失败原因；用户可重试）。 */
    FAILED
}

/**
 * 一次工具调用的 UI 投影（B6 工具调用 UI）。
 *
 * - 由 [ToolInvocationViewModel.submit] 创建并加入 invocations 列表
 * - 状态随执行进度更新：CONFIRMING → RUNNING → SUCCESS / FAILED
 * - [ToolInvocationBubble] 渲染此 data class；可展开查看完整输出
 *
 * @param id 调用唯一 ID（用于重试 / 取消等操作引用）
 * @param toolId 工具 ID（与 [com.aibox.domain.tool.Tool.id] 一致）
 * @param toolDisplayName 工具显示名（用户可见）
 * @param toolIcon 工具图标（emoji 字符串）
 * @param params 调用参数 map；用于展示在 bubble 与确认 dialog
 * @param state 当前状态（驱动 UI 渲染分支）
 * @param progress 执行进度 0.0..1.0；null 表示不确定进度（仅 RUNNING 时有效）
 * @param result 执行结果；仅 [ToolState.SUCCESS] 时非空
 * @param error 失败原因；仅 [ToolState.FAILED] 时非空
 * @param needsConfirmation 是否需要用户确认（高级工具为 true）
 * @param executionTimeMs 执行耗时（毫秒）；仅 SUCCESS / FAILED 时有意义
 */
data class ToolInvocation(
    val id: String,
    val toolId: String,
    val toolDisplayName: String,
    val toolIcon: String,
    val params: Map<String, Any>,
    val state: ToolState,
    val progress: Float? = null,
    val result: ToolResult? = null,
    val error: String? = null,
    val needsConfirmation: Boolean = false,
    val executionTimeMs: Long = 0
)

/**
 * 待用户确认的工具调用（B6 工具调用 UI）。
 *
 * 由 [ToolInvocationViewModel.confirmCallback] 创建并 push 到 `pendingConfirmation` StateFlow；
 * UI（[ChatScreen] / [ToolConfirmDialog]）观察此状态弹确认对话框，用户选择后调
 * [ToolInvocationViewModel.confirm] / [ToolInvocationViewModel.deny] /
 * [ToolInvocationViewModel.alwaysAllow] 之一，内部通过 [CompletableDeferred] 解锁 await。
 *
 * @param invocationId 关联的 [ToolInvocation.id]
 * @param toolId 工具 ID
 * @param toolDisplayName 工具显示名
 * @param toolIcon 工具图标
 * @param params 待确认参数
 * @param needsConfirmation 始终为 true（dialog 仅在高级工具时弹）；冗余字段保留语义对称
 */
data class PendingToolConfirmation(
    val invocationId: String,
    val toolId: String,
    val toolDisplayName: String,
    val toolIcon: String,
    val params: Map<String, Any>
)
