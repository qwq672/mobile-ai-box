package com.aibox.litert

import com.aibox.plugin.IPluginInterface
import com.aibox.plugin.IPluginLogCallback
import com.aibox.plugin.IStreamCallback
import com.aibox.plugin.InferRequest
import com.aibox.plugin.InferResult
import com.aibox.plugin.LoadModelRequest
import com.aibox.plugin.LoadModelResult
import com.aibox.plugin.PluginInfo
import com.aibox.litert.backend.LiteRTBackend

/**
 * [IPluginInterface] 的具体实现(Stub)。
 *
 * 持有 [LiteRTBackend] 单例(一个插件进程通常只加载一组模型;若宿主并发加载多模型,
 * [LiteRTBackend] 内部用 modelHandle Map 区分)。
 *
 * 零异常约定:所有方法用 try-catch,失败路径都通过 [LoadModelResult] / [InferResult] 的
 * `success=false + errorMessage` 返回,不抛 RemoteException 给宿主业务层。
 * RemoteException 仍可能因 binder 死亡而抛出,但那已是宿主侧问题,不属于本类职责。
 *
 * @param service 持有的 Service(用于查 packageName 等)。
 */
class LiteRTBinder(private val service: LiteRTPluginService) : IPluginInterface.Stub() {

    private val backend = LiteRTBackend()
    private val logTag = "aibox.plugin.litert.binder"

    override fun getPluginInfo(): PluginInfo {
        val info = PluginInfo()
        info.packageName = service.packageName ?: "com.aibox.litert.backend"
        info.descriptorClass = "com.aibox.litert.LiteRTDescriptor"
        info.version = "1.0.0"
        info.displayName = "LiteRT-LM"
        // sizeBytes 用 plugin.json 中的 sizeBytes(5MB);实际 APK 含 .so 后会更大,
        // 但本字段是"插件声明的大小",宿主可后续 PackageManager.getPackageInfo 拿真实大小。
        info.sizeBytes = 5L * 1024 * 1024
        return info
    }

    // === 模型加载 ===

    override fun loadModel(request: LoadModelRequest?): LoadModelResult {
        if (request == null) {
            return fail("LoadModelRequest is null")
        }
        val modelPath = request.modelPath?.takeIf { it.isNotBlank() }
            ?: return fail("modelPath is empty")
        val configJson = request.configJson ?: "{}"
        return try {
            val handle = backend.loadModel(modelPath, configJson)
            LiteRTLogger.info(logTag, "loadModel ok: path=$modelPath handle=$handle")
            val result = LoadModelResult()
            result.success = true
            result.modelHandle = handle
            result.errorMessage = ""
            result
        } catch (e: Throwable) {
            LiteRTLogger.error(logTag, "loadModel failed: path=$modelPath", e)
            fail("load failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override fun unload(modelHandle: String?) {
        if (modelHandle.isNullOrBlank()) {
            LiteRTLogger.warn(logTag, "unload called with null/empty handle, ignored")
            return
        }
        try {
            backend.unload(modelHandle)
            LiteRTLogger.info(logTag, "unload ok: handle=$modelHandle")
        } catch (e: Throwable) {
            // unload 失败仅日志,不抛异常(与宿主 V2 unload 零异常约定对齐)。
            LiteRTLogger.warn(logTag, "unload failed: handle=$modelHandle", e)
        }
    }

    // === 同步推理 ===

    override fun infer(request: InferRequest?, modelHandle: String?): InferResult {
        if (request == null) return inferFail("InferRequest is null")
        if (modelHandle.isNullOrBlank()) return inferFail("modelHandle is empty")
        return try {
            // 把 InferRequest 转成 JSON 传给 backend(backend 内部解析)
            val requestJson = inferRequestToJson(request)
            val resultJson = backend.infer(requestJson)
            LiteRTLogger.debug(logTag, "infer ok: handle=$modelHandle taskId=${request.taskId}")
            val result = InferResult()
            result.success = true
            result.resultJson = resultJson
            result.errorMessage = ""
            result
        } catch (e: Throwable) {
            LiteRTLogger.error(logTag, "infer failed: handle=$modelHandle taskId=${request.taskId}", e)
            inferFail("infer failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // === 流式推理 ===

    override fun streamInfer(
        request: InferRequest?,
        modelHandle: String?,
        callback: IStreamCallback?
    ) {
        if (request == null || modelHandle.isNullOrBlank() || callback == null) {
            LiteRTLogger.warn(logTag, "streamInfer rejected: null args")
            try {
                callback?.onError("invalid arguments: request/handle/callback is null")
            } catch (_: Throwable) { /* ignore */ }
            return
        }
        // 实际推理在后台线程执行(AIDL onTransact 在 binder 工作线程,不应阻塞)。
        // 骨架版直接在调用线程同步生成空 chunk + done,后续接入真实 LiteRT-LM SDK
        // 后改为单独后台线程 + 逐 token 回调。
        try {
            // 把 InferRequest 转成 JSON 传给 backend
            val requestJson = inferRequestToJson(request)
            // 把 AIDL IStreamCallback 包装成 LiteRTBackend.StreamCallback
            val streamCallback = object : com.aibox.litert.backend.LiteRTBackend.StreamCallback {
                override fun onChunk(partial: String, done: Boolean) {
                    try {
                        callback.onChunk(partial, done)
                    } catch (_: Throwable) { /* ignore remote exception */ }
                }
                override fun onError(reason: String) {
                    try {
                        callback.onError(reason)
                    } catch (_: Throwable) { /* ignore */ }
                }
            }
            backend.streamInfer(requestJson, streamCallback)
        } catch (e: Throwable) {
            LiteRTLogger.error(logTag, "streamInfer failed: handle=$modelHandle taskId=${request.taskId}", e)
            try {
                callback.onError("streamInfer failed: ${e.message ?: e.javaClass.simpleName}")
            } catch (_: Throwable) { /* ignore */ }
        }
    }

    // === 优化开关 ===

    override fun applyOptimizations(flags: MutableMap<String, String>?) {
        if (flags == null) {
            LiteRTLogger.warn(logTag, "applyOptimizations called with null flags, ignored")
            return
        }
        try {
            // AIDL 不支持 Map<String,Boolean>,用 String "true"/"false" 表示
            // 转 Boolean Map 给 backend
            val boolMap = flags.mapValues { it.value.toBooleanStrictOrNull() ?: (it.value == "true") }
            backend.applyOptimizations(boolMap)
            LiteRTLogger.debug(logTag, "applyOptimizations ok: ${flags.size} flags")
        } catch (e: Throwable) {
            LiteRTLogger.warn(logTag, "applyOptimizations failed", e)
        }
    }

    // === 日志回调注入 ===

    override fun setLogCallback(callback: IPluginLogCallback?) {
        LiteRTLogger.setCallback(callback)
        // 注入后立即打一条 info,让宿主 LogSystem 看到桥接已建立(本轮日志会经宿主刷盘)。
        LiteRTLogger.info(logTag, "log bridge installed by host")
    }

    // === 内部 helper ===

    private fun fail(message: String): LoadModelResult {
        val result = LoadModelResult()
        result.success = false
        result.modelHandle = ""
        result.errorMessage = message
        return result
    }

    private fun inferFail(message: String): InferResult {
        val result = InferResult()
        result.success = false
        result.resultJson = ""
        result.errorMessage = message
        return result
    }

    /**
     * 把 AIDL InferRequest Parcelable 转成 JSON 字符串,传给 LiteRTBackend。
     * JSON 格式: {"taskId": "...", "taskType": ..., "inputs": [{"type": "text", "content": "..."}], ...}
     */
    private fun inferRequestToJson(request: InferRequest): String {
        return try {
            val sb = StringBuilder()
            sb.append("{\"taskId\":\"${request.taskId}\"")
            sb.append(",\"taskType\":${request.taskType}")
            sb.append(",\"modelId\":\"${request.modelId}\"")
            sb.append(",\"stream\":${request.stream}")
            sb.append(",\"sessionId\":\"${request.sessionId ?: ""}\"")
            // inputJsons 是 List<String> (每项是 JSON 字符串)
            sb.append(",\"inputs\":[")
            val inputs = request.inputJsons ?: emptyList<String>()
            inputs.forEachIndexed { i, json ->
                if (i > 0) sb.append(",")
                sb.append(json)
            }
            sb.append("]")
            // configJson 直接拼
            sb.append(",\"configJson\":${request.configJson ?: "{}"}")
            sb.append("}")
            sb.toString()
        } catch (e: Throwable) {
            // fallback: 返回最小 JSON,backend 解析失败时按默认处理
            "{}"
        }
    }
}
