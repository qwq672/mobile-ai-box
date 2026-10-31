package com.aibox.core.common

/**
 * 全局 Dispatcher 规约。
 *
 * 反屎山原则：
 * - 任何模块不得直接 import androidx Dispatchers.IO/Main
 * - 必须通过 [AIBoxDispatchers] 抽象注入，便于测试替换
 * - NPU/推理重型任务用 [compute] 调度器，避免与 IO 抢资源
 */
public interface AIBoxDispatchers {
    /** 主线程，用于 UI 更新 */
    public val main: kotlin.coroutines.CoroutineContext

    /** 网络/磁盘 IO */
    public val io: kotlin.coroutines.CoroutineContext

    /** 推理/NPU 计算专用，并发度受限 */
    public val compute: kotlin.coroutines.CoroutineContext

    /** 单线程串行：用于需要严格顺序的本地任务（如模型加载） */
    public val single: kotlin.coroutines.CoroutineContext

    public companion object
}

/**
 * 默认生产实现。
 *
 * - compute 默认 = Dispatchers.Default，但并发度通过 Semaphore 限制为 CPU 核数/2
 *   推理任务本身是阻塞性的，不该抢占全部 CPU 核
 * - single 用.newSingleThreadContext 永久保活
 */
public class DefaultAIBoxDispatchers : AIBoxDispatchers {
    override val main: kotlin.coroutines.CoroutineContext = kotlinx.coroutines.Dispatchers.Main
    override val io: kotlin.coroutines.CoroutineContext = kotlinx.coroutines.Dispatchers.IO
    override val compute: kotlin.coroutines.CoroutineContext = kotlinx.coroutines.Dispatchers.Default
    override val single: kotlin.coroutines.CoroutineContext =
        kotlinx.coroutines.newSingleThreadContext("aibox-single")
}
