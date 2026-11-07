package com.pocketllm.infra.jni

import java.nio.ByteBuffer

/**
 * JNI 桥。所有 native 调用都集中在这里，便于管理符号。
 *
 * 对应 C++ 侧 native_bridge.cpp。每个方法都映射到一个 extern "C" 函数。
 *
 * 命名约定：
 * - llama*     : llama.cpp 推理相关
 * - memoryPool* : 内存池相关
 * - vulkanAvailable / npuAvailable / openclAvailable : 后端探测
 */
class NativeBridge {

    init {
        System.loadLibrary("pocketllm")
    }

    // -------- 后端可用性 --------
    external fun vulkanAvailable(): Boolean
    external fun npuAvailable(): Boolean
    external fun openclAvailable(): Boolean
    external fun nativeVersion(): String

    // -------- 模型加载 --------
    external fun llamaLoad(
        modelPath: String,
        backend: String,
        nGpuLayers: Int,
        threads: Int,
        physicalBatch: Int,
        batch: Int,
        contextLength: Int,
        kvQuant: String
    ): Boolean

    external fun llamaUnload()
    external fun llamaSetSampler(
        temperature: Float,
        topK: Int,
        topP: Float,
        repeatPenalty: Float
    )

    /**
     * 流式补全。token 通过 [tokenCallback] 回调到 JVM（已是字符串），
     * 完成时调用 [stopCallback] 传入 stop reason。
     *
     * 注意：使用专用 Java interface（不带 generic）而不是 Kotlin (String)->Unit，
     * 因为 C++ 端 FindClass/GetMethodID 在 Kotlin Function1.invoke 上经常失败。
     */
    external fun llamaCompletion(
        prompt: String,
        tokenCallback: StringCallback,
        stopCallback: StringCallback
    )

    /**
     * 流式补全（chat template 版）。
     * 把对话历史以消息数组形式传给 native，native 端用模型内置 chat template 格式化。
     * 这比 llamaCompletion 的 String prompt 更可靠 —— 避免简化模板与模型不匹配。
     *
     * @param messages 消息数组，每条: role + content。role: "system" / "user" / "assistant"
     * @param thinkingMode true 时在 system message 末尾加 "/think"（Qwen3 支持）
     * @param tokenCallback 每生成一个 token 调用一次
     * @param stopCallback 完成时调用，传入 stop reason
     */
    external fun llamaCompletionChat(
        messages: Array<ChatMsg>,
        thinkingMode: Boolean,
        tokenCallback: StringCallback,
        stopCallback: StringCallback
    )

    /** 一条 chat 消息（给 llamaCompletionChat 用） */
    data class ChatMsg(val role: String, val content: String)

    external fun llamaInterrupt()

    // -------- 状态查询 --------
    external fun llamaTokensPerSecond(): Float
    external fun llamaContextUsed(): Int
    external fun llamaContextMax(): Int

    // -------- 内存池 --------
    external fun memoryPoolWarmup(kvBytes: Long, computeBytes: Long)
    external fun memoryPoolRelease()
    external fun memoryPoolShrinkIdle(idleMs: Long, keepTokens: Int)
    external fun memoryPoolPeakMb(): Long

    /**
     * 借一片 DirectByteBuffer（池化复用）。
     * 调用方使用完必须 [returnDirectBuffer] 归还。
     */
    external fun borrowDirectBuffer(byteSize: Int): ByteBuffer
    external fun returnDirectBuffer(buf: ByteBuffer)

    // -------- 热感知 --------
    external fun thermalPercent(): Int

    companion object {
        @Volatile private var logHandler: ((Int, String, String) -> Unit)? = null

        /** 由 native 侧通过 JNI 调用，把日志传到 Kotlin AppLogger。 */
        @JvmStatic
        fun onNativeLog(level: Int, tag: String, msg: String) {
            logHandler?.invoke(level, tag, msg)
        }

        /** 由 PocketLLMApp 注册实际写入文件的 handler。 */
        fun setLogHandler(h: ((Int, String, String) -> Unit)?) {
            logHandler = h
        }
    }
}

/**
 * 单字符串参数回调。Java interface，无 generic，C++ 端 GetMethodID 100% 可靠。
 * 用法：`object : StringCallback { override fun onValue(v: String) { ... } }`
 */
interface StringCallback {
    fun onValue(value: String)
}

