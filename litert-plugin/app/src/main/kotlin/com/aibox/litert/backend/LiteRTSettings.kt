package com.aibox.litert.backend

/**
 * LiteRT 插件运行期配置。
 *
 * 由 [LiteRTBackend.loadModel] 解析宿主传入的 `configJson`(序列化后的 InferenceConfigV2)
 * 得到;字段名以 `litert.*` 前缀,与 [com.aibox.litert.LiteRTDescriptor.getSettingsSchema]
 * 的 key 对齐。
 *
 * 用户在宿主设置面板改的值会通过 configJson 一并下发(宿主 PluginBackendAdapter.loadModel
 * 时把 InferenceConfigV2 序列化为 JSON 跨进程传递)。每次 loadModel 都重新解析,不缓存。
 *
 * @property preferredDelegate 优先 delegate:"auto" / "npu" / "gpu" / "cpu"。
 * @property gpuDelegateFP16 GPU Delegate 是否启用 FP16(降低显存 + 加速;略降精度)。
 * @property performanceMode NPU 性能模式:"performance" / "balanced" / "luxury"。
 * @property enableVisionEncoder 启用 Siglip 视觉编码器(纯文本模型可关掉省内存)。
 * @property visionResolution Siglip 输入图像分辨率(512 / 1024 / 2048)。
 */
data class LiteRTSettings(
    val preferredDelegate: String = "auto",
    val gpuDelegateFP16: Boolean = true,
    val performanceMode: String = "balanced",
    val enableVisionEncoder: Boolean = true,
    val visionResolution: Int = 1024
) {
    companion object {
        /**
         * 从宿主 configJson(JSON 字符串)解析。
         *
         * configJson 字段约定:用 JSON Object + 平铺 key(不走 nested object),
         * 简化 org.json.JSONObject 解析(无需 AndroidX JSON 库依赖)。
         */
        fun fromConfigJson(configJson: String?): LiteRTSettings {
            if (configJson.isNullOrBlank()) return LiteRTSettings()
            return try {
                val json = org.json.JSONObject(configJson)
                LiteRTSettings(
                    preferredDelegate = json.optString("litert.preferredDelegate", "auto"),
                    gpuDelegateFP16 = json.optBoolean("litert.gpuDelegateFP16", true),
                    performanceMode = json.optString("litert.performanceMode", "balanced"),
                    enableVisionEncoder = json.optBoolean("litert.enableVisionEncoder", true),
                    visionResolution = json.optInt("litert.visionResolution", 1024)
                )
            } catch (e: Throwable) {
                // 解析失败用默认值;不抛异常,让 loadModel 继续(用安全配置)。
                LiteRTSettings()
            }
        }
    }
}
