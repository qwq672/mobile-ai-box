package com.aibox.translate

import android.util.Log
import com.aibox.plugin.ITranslateCallback
import com.aibox.plugin.ITranslateModuleInterface

/**
 * [TranslateModuleService] 的 AIDL Stub 实现。
 *
 * 继承自由 AIDL 工具生成的 [ITranslateModuleInterface.Stub],
 * 自动实现跨进程的 onTransact 分发。
 *
 * 注意:
 * - 本类持有的 [service] 是 Service 上下文,只用它读 assets,不做其他事。
 * - 本类持有的 [orchestrator] 是协调器;所有翻译请求都委托给它执行。
 * - 所有方法都不会抛 RemoteException 给宿主:跨进程异常会被 Binder 框架转成
 *   RemoteException 抛在调用侧,但本类内部已用 runCatching 兜底把异常转成
 *   "语义化失败值"(空串 / null / 错误回调),让宿主代码更简洁。
 */
class TranslateModuleBinder(
    private val service: TranslateModuleService,
    private val orchestrator: TranslationOrchestrator
) : ITranslateModuleInterface.Stub() {

    companion object {
        private const val TAG = "TranslateModuleBinder"
    }

    /** @see TranslateModuleService.getModuleMetadata */
    override fun getModuleMetadata(): String = service.getModuleMetadata()

    /** @see TranslateModuleService.getSupportedLanguages */
    override fun getSupportedLanguages(): String = service.getSupportedLanguages()

    /**
     * 启动一次翻译会话;委托 [TranslationOrchestrator.startTranslation] 异步执行。
     *
     * 返回 sessionId(若入参为空则 orchestrator 内部生成 UUID);若 orchestrator
     * 立即拒绝(如 hostBridge 为 null),仍返回 sid 并通过 callback 报错。
     *
     * 注意:本方法签名同步返回 String,但翻译过程异步;宿主应通过
     * [setTranslationCallback] 已注入的 [ITranslateCallback] 接收事件。
     */
    override fun startTranslation(
        sourceText: String,
        sourceLang: String,
        targetLang: String,
        sessionId: String
    ): String {
        // orchestrator 内部已对 blank sessionId 做兜底(UUID.randomUUID),
        // 这里直接透传,无需在 binder 层重复处理。
        return runCatching {
            orchestrator.startTranslation(
                sourceText = sourceText,
                sourceLang = sourceLang,
                targetLang = targetLang,
                sessionId = sessionId
            )
        }.getOrElse { e ->
            Log.e(TAG, "Failed to start translation", e)
            ""
        }
    }

    /**
     * 取消正在进行的翻译会话。
     * 委托 [TranslationOrchestrator.cancel];幂等(不存在 / 已结束 no-op)。
     */
    override fun cancelTranslation(sessionId: String) {
        runCatching { orchestrator.cancel(sessionId) }
            .onFailure { Log.w(TAG, "cancelTranslation failed for $sessionId", it) }
    }

    /**
     * 注入翻译回调;委托 [TranslationOrchestrator.setCallback]。
     * 传 null 表示解除回调。
     */
    override fun setTranslationCallback(callback: ITranslateCallback?) {
        runCatching { orchestrator.setCallback(callback) }
            .onFailure { Log.w(TAG, "setTranslationCallback failed", it) }
    }
}
