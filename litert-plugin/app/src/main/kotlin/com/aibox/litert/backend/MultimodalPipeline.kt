package com.aibox.litert.backend

import com.aibox.litert.LiteRTLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * 多模态推理管线（骨架版）。
 *
 * 实际生产实现需要:
 * 1. Siglip 视觉编码器（提取图像 embedding）
 * 2. Gemma decoder 接受 text + image embedding 生成文本
 *
 * 当前简化方案:
 * - 直接把"图像 URL/路径"作为文本描述传给 LiteRT-LM
 * - LLM 看不到真实图像内容,只能按文本理解
 * - 等 LiteRT-LM 多模态 API 稳定后再升级
 */
class MultimodalPipeline(
    private val backend: LiteRTBackend
) {

    private val logTag = "aibox.litert.multimodal"

    /**
     * 多模态推理（简化版）。
     *
     * @param requestJson 宿主传入的 JSON 推理请求
     * @return ModalityOutput.Text 的 JSON 序列化字符串
     */
    fun infer(requestJson: String): String {
        LiteRTLogger.info(logTag, "multimodal infer start")
        return try {
            val request = JSONObject(requestJson)
            val inputs = request.optJSONArray("inputs") ?: JSONArray()
            val (textParts, imageParts) = splitInputs(inputs)

            LiteRTLogger.debug(logTag, "parsed: ${textParts.size} text + ${imageParts.size} image")

            // 简化:把图像描述作为文本传给 backend
            // (等 LiteRT-LM 多模态 API 稳定后改用真实图像 embedding)
            val combined = buildString {
                if (textParts.isNotEmpty()) {
                    append(textParts.joinToString("\n"))
                }
                if (imageParts.isNotEmpty()) {
                    if (textParts.isNotEmpty()) append("\n\n")
                    append("[Image inputs: ${imageParts.joinToString(", ")}]")
                }
            }

            val combinedRequest = JSONObject(request.toString())
            combinedRequest.put("inputs", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("content", combined)
                })
            })

            backend.infer(combinedRequest.toString())
        } catch (e: Throwable) {
            LiteRTLogger.error(logTag, "multimodal infer failed: ${e.message}", e)
            JSONObject().apply {
                put("success", false)
                put("error", e.message ?: "unknown")
            }.toString()
        }
    }

    /** 解析输入 JSON 数组,拆分 text + image。 */
    private fun splitInputs(inputs: JSONArray): Pair<List<String>, List<String>> {
        val textParts = mutableListOf<String>()
        val imageParts = mutableListOf<String>()
        for (i in 0 until inputs.length()) {
            val input = inputs.optJSONObject(i) ?: continue
            val type = input.optString("type", "text")
            when (type) {
                "text" -> textParts.add(input.optString("content", ""))
                "image" -> imageParts.add(input.optString("uri", input.optString("content", "")))
            }
        }
        return textParts to imageParts
    }
}
