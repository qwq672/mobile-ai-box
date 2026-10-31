package com.aibox.translate

/**
 * 零异常约定的统一结果类型(本模块自带的轻量实现)。
 *
 * 与宿主工程 [com.aibox.util.outcome.Outcome] 同名同语义,但不跨进程共享:
 * - 宿主工程的 Outcome 在 com.aibox.util.outcome 包,属于宿主 APK;
 * - 模块 APK 是独立工程,编译期不依赖宿主源码,故本模块自带一份局部 Outcome。
 * - 两者实现等价(均 sealed class + Success / Failure),仅包名不同;
 *   未来若把宿主 Outcome 抽到共享 AAR,本文件可删除并改 import 即可。
 *
 * 使用示例:
 * ```
 * val r: Outcome<String> = orchestrator.startTranslation(...)
 *     .map { it.sessionId }
 *     .onFailure { reason, cause -> Log.e(TAG, reason, cause) }
 * ```
 */
sealed class Outcome<out T> {

    /** 成功结果,携带值 [value]。 */
    data class Success<T>(val value: T) : Outcome<T>()

    /**
     * 失败结果。
     *
     * @property reason 人类可读的失败原因。
     * @property cause 触发失败的原始异常;可空。
     */
    data class Failure(
        val reason: String,
        val cause: Throwable? = null
    ) : Outcome<Nothing>()

    /** 映射成功值;失败原样透传。 */
    inline fun <R> map(transform: (T) -> R): Outcome<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    /** flatMap;失败原样透传。 */
    inline fun <R> flatMap(transform: (T) -> Outcome<R>): Outcome<R> = when (this) {
        is Success -> transform(value)
        is Failure -> this
    }

    /** 成功时执行 [block],返回原 Outcome(便于链式副作用)。 */
    inline fun onSuccess(block: (T) -> Unit): Outcome<T> {
        if (this is Success) block(value)
        return this
    }

    /** 失败时执行 [block],返回原 Outcome。 */
    inline fun onFailure(block: (String, Throwable?) -> Unit): Outcome<T> {
        if (this is Failure) block(reason, cause)
        return this
    }

    /** 成功则返回值,失败返回 null。 */
    fun getOrNull(): T? = if (this is Success) value else null

    /** 失败则返回原因字符串,成功返回 null。 */
    fun reasonOrNull(): String? = if (this is Failure) reason else null

    companion object {
        fun <T> success(value: T): Outcome<T> = Success(value)
        fun failure(reason: String, cause: Throwable? = null): Outcome<Nothing> = Failure(reason, cause)

        /** 把可能抛异常的块包装为 Outcome。 */
        inline fun <T> catching(block: () -> T): Outcome<T> = try {
            Success(block())
        } catch (t: Throwable) {
            Failure(t.message ?: t.javaClass.simpleName, t)
        }
    }
}
