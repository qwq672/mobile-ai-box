package com.aibox.util.outcome

/**
 * 零异常约定的统一结果类型。
 *
 * 设计原则：
 * - 所有可失败的 API 返回 `Outcome<T>` 而非抛异常。
 * - `Success` 携带值；`Failure` 携带原因字符串 + 可选 cause。
 * - 提供 [map] / [flatMap] / [onSuccess] / [onFailure] / [getOrNull] / [reasonOrNull] 链式 API。
 *
 * 使用示例：
 * ```
 * val r: Outcome<Int> = backend.loadModel(path, config)
 *     .map { it.id.length }
 *     .onFailure { reason, cause -> LogSystem.error(tag, reason, cause) }
 * ```
 *
 * 注：与 Kotlin 内置的 `Result` 相比，`Outcome` 把"失败"明确建模为值，
 * 不与异常机制耦合，便于跨进程 / 跨模块传递，也便于插件返回结构化错误。
 */
sealed class Outcome<out T> {

    /** 成功结果，携带值 [value]。 */
    data class Success<T>(val value: T) : Outcome<T>()

    /**
     * 失败结果。
     *
     * @property reason 人类可读的失败原因，如 "Model file not found"。
     * @property cause 触发失败的原始异常；可空（如纯逻辑校验失败时为 null）。
     */
    data class Failure(
        val reason: String,
        val cause: Throwable? = null
    ) : Outcome<Nothing>()

    /** 映射成功值；失败原样透传。 */
    inline fun <R> map(transform: (T) -> R): Outcome<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    /** flatMap；失败原样透传。 */
    inline fun <R> flatMap(transform: (T) -> Outcome<R>): Outcome<R> = when (this) {
        is Success -> transform(value)
        is Failure -> this
    }

    /** 成功时执行 [block]，返回原 Outcome（不改变值），便于链式日志 / 副作用。 */
    inline fun onSuccess(block: (T) -> Unit): Outcome<T> {
        if (this is Success) block(value)
        return this
    }

    /** 失败时执行 [block]，返回原 Outcome，便于链式日志 / 错误上报。 */
    inline fun onFailure(block: (String, Throwable?) -> Unit): Outcome<T> {
        if (this is Failure) block(reason, cause)
        return this
    }

    /** 成功则返回值，失败返回 null。 */
    fun getOrNull(): T? = if (this is Success) value else null

    /** 失败则返回原因字符串，成功返回 null。 */
    fun reasonOrNull(): String? = if (this is Failure) reason else null

    companion object {
        /** 快捷构造成功结果。 */
        fun <T> success(value: T): Outcome<T> = Success(value)

        /** 快捷构造失败结果。 */
        fun failure(reason: String, cause: Throwable? = null): Outcome<Nothing> =
            Failure(reason, cause)

        /**
         * 把可能抛异常的块包装为 Outcome：成功返回 Success(value)，失败返回 Failure(message, throwable)。
         * 用于过渡期包装旧的 throw 风格 API。
         */
        inline fun <T> catching(block: () -> T): Outcome<T> = try {
            Success(block())
        } catch (t: Throwable) {
            Failure(t.message ?: t.javaClass.simpleName, t)
        }
    }
}
