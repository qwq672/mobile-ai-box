package com.easychat

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.io.InputStream

/**
 * Easy Chat 模块的入口 Service。
 *
 * 宿主通过 Intent Action "com.aibox.action.CHAT_MODULE" 发现本服务,
 * 然后通过 AIDL(IChatModuleInterface)拉取元数据 + 模型文件输入流。
 *
 * 生命周期:
 * - 第一次 bindService 时系统构造本服务并调 onBind,返回 [EasyChatModuleBinder];
 * - 后续 bind 复用同一实例(Android Service 默认单例);
 * - 所有连接 unbind 后系统可能 destroy 本服务,但本服务无状态可清理。
 *
 * 注意:
 * - 本服务不申请任何权限,不读写外部存储,模型从 assets 读出后通过
 *   ParcelFileDescriptor 跨进程交给宿主,宿主负责把它复制到自己的私有目录。
 * - assets 中的 qwen2.5-0.5b-instruct-q4_k_m.gguf 实际文件由 CI/CD 注入
 *   (见 README.md 的"构建与模型注入"章节),源码工程中只放 .gitkeep 占位。
 */
class EasyChatModuleService : Service() {

    companion object {
        private const val TAG = "EasyChatModuleService"
        private const val MODULE_JSON_ASSET = "module.json"
        private const val MODEL_FILE_ASSET = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "EasyChatModuleService created; ready to be discovered by host.")
    }

    override fun onBind(intent: Intent?): IBinder? {
        // 不论 intent action 是什么,只要宿主绑定本服务就返回同一个 Binder。
        // 宿主侧会通过 IChatModuleInterface.Stub.asInterface(binder) 转换。
        // 如果将来需要支持多个接口,可以根据 intent.action 区分返回不同 Binder。
        return EasyChatModuleBinder(this)
    }

    /** 提供模块元数据(结构化的 module.json 原文)。失败时返回空串,不抛异常。 */
    fun getModuleMetadata(): String {
        return runCatching {
            assets.open(MODULE_JSON_ASSET).bufferedReader().use { it.readText() }
        }.getOrElse { e ->
            Log.e(TAG, "Failed to read $MODULE_JSON_ASSET from assets", e)
            ""
        }
    }

    /**
     * 提供模型文件大小(字节)。失败返回 -1。
     *
     * 用 AssetFileDescriptor.length 比 InputStream.available 可靠:
     * available 在压缩资产上可能返回 -1 或不准确的值。
     */
    fun getModelFileSize(): Long {
        return runCatching {
            assets.openFd(MODEL_FILE_ASSET).use { it.length }
        }.getOrElse { e ->
            // openFd 在文件不存在(只有占位 .gitkeep)时抛 FileNotFoundException,
            // 这是开发期正常现象——CI 注入模型前本方法返回 -1。
            Log.w(TAG, "Model asset $MODEL_FILE_ASSET not found (CI not injected yet?)", e)
            -1L
        }
    }

    /**
     * 提供模型文件输入流,让宿主复制到自己的目录。
     *
     * 调用方(EasyChatModuleBinder)会把本流包装成 ParcelFileDescriptor
     * 跨进程传回宿主,宿主侧必须最终 close 该 pfd(进而 close 本流)。
     *
     * 若 assets 中没有真实模型文件(开发期),抛 FileNotFoundException;
     * Binder 会捕获并返回 null 给宿主。
     */
    fun openModelFile(): InputStream {
        return assets.open(MODEL_FILE_ASSET)
    }
}
