package com.aibox.ui.tool

import com.aibox.domain.tool.DeviceInfo
import com.aibox.domain.tool.FunctionCall
import com.aibox.domain.tool.ToolContext
import com.aibox.domain.tool.ToolExecutor
import com.aibox.domain.tool.ToolRegistry
import com.aibox.domain.tool.ToolSettingsRepository
import com.aibox.util.log.LogSystem
import com.aibox.util.outcome.Outcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * 工具调用流程管理器（B6 工具调用 UI）。
 *
 * 职责：
 * - 持有当前活跃的 [ToolInvocation] 列表（[invocations] StateFlow，UI 用 collectAsStateWithLifecycle）
 * - 持有当前待确认的工具调用（[pendingConfirmation] StateFlow，UI 弹 [ToolConfirmDialog]）
 * - 提供 [submit] / [retry] / [confirm] / [deny] / [alwaysAllow] 等操作
 * - 内部通过 [ToolExecutor.executeFunctionCall] 调起实际执行；
 *   高级工具的 [ToolContext.confirmCallback] 通过 [CompletableDeferred] 等用户响应
 *
 * 设计原则：
 * - **零异常约定**：所有方法不抛异常；[Outcome] 失败被转换为 [ToolState.FAILED] + error 文本
 * - **不阻塞 UI 线程**：所有执行走传入的 [scope] 协程（ChatViewModel 用 viewModelScope）
 * - **状态机线性推进**：CONFIRMING → RUNNING → SUCCESS / FAILED；FAILED 经 [retry] 回到 CONFIRMING 或 RUNNING
 * - **并发安全**：[pendingDeferred] 用 [AtomicReference] 串行化确认流程（同时间至多一个 pending dialog）
 *
 * 关于并发确认的说明：
 * - [ToolExecutor.executeMultiple] 并行执行多个高级工具时，可能并发触发 confirmCallback
 * - 本实现用单一 [pendingDeferred] 串行化（后来者覆盖前者，但前者 await 永远阻塞）
 * - 当前调用方（[com.aibox.ui.chat.ChatViewModel.tryInvokeTool]）串行触发，无并发问题
 * - 后续 AgentRunner 若并行触发多个高级工具，应在外层串行化调用方
 */
class ToolInvocationViewModel(
    private val toolExecutor: ToolExecutor,
    private val toolRegistry: ToolRegistry,
    private val toolSettingsRepository: ToolSettingsRepository,
    private val scope: CoroutineScope
) {
    /** 当前活跃的工具调用列表（按提交顺序追加；UI 渲染时按列表顺序展示）。 */
    private val _invocations = MutableStateFlow<List<ToolInvocation>>(emptyList())
    val invocations: StateFlow<List<ToolInvocation>> = _invocations.asStateFlow()

    /** 当前待用户确认的工具调用；null 表示无 pending。 */
    private val _pending = MutableStateFlow<PendingToolConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingToolConfirmation?> = _pending.asStateFlow()

    /** 当前 await 中的 CompletableDeferred；用 AtomicReference 保证串行化解析。 */
    private val pendingDeferred = AtomicReference<CompletableDeferred<Boolean>?>(null)

    /** 每个 invocation 关联的执行上下文（sessionId + deviceInfo），用于重试时复用。 */
    private val invocationContexts = mutableMapOf<String, InvocationContext>()

    /**
     * 提交一次工具调用。
     *
     * 流程：
     * 1. 从 [toolRegistry] 查工具元信息（显示名 / 图标 / 是否需要确认）；找不到也允许创建（执行时返回失败）
     * 2. 创建 [ToolInvocation]，初始状态 = RUNNING（乐观）；高级工具若需确认会被 [runInvocation] 改回 CONFIRMING
     * 3. 把 invocation 加入 [_invocations] 列表
     * 4. 在 [scope] 中 launch [runInvocation]
     *
     * @param toolId 工具 id（与 [com.aibox.domain.tool.Tool.id] 一致）
     * @param params 参数 map（LLM function call 解析后传入）
     * @param sessionId 推理会话 ID（与 LogSystem 会话日志对齐）
     * @param deviceInfo 设备信息快照（由调用方在调用前构造）
     * @return 创建的 invocation id（用于 retry / cancel 等操作引用）
     */
    fun submit(
        toolId: String,
        params: Map<String, Any>,
        sessionId: String,
        deviceInfo: DeviceInfo
    ): String {
        val tool = toolRegistry.getTool(toolId)
        val invocationId = generateInvocationId()
        val needsConfirm = tool?.requiresConfirmation ?: false

        val initial = ToolInvocation(
            id = invocationId,
            toolId = toolId,
            toolDisplayName = tool?.displayName ?: toolId,
            toolIcon = tool?.icon ?: "?",
            params = params,
            state = ToolState.RUNNING,
            needsConfirmation = needsConfirm
        )
        invocationContexts[invocationId] = InvocationContext(sessionId, deviceInfo)
        _invocations.update { it + initial }
        LogSystem.info(TAG, "submit: id=$invocationId tool=$toolId params=${params.keys} needsConfirm=$needsConfirm")

        scope.launch { runInvocation(initial) }
        return invocationId
    }

    /**
     * 重试一次失败的调用。复用原 [ToolInvocation] 的 toolId / params + 关联的执行上下文。
     *
     * - 找不到 invocation 或 context → no-op + warn 日志
     * - 状态置 RUNNING（清空 error / result / progress），重新 launch [runInvocation]
     */
    fun retry(invocationId: String) {
        val existing = _invocations.value.firstOrNull { it.id == invocationId } ?: run {
            LogSystem.warn(TAG, "retry: invocation $invocationId not found")
            return
        }
        val ctx = invocationContexts[invocationId] ?: run {
            LogSystem.warn(TAG, "retry: context for $invocationId lost (likely App restart)")
            return
        }
        LogSystem.info(TAG, "retry: id=$invocationId tool=${existing.toolId}")
        updateInvocation(invocationId) {
            it.copy(
                state = ToolState.RUNNING,
                progress = null,
                result = null,
                error = null,
                executionTimeMs = 0
            )
        }
        scope.launch { runInvocation(existing.copy(state = ToolState.RUNNING)) }
    }

    /**
     * 用户点"确认执行"：解析当前 pending 为 true。
     * 没有 pending 时 no-op（避免误触）。
     */
    fun confirm() {
        resolvePending(true)
    }

    /**
     * 用户点"拒绝"：解析当前 pending 为 false。
     * 调用方的 confirmCallback 返回 false → [ToolRegistry.execute] 返回 Outcome.Failure("User declined")
     * → [runInvocation] 把 invocation 状态推到 FAILED。
     */
    fun deny() {
        resolvePending(false)
    }

    /**
     * 用户点"始终允许此工具"：
     * 1. 异步调 [ToolSettingsRepository.setAutoConfirm]（true）写入 DataStore
     * 2. 同步解析 pending 为 true（不等待 DataStore 写完成；下次同工具调用会跳过 dialog）
     */
    fun alwaysAllow() {
        val pending = _pending.value ?: return
        LogSystem.info(TAG, "alwaysAllow: tool=${pending.toolId} added to auto-confirm whitelist")
        scope.launch {
            runCatching { toolSettingsRepository.setAutoConfirm(pending.toolId, true) }
                .onFailure { LogSystem.error(TAG, "setAutoConfirm failed for ${pending.toolId}", it) }
        }
        resolvePending(true)
    }

    /** 清空所有历史 invocation（成功 + 失败）；不影响当前正在执行中的。UI 通常提供"清空历史"按钮。 */
    fun clearHistory() {
        val toKeep = _invocations.value.filter { it.state == ToolState.RUNNING || it.state == ToolState.CONFIRMING }
        // 不清空正在执行中的 invocation 对应的 context（防止 retry 时找不到）
        val toRemoveIds = _invocations.value.map { it.id } - toKeep.map { it.id }
        toRemoveIds.forEach { invocationContexts.remove(it) }
        _invocations.value = toKeep
        LogSystem.info(TAG, "clearHistory: kept ${toKeep.size} active, removed ${toRemoveIds.size} finished")
    }

    // ===================================================
    // 内部实现
    // ===================================================

    /**
     * 执行一次 invocation：构造 [ToolContext] → 调 [ToolExecutor.executeFunctionCall] → 更新状态。
     */
    private suspend fun runInvocation(invocation: ToolInvocation) {
        val ctx = invocationContexts[invocation.id]
        if (ctx == null) {
            updateInvocation(invocation.id) {
                it.copy(state = ToolState.FAILED, error = "Lost invocation context")
            }
            return
        }

        val tool = toolRegistry.getTool(invocation.toolId)
        if (tool == null) {
            updateInvocation(invocation.id) {
                it.copy(state = ToolState.FAILED, error = "Tool not found: ${invocation.toolId}")
            }
            return
        }

        // 高级工具且未在白名单 → 状态置 CONFIRMING（dialog 弹起时显示）；executor 内部会调 confirmCallback
        val needsConfirm = tool.requiresConfirmation &&
            !runCatching { toolSettingsRepository.isAutoConfirmed(invocation.toolId) }.getOrDefault(false)
        if (needsConfirm) {
            updateInvocation(invocation.id) { it.copy(state = ToolState.CONFIRMING) }
        } else {
            updateInvocation(invocation.id) { it.copy(state = ToolState.RUNNING, progress = null) }
        }

        val context = ToolContext(
            sessionId = ctx.sessionId,
            deviceInfo = ctx.deviceInfo,
            confirmCallback = { _, params ->
                awaitUserConfirmation(
                    invocationId = invocation.id,
                    toolId = tool.id,
                    toolDisplayName = tool.displayName,
                    toolIcon = tool.icon,
                    params = params
                )
            },
            progressCallback = { progress, _ ->
                updateInvocation(invocation.id) { it.copy(progress = progress) }
            }
        )

        updateInvocation(invocation.id) { it.copy(state = ToolState.RUNNING) }
        val startTime = System.currentTimeMillis()
        val outcome = runCatching {
            toolExecutor.executeFunctionCall(
                FunctionCall(name = invocation.toolId, arguments = invocation.params),
                context
            )
        }.getOrElse {
            LogSystem.error(TAG, "executeFunctionCall threw (contract violation): ${it.message}", it)
            Outcome.failure("ToolExecutor threw: ${it.message}", it)
        }
        val duration = System.currentTimeMillis() - startTime

        when (outcome) {
            is Outcome.Success -> {
                val result = outcome.value
                if (result.success) {
                    updateInvocation(invocation.id) {
                        it.copy(
                            state = ToolState.SUCCESS,
                            result = result,
                            executionTimeMs = duration,
                            progress = null
                        )
                    }
                    LogSystem.info(TAG, "runInvocation success: id=${invocation.id} tool=${invocation.toolId} ${duration}ms")
                } else {
                    updateInvocation(invocation.id) {
                        it.copy(
                            state = ToolState.FAILED,
                            error = result.error ?: "Tool returned failure",
                            executionTimeMs = duration
                        )
                    }
                    LogSystem.warn(TAG, "runInvocation tool-fail: id=${invocation.id} tool=${invocation.toolId} error=${result.error}")
                }
            }
            is Outcome.Failure -> {
                updateInvocation(invocation.id) {
                    it.copy(
                        state = ToolState.FAILED,
                        error = outcome.reason,
                        executionTimeMs = duration
                    )
                }
                LogSystem.warn(TAG, "runInvocation failed: id=${invocation.id} tool=${invocation.toolId} reason=${outcome.reason}")
            }
        }
    }

    /**
     * 把 confirmCallback 的 suspend 等待翻译成：
     * 1. 创建 [CompletableDeferred] 并塞入 [pendingDeferred]
     * 2. 把 [PendingToolConfirmation] push 到 [_pending]（UI 看到 dialog 弹起）
     * 3. await deferred（被 [confirm] / [deny] / [alwaysAllow] 解析）
     *
     * @return true 表示用户同意执行；false 表示拒绝
     */
    private suspend fun awaitUserConfirmation(
        invocationId: String,
        toolId: String,
        toolDisplayName: String,
        toolIcon: String,
        params: Map<String, Any>
    ): Boolean {
        // 若上一个 pending 未解析（罕见：调用方串行触发，但 dialog 仍在显示），先解析为 false 避免 hang
        pendingDeferred.get()?.let { if (!it.isCompleted) it.complete(false) }

        val deferred = CompletableDeferred<Boolean>()
        pendingDeferred.set(deferred)
        _pending.value = PendingToolConfirmation(
            invocationId = invocationId,
            toolId = toolId,
            toolDisplayName = toolDisplayName,
            toolIcon = toolIcon,
            params = params
        )
        LogSystem.info(TAG, "awaitUserConfirmation: waiting on user for $toolId (invocation=$invocationId)")
        val result = deferred.await()
        LogSystem.info(TAG, "awaitUserConfirmation: user responded $result for $toolId")
        return result
    }

    /** 解析当前 pending 并清空相关状态。 */
    private fun resolvePending(approved: Boolean) {
        pendingDeferred.getAndSet(null)?.takeIf { !it.isCompleted }?.complete(approved)
        _pending.value = null
    }

    /** 原子更新单条 invocation（按 id 匹配；找不到 no-op）。 */
    private fun updateInvocation(id: String, transform: (ToolInvocation) -> ToolInvocation) {
        _invocations.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
    }

    /** 生成短而唯一的 invocation id（纳秒 + 4 位随机后缀）。 */
    private fun generateInvocationId(): String {
        return "inv-${System.nanoTime()}-${Random.nextInt(1000, 9999)}"
    }

    /** 关联到 invocation 的执行上下文（用于 retry）。 */
    private data class InvocationContext(
        val sessionId: String,
        val deviceInfo: DeviceInfo
    )

    companion object {
        private const val TAG = "aibox.ui.tool.invocation"
    }
}
