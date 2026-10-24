package com.aibox.litert

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.aibox.plugin.IPluginInterface

/**
 * LiteRT-LM 插件入口 Service。
 *
 * 宿主通过 Intent Action `com.aibox.action.BACKEND_PLUGIN` 发现本服务,
 * 通过 [IPluginInterface] 调用推理功能,
 * 通过 [LiteRTDescriptor](由 manifest meta-data `aibox.descriptor_class` 暴露)获取元数据。
 *
 * 生命周期约定:
 *  - 宿主侧 `bindService` → 系统 onCreated 本 Service + `onBind` 返回 [LiteRTBinder]。
 *  - 宿主侧 `unbindService` → 系统 onDestroy;模型资源由 [LiteRTBackend.unload] 显式释放,
 *    依赖宿主先 unload 再 unbind,本 Service 不在 onDestroy 兜底(因为宿主崩溃时
 *    `unbindService` 不会触发,但进程死掉会让 binder 死通知 onServiceDisconnected;
 *    plugin 的 native model handle 随进程 death 自动释放)。
 *
 * 单 Service 双接口设计:
 *  - 本 Service 的 onBind 同时支持宿主通过 [IPluginInterface] 调用推理。
 *  - 宿主通过 `descriptor_class` meta-data 反射加载 [LiteRTDescriptor](独立 Stub),
 *    或直接 `binder.asInterface()` 强转(本工程让 LiteRTDescriptor 单独实例化,
 *    避免 Binder 上的 onTransact 分发因双接口冲突)。
 */
class LiteRTPluginService : Service() {

    override fun onCreate() {
        super.onCreate()
        // 仅打 logcat;宿主绑定后通过 IPluginLogCallback 把后续日志回传宿主 LogSystem。
        // 本插件进程级日志路径:`/data/data/com.aibox.litert.backend/...`(私有目录),
        // 但实际不写本地文件,所有日志都经 IPluginLogCallback 桥到宿主统一刷盘。
        android.util.Log.i(TAG, "LiteRT-LM plugin service created, ready to be discovered by host")
    }

    /**
     * onBind 返回 [LiteRTBinder](实现 [IPluginInterface])。
     *
     * 不区分 intent.action:本 Service 只接受 `com.aibox.action.BACKEND_PLUGIN` 一种 action
     * (manifest intent-filter 已限制),其余 action 视为非法绑定返回 null。
     */
    override fun onBind(intent: Intent?): IBinder? {
        if (intent == null) return null
        if (intent.action != null && intent.action != ACTION_BACKEND_PLUGIN) {
            android.util.Log.w(TAG, "Reject bind: unexpected action=${intent.action}")
            return null
        }
        return LiteRTBinder(this)
    }

    companion object {
        private const val TAG = "aibox.plugin.litert.service"

        /** 与 manifest intent-filter 对齐的 action。 */
        const val ACTION_BACKEND_PLUGIN = "com.aibox.action.BACKEND_PLUGIN"
    }
}
