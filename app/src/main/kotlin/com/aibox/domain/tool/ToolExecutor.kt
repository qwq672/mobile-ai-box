package com.aibox.domain.tool

import com.aibox.util.log.LogSystem
import com.aibox.util.outcome.Outcome
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 工具执行器（B1 工具调用抽象层）。
 *
 * AgentRunner 与 [ToolRegistry] 之间的薄适配层：
 * - 接收 LLM 输出的 [FunctionCall]（由 LLM 输出 parser 构造），转交 [ToolRegistry.execute]
 * - [executeMultiple] 支持 LLM 并行 function calling（OpenAI / Anthropic 都支持单回复中
 *   多个 tool_calls，可用此 API 并发执行）
 *
 * 设计原则：
 * - 零异常约定：所有路径返回 [Outcome]，不抛出界
 * - 并行执行用 [coroutineScope] + [async] + [awaitAll]：
 *   - 每个 [FunctionCall] 跑在独立 async 中，互不阻塞
 *   - 若任一执行抛异常（理论不应发生，[ToolRegistry.execute] 已兜底），coroutineScope 会取消
 *     其他 async 并向上传播；调用方需自己 try-catch
 * - 同名调用去重限制：返回的 Map 以 [FunctionCall.name] 为 key，若 [calls] 中有多个
 *   同名 call（罕见，LLM 通常并行调用不同工具），最后一个的 Outcome 会覆盖前者。
 *   若需保留所有调用结果，应改为 `List<Pair<String, Outcome<ToolResult>>>` 或加 call_id。
 *
 * 关于确认回调并发的说明：
 * - 多个高级工具并行执行时，[ToolContext.confirmCallback] 可能被并发调用
 * - 若 UI 用 dialog 实现 confirmCallback，应自行串行化（如 Mutex + queue）避免同时弹多个 dialog
 * - 本层不强制串行，以保持调用语义简单
 */
class ToolExecutor(
    private val registry: ToolRegistry
) {
    /**
     * 执行单个 function call。
     *
     * 直接转交 [ToolRegistry.execute]，由后者处理开关检查 / 确认 / 异常兜底。
     */
    suspend fun executeFunctionCall(
        functionCall: FunctionCall,
        context: ToolContext
    ): Outcome<ToolResult> {
        LogSystem.debug(TAG, "executeFunctionCall: name=${functionCall.name}, args=${functionCall.arguments}")
        return registry.execute(functionCall.name, functionCall.arguments, context)
    }

    /**
     * 并行执行多个 function call。
     *
     * - 用 [coroutineScope] + [async] 让每个 call 在独立协程中跑
     * - [awaitAll] 等所有完成；任一抛异常会取消其他（但 [ToolRegistry.execute] 内部已 catch，
     *   正常情况下不会抛）
     * - 返回 Map<name, Outcome>：以 [FunctionCall.name] 为 key
     *   - 注：若 calls 中有重名 call，后者覆盖前者
     *
     * 使用示例：
     * ```
     * val outcomes = executor.executeMultiple(
     *     listOf(
     *         FunctionCall("web_search", mapOf("query" to "天气")),
     *         FunctionCall("calculator", mapOf("expr" to "1+1"))
     *     ),
     *     context
     * )
     * outcomes.forEach { (name, outcome) ->
     *     println("$name -> ${outcome.getOrNull()?.output}")
     * }
     * ```
     */
    suspend fun executeMultiple(
        calls: List<FunctionCall>,
        context: ToolContext
    ): Map<String, Outcome<ToolResult>> = coroutineScope {
        if (calls.isEmpty()) return@coroutineScope emptyMap()

        LogSystem.info(TAG, "executeMultiple: ${calls.size} calls in parallel: ${calls.map { it.name }}")
        val deferred = calls.map { call ->
            // async 让每个调用跑在独立协程中；coroutineScope 提供作用域
            async { call.name to executeFunctionCall(call, context) }
        }
        deferred.awaitAll().toMap()
    }

    companion object {
        private const val TAG = "aibox.tool.executor"
    }
}

/**
 * LLM function call 解析结果。
 *
 * 由 AgentRunner 的 LLM 输出 parser 构造（OpenAI tool_calls / Anthropic tool_use blocks
 * 解析后填入此结构），交给 [ToolExecutor] 执行。
 *
 * @param name 工具 id（与 [Tool.id] 一致）
 * @param arguments 参数 map；值类型由 LLM 输出的 JSON 决定（基础类型 + 嵌套 object/array）
 */
data class FunctionCall(
    val name: String,
    val arguments: Map<String, Any>
)
