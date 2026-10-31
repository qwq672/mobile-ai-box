package com.aibox.core.common

import kotlinx.coroutines.flow.Flow

/**
 * 资源 Outcome 模型：所有跨层调用统一返回 Outcome 而非抛异常。
 *
 * 这是 AIBox 反屎山的"零异常约定"：
 * - 上层调用永远不靠 try/catch 处理业务错误
 * - 所有可预期错误（模型缺失、NPU 不可用、OOM）走 [Outcome.Failure]
 * - 仅不可预期崩溃（空指针/索引越界）允许抛异常
 *
 * 借鉴 kotlin.Result 但更明确：不混淆"成功无值"与"失败"两种状态。
 */
public sealed interface Outcome<out T> {
    public data class Success<T>(val value: T) : Outcome<T>
    public data class Failure(val error: AIBoxError) : Outcome<Nothing>

    /** 把 Outcome 转为 Flow.emit，便于在协程流中传播 */
    public fun toFlow(): Flow<T> = kotlinx.coroutines.flow.flow {
        when (this@Outcome) {
            is Success -> emit(value)
            is Failure -> throw error.asException()
        }
    }

    public companion object {
        public fun success(value: Unit): Outcome<Unit> = Success(value)
        public fun failure(error: AIBoxError): Outcome<Nothing> = Failure(error)
    }
}

/** 统一错误类型：所有模块必须用 [AIBoxError] 子类型表达错误 */
public sealed class AIBoxError(
    public val code: String,
    public val message: String,
    public val cause: Throwable? = null,
) {
    public class ModelNotFound(path: String) : AIBoxError("model.not_found", "model not found: $path")

    public class BackendUnavailable(name: String, reason: String) :
        AIBoxError("backend.unavailable", "backend [$name] unavailable: $reason")

    public class NpuNotSupported(reason: String) :
        AIBoxError("npu.not_supported", "NPU not supported: $reason")

    public class InferenceFailed(reason: String, cause: Throwable? = null) :
        AIBoxError("inference.failed", reason, cause)

    public class PluginConnectionError(pluginId: String, reason: String) :
        AIBoxError("plugin.connection", "plugin [$pluginId] connection failed: $reason")

    public class PluginSignatureInvalid(pluginId: String) :
        AIBoxError("plugin.signature", "plugin [$pluginId] signature verification failed")

    public class DownloadInterrupted(url: String, atBytes: Long) :
        AIBoxError("download.interrupted", "download of $url interrupted at $atBytes bytes")

    public class Oom(reason: String) : AIBoxError("system.oom", "out of memory: $reason")

    public class Generic(code: String, message: String, cause: Throwable? = null) :
        AIBoxError(code, message, cause)

    public fun asException(): AIBoxException = AIBoxException(this)
}

public class AIBoxException(public val error: AIBoxError) : Exception(error.message, error.cause)
