package com.aibox.translate

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * 翻译模块的入口 Service。
 *
 * 宿主通过 Intent Action "com.aibox.action.TRANSLATE_MODULE" 发现本服务,
 * 然后通过 AIDL(ITranslateModuleInterface)拉取元数据 + 启动翻译会话。
 *
 * 生命周期:
 * - 第一次 bindService 时系统构造本服务并调 onBind,返回 [TranslateModuleBinder];
 * - 后续 bind 复用同一实例(Android Service 默认单例);
 * - 所有连接 unbind 后系统可能 destroy 本服务;本服务持有 [orchestrator]
 *   实例用于清理正在进行的翻译会话(避免协程泄漏)。
 *
 * 注意:
 * - 本服务不申请任何权限,不读写外部存储,不录音(SpeechRecognizer 由宿主调用)。
 * - 本服务不持有 LLM 模型;MT 推理由宿主 BackendManagerV2 在宿主进程完成,
 *   本服务通过 [TranslationOrchestrator] + 翻译宿主桥(TranslationHostBridge)
 *   协调三步工作流。
 * - 本服务运行在模块自己的进程(applicationId = com.aibox.translate.module),
 *   宿主跨进程通过 AIDL 调用;因此 orchestrator 内的所有耗时操作都跑在
 *   模块进程的后台线程,不阻塞 binder 线程。
 */
class TranslateModuleService : Service() {

    companion object {
        private const val TAG = "TranslateModuleService"
        private const val MODULE_JSON_ASSET = "module.json"
    }

    /**
     * 翻译协调器实例。
     *
     * 当前骨架版本未注入真实 [TranslationHostBridge](等宿主侧补 AIDL 反向接口);
     * 启动翻译会话时若桥为 null,会立即返回 Failure("host_bridge_not_attached"),
     * 便于宿主侧尽早发现"未连接桥"的错误路径。
     *
     * 未来工作:在 onCreate 中通过反向 bindService 到宿主或 AIDL 注入器,
     * 拿到 IHostTranslationServices 后构造 [TranslationHostBridge] 实现并赋给 orchestrator。
     */
    private val orchestrator: TranslationOrchestrator = TranslationOrchestrator(hostBridge = null)

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "TranslateModuleService created; ready to be discovered by host.")
    }

    override fun onBind(intent: Intent?): IBinder? {
        // 不论 intent action 是什么,只要宿主绑定本服务就返回同一个 Binder。
        // 宿主侧会通过 ITranslateModuleInterface.Stub.asInterface(binder) 转换。
        return TranslateModuleBinder(this, orchestrator)
    }

    /** 提供模块元数据(assets/module.json 原文)。失败时返回空串,不抛异常。 */
    fun getModuleMetadata(): String {
        return runCatching {
            assets.open(MODULE_JSON_ASSET).bufferedReader().use { it.readText() }
        }.getOrElse { e ->
            Log.e(TAG, "Failed to read $MODULE_JSON_ASSET from assets", e)
            ""
        }
    }

    /**
     * 提供支持的语言列表(JSON 数组字符串)。
     *
     * 当前实现:从 module.json 的 "supportedLanguages" 字段提取并返回。
     * 失败时返回空串。
     *
     * 注意:本方法同步执行 assets 读取 + JSON 解析,binder 调用方在 worker 线程,
     * 不会阻塞 UI;解析结果小(<1KB),无需缓存。
     */
    fun getSupportedLanguages(): String {
        return runCatching {
            val raw = assets.open(MODULE_JSON_ASSET).bufferedReader().use { it.readText() }
            // 极简 JSON 字段提取:用正则截取 "supportedLanguages" 数组的内容。
            // 本模块的 module.json 是受控的(由本工程自己写),不依赖第三方 JSON 库;
            // 宿主侧有完整 kotlinx.serialization 可二次解析,这里只做最小转发。
            val regex = Regex("\"supportedLanguages\"\\s*:\\s*(\\[.*?\\])", RegexOption.DOT_MATCHES_ALL)
            regex.find(raw)?.groupValues?.getOrNull(1) ?: "[]"
        }.getOrElse { e ->
            Log.e(TAG, "Failed to extract supportedLanguages from module.json", e)
            ""
        }
    }

    /**
     * 销毁时取消所有正在进行的翻译会话,避免协程泄漏。
     * 翻译会话可能持续数十秒(ASR + MT + TTS),Service 被销毁时必须主动停止。
     */
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "TranslateModuleService destroyed; cancelling in-flight translations.")
        orchestrator.cancelAll()
    }
}
