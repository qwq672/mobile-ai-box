package com.aibox.domain.inference

/**
 * 模型格式检测 + 后端路由。
 *
 * 根据模型文件扩展名判断属于哪个推理框架:
 * - `.gguf`      → LLAMACPP (走 V1 InferenceEngine + llama.cpp)
 * - `.tflite`    → LITERT   (走 V2 BackendManagerV2 + LiteRT 插件)
 * - `.litertlm`  → LITERT   (走 V2 BackendManagerV2 + LiteRT 插件)
 * - `.onnx`      → ONNX     (走 V2 BackendManagerV2 + ONNX 插件,若装了)
 * - `.mnn`       → MNN      (走 V2 BackendManagerV2 + MNN 插件,若装了)
 * - `.bin`       → LLAMACPP (老格式兼容,当 gguf 处理)
 * - 其他         → LLAMACPP (默认当 GGUF)
 *
 * 路由规则:
 * 1. 模型是 LLAMACPP → 走 V1 路径(InferenceEngine.loadModel)
 * 2. 模型是 LITERT/ONNX/MNN → 走 V2 路径
 *    - 从 BackendManagerV2 找匹配框架的已注册后端
 *    - 找不到 → 报错("未安装 LiteRT/ONNX/MNN 插件")
 *    - 找到 → 调 backend.loadModel + backend.streamInfer
 */
object ModelFormatRouter {

    /** 模型格式枚举。 */
    enum class ModelFormat(val extension: String, val frameworkId: FrameworkId) {
        GGUF("gguf", FrameworkId.LLAMACPP),
        BIN("bin", FrameworkId.LLAMACPP),
        TFLITE("tflite", FrameworkId.LITERT),
        LITERTLM("litertlm", FrameworkId.LITERT),
        ONNX("onnx", FrameworkId.ONNX),
        MNN("mnn", FrameworkId.MNN),
        UNKNOWN("", FrameworkId.LLAMACPP);

        companion object {
            fun fromPath(filePath: String): ModelFormat {
                val lower = filePath.lowercase().trim()
                return when {
                    // 兼容历史导入路径被错误追加 .gguf 的情况（如 xxx.litertlm.gguf）
                    lower.contains(".litertlm.") -> LITERTLM
                    lower.contains(".tflite.")   -> TFLITE
                    lower.contains(".onnx.")     -> ONNX
                    lower.contains(".mnn.")      -> MNN
                    lower.endsWith(".gguf")      -> GGUF
                    lower.endsWith(".bin")       -> BIN
                    lower.endsWith(".tflite")    -> TFLITE
                    lower.endsWith(".litertlm")  -> LITERTLM
                    lower.endsWith(".onnx")      -> ONNX
                    lower.endsWith(".mnn")       -> MNN
                    else                          -> UNKNOWN
                }
            }
        }
    }

    /** 路由结果。 */
    sealed class RouteResult {
        /** 走 V1 路径(llama.cpp 内置)。 */
        data class V1Path(val format: ModelFormat) : RouteResult()
        /** 走 V2 路径,需要指定 backendName(从 BackendManagerV2.listBackends 找)。 */
        data class V2Path(val format: ModelFormat, val backendName: String) : RouteResult()
        /** 无法路由(需要的插件未安装)。 */
        data class NoBackend(val format: ModelFormat, val frameworkId: FrameworkId, val reason: String) : RouteResult()
    }

    /**
     * 根据模型路径 + 当前可用后端列表,决定路由到哪条路径。
     *
     * @param filePath 模型文件路径
     * @param availableBackends 当前 BackendManagerV2 中已注册的后端名列表
     * @return [RouteResult]
     */
    fun route(filePath: String, availableBackends: List<String>): RouteResult {
        val format = ModelFormat.fromPath(filePath)
        return when (format.frameworkId) {
            FrameworkId.LLAMACPP -> RouteResult.V1Path(format)
            FrameworkId.LITERT -> {
                // LiteRT 模型需要找到 LiteRT 插件
                // 约定:LiteRT 后端名包含 "litert"(忽略大小写)
                val name = availableBackends.firstOrNull { it.contains("litert", ignoreCase = true) }
                if (name != null) RouteResult.V2Path(format, name)
                else RouteResult.NoBackend(format, FrameworkId.LITERT,
                    "LiteRT 插件未就绪：可能尚未安装，或已安装但绑定失败（查看日志中的 auto-enable 记录）")
            }
            FrameworkId.ONNX -> {
                val name = availableBackends.firstOrNull { it.contains("onnx", ignoreCase = true) }
                if (name != null) RouteResult.V2Path(format, name)
                else RouteResult.NoBackend(format, FrameworkId.ONNX,
                    "未安装 ONNX Runtime 插件")
            }
            FrameworkId.MNN -> {
                val name = availableBackends.firstOrNull { it.contains("mnn", ignoreCase = true) }
                if (name != null) RouteResult.V2Path(format, name)
                else RouteResult.NoBackend(format, FrameworkId.MNN,
                    "未安装 MNN 插件")
            }
            else -> RouteResult.V1Path(format)  // QNN/MLLM 暂走 V1 兜底
        }
    }
}
