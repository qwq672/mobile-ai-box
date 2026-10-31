package com.aibox.plugins.sample_echo

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.aibox.plugin.aidl.IPluginInterface
import com.aibox.plugin.aidl.InferCallbacksProxy
import com.aibox.plugin.aidl.InferenceRequestParcel
import com.aibox.plugin.aidl.InferenceResultParcel
import com.aibox.plugin.aidl.PluginDescriptor
import kotlin.system.measureTimeMillis

/**
 * 示例插件 Service：Echo 后端。
 *
 * 用途：验证插件宿主 AIDL 通路可用。所有请求回显 prompt 前缀 "[echo] "。
 *
 * 真实插件示例：
 * - 把 SD-MNN 推理代码包成插件 APK，提供 IMAGE_GENERATION 能力
 * - 把 OpenAI Whisper 的 tflite 推理包成插件，提供 AUDIO_STT 能力
 */
class EchoPluginService : Service() {

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : IPluginInterface.Stub() {
        override fun describe(): PluginDescriptor {
            return PluginDescriptor().apply {
                pluginId = "sample-echo@0.1.0"
                displayName = "Sample Echo Plugin"
                version = "0.1.0"
                capabilities = arrayOf("CHAT")
                npuVendors = arrayOf()
                signatureSha256 = ""
                apiVersion = 1
            }
        }

        override fun start() {
            // 插件进程启动：预加载资源
        }

        override fun stop() {
            // 释放资源
        }

        override fun inferOnce(request: InferenceRequestParcel): InferenceResultParcel {
            val elapsed = measureTimeMillis {
                Thread.sleep(50)  // 模拟推理耗时
            }
            return InferenceResultParcel().apply {
                text = "[echo] ${request.prompt}"
                tokensGenerated = request.prompt.length / 4
                latencyMillis = elapsed
                tokensPerSecond = tokensGenerated.toDouble() / (elapsed / 1000.0)
                finishReason = 0 // STOP
                npuUsed = false
                errorCode = ""
                errorMessage = ""
            }
        }

        override fun inferStreamed(
            request: InferenceRequestParcel,
            callback: InferCallbacksProxy?,
        ): InferenceResultParcel {
            val response = "[echo] ${request.prompt}"
            var elapsed = 0L
            elapsed = measureTimeMillis {
                // 简单流式：按字符回显
                response.forEach { ch ->
                    callback?.onToken(ch.toString())
                    Thread.sleep(10)
                }
            }
            val result = InferenceResultParcel().apply {
                text = response
                tokensGenerated = response.length / 4
                latencyMillis = elapsed
                tokensPerSecond = tokensGenerated.toDouble() / (elapsed / 1000.0)
                finishReason = 0
                npuUsed = false
                errorCode = ""
                errorMessage = ""
            }
            callback?.onComplete(result)
            return result
        }

        override fun cancel() {
            // stub: 无状态可取消
        }

        override fun apiVersion(): Int = 1
    }
}
