package com.aibox.infra.plugin

import android.os.RemoteException
import com.aibox.domain.inference.BackendCapabilities
import com.aibox.domain.inference.InferenceBackendV2
import com.aibox.domain.inference.InferenceConfigV2
import com.aibox.domain.inference.InferenceRequest
import com.aibox.domain.inference.InferenceResult
import com.aibox.domain.inference.LoadedModel
import com.aibox.domain.inference.ModalityOutput
import com.aibox.domain.inference.OptimizationFlags
import com.aibox.util.outcome.Outcome
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
// AIDL 接口
import com.aibox.plugin.IPluginInterface
import com.aibox.plugin.IPluginDescriptor
import com.aibox.plugin.LoadModelRequest
import com.aibox.plugin.LoadModelResult
import com.aibox.plugin.InferRequest as InferRequestParcel
import com.aibox.plugin.InferResult as InferResultParcel
import com.aibox.plugin.IStreamCallback

/**
 * 把 [com.aibox.plugin.IPluginInterface] 适配为 V2 [InferenceBackendV2]。
 *
 * 设计原则：
 * 1. **零异常约定**：所有 AIDL 调用都 try-catch RemoteException，转成 [Outcome.Failure]；
 *    `streamInfer` 通过 emit `done=true` 的错误 chunk 体现失败。
 * 2. **桥接 callback → Flow**：AIDL 的 `IStreamCallback` 是回调式 API；
 *    Kotlin V2 用 [Flow]；用 [callbackFlow] 把回调桥为冷流，订阅时才发起 streamInfer。
 * 3. **不可变映射**：[LoadedModel] 的 capabilities 来自 [descriptor]（descriptor 的快照）；
 *    不缓存运行期变化（如热更新插件后需重新 enable）。
 * 4. **JSON 桥**：复杂类型（InferenceConfigV2 / ModalityInput / InferenceResult / ModalityOutput）
 *    通过 [ParcelableConverters] 序列化为 JSON 字符串跨进程传递。
 *
 * 不实现 `unload` 之外的清理逻辑（如取消推理）；推理取消由 Flow 取消触发，本适配器
 * 在 [callbackFlow] 的 [awaitClose] 中不做主动取消（AIDL 不支持取消单次推理，
 * 后续如需支持需在 IPluginInterface 加 cancel 方法）。
 *
 * @property pluginInterface 插件运行时接口（通过 bindService 获取的 IBinder.asInterface()）。
 * @property descriptor 插件元信息接口（能力 / 设置 schema）。可以与 pluginInterface 来自同一个 Binder，
 *   也可以来自不同 Binder（取决于插件实现：单 Service 同时实现两个 interface 或分两个 Service）。
 * @property pluginInfo 宿主侧 domain PluginInfo（包名 / 版本 / 显示名 / 大小）。
 */
class PluginBackendAdapter(
    private val pluginInterface: IPluginInterface,
    private val descriptor: IPluginDescriptor,
    private val pluginInfo: PluginInfo
) : InferenceBackendV2 {

    /** 日志 tag，建议格式 aibox.plugin.<pluginName>，便于 LogSystem.setTagLevel 过滤。 */
    private val logTag = "aibox.plugin.${pluginInfo.packageName}"

    override fun getName(): String = pluginInfo.packageName

    override fun getDisplayName(): String = pluginInfo.displayName

    override fun getVersion(): String = pluginInfo.version

    override fun describeCapabilities(): BackendCapabilities =
        ParcelableConverters.run {
            try {
                descriptor.describeCapabilities().toDomain()
            } catch (e: RemoteException) {
                // 描述符读取失败，返回"空能力" BackendCapabilities，让 BackendManager 不路由到此插件
                BackendCapabilities(
                    supportedTasks = emptySet(),
                    supportedInputModalities = emptySet(),
                    supportedOutputModalities = emptySet(),
                    maxContextLength = 0,
                    maxImageResolution = null,
                    supportedModelFormats = emptyList(),
                    supportedQuantizations = emptyList(),
                    features = emptySet()
                )
            }
        }

    override fun loadModel(modelPath: String, config: InferenceConfigV2): Outcome<LoadedModel> {
        val request = LoadModelRequest().apply {
            this.modelPath = modelPath
            this.configJson = ParcelableConverters.run { config.toJsonString() }
        }
        return try {
            val result = pluginInterface.loadModel(request)
            if (result?.success == true && !result.modelHandle.isNullOrBlank()) {
                Outcome.Success(
                    LoadedModel(
                        id = result.modelHandle,
                        modelPath = modelPath,
                        backendName = pluginInfo.packageName,
                        config = config,
                        capabilities = describeCapabilities(),
                        loadedAt = System.currentTimeMillis()
                    )
                )
            } else {
                Outcome.Failure(
                    reason = result?.errorMessage?.takeIf { it.isNotBlank() }
                        ?: "Plugin loadModel returned failure with no message"
                )
            }
        } catch (e: RemoteException) {
            Outcome.Failure("RemoteException in loadModel: ${e.message}", e)
        } catch (e: Throwable) {
            Outcome.Failure("Unexpected error in loadModel: ${e.message}", e)
        }
    }

    override fun infer(
        request: InferenceRequest,
        loadedModel: LoadedModel
    ): Outcome<InferenceResult> {
        val parcel = ParcelableConverters.run { request.toInferParcel() }
        return try {
            val result = pluginInterface.infer(parcel, loadedModel.id)
            if (result?.success == true) {
                Outcome.Success(ParcelableConverters.inferenceResultFromJson(result.resultJson))
            } else {
                Outcome.Failure(
                    reason = result?.errorMessage?.takeIf { it.isNotBlank() }
                        ?: "Plugin infer returned failure with no message"
                )
            }
        } catch (e: RemoteException) {
            Outcome.Failure("RemoteException in infer: ${e.message}", e)
        } catch (e: Throwable) {
            Outcome.Failure("Unexpected error in infer: ${e.message}", e)
        }
    }

    /**
     * 流式推理：把 AIDL `IStreamCallback` 桥到 Kotlin [Flow]。
     *
     * - 订阅 [Flow] 时（[callbackFlow] block 执行）才真正调用 pluginInterface.streamInfer。
     * - 每个 onChunk(partialJson, done=false) emit 一个 [InferenceResult.StreamChunk]（done=false）。
     * - onChunk("", done=true) emit 一个 StreamChunk（done=true）后 close flow。
     * - onError(reason) emit 一个 partial=Text(reason)、done=true 的 StreamChunk（统一"用 done chunk 表达错误"语义）。
     * - Flow 被取消（cancel）时 [awaitClose] 触发；AIDL 当前不支持中途取消，
     *   这里不主动调任何"取消"接口，让插件自己跑完（或卸载模型时统一清理）。
     */
    override fun streamInfer(
        request: InferenceRequest,
        loadedModel: LoadedModel
    ): Flow<InferenceResult.StreamChunk> = callbackFlow {
        val parcel = ParcelableConverters.run { request.toInferParcel() }
        val callback = object : IStreamCallback.Stub() {
            override fun onChunk(partialJson: String?, done: Boolean) {
                val partial = ParcelableConverters.modalityOutputFromJson(partialJson)
                trySend(InferenceResult.StreamChunk(partial, done))
                if (done) channel.close()
            }

            override fun onError(reason: String?) {
                // 错误用 done chunk 表达（与 InferenceBackendV2 的零异常约定对齐）
                trySend(
                    InferenceResult.StreamChunk(
                        partial = ModalityOutput.Text("[stream error: ${reason ?: "unknown"}]"),
                        done = true
                    )
                )
                channel.close()
            }
        }
        try {
            pluginInterface.streamInfer(parcel, loadedModel.id, callback)
        } catch (e: RemoteException) {
            trySend(
                InferenceResult.StreamChunk(
                    partial = ModalityOutput.Text("[RemoteException: ${e.message}]"),
                    done = true
                )
            )
            channel.close()
        } catch (e: Throwable) {
            trySend(
                InferenceResult.StreamChunk(
                    partial = ModalityOutput.Text("[error: ${e.message}]"),
                    done = true
                )
            )
            channel.close()
        }
        awaitClose {
            // Flow 被取消时本适配器不主动通知插件取消（AIDL 当前无 cancel API）。
            // 插件侧的 streamInfer 应在 model unload 时统一中断其内部推理线程。
        }
    }

    override fun unload(model: LoadedModel) {
        try {
            pluginInterface.unload(model.id)
        } catch (e: RemoteException) {
            // unload 失败仅记日志，不抛异常（V2 接口 unload 返回 Unit，零异常）
            com.aibox.util.log.LogSystem.warn(
                logTag,
                "RemoteException in unload: ${e.message}",
                e
            )
        } catch (e: Throwable) {
            com.aibox.util.log.LogSystem.warn(
                logTag,
                "Unexpected error in unload: ${e.message}",
                e
            )
        }
    }

    override fun applyOptimizations(flags: OptimizationFlags) {
        val map = ParcelableConverters.run { flags.toFlagMap() }
        try {
            pluginInterface.applyOptimizations(map)
        } catch (e: RemoteException) {
            com.aibox.util.log.LogSystem.warn(
                logTag,
                "RemoteException in applyOptimizations: ${e.message}",
                e
            )
        } catch (e: Throwable) {
            com.aibox.util.log.LogSystem.warn(
                logTag,
                "Unexpected error in applyOptimizations: ${e.message}",
                e
            )
        }
    }
}
