package com.aibox.domain.tool

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON schema 构建工具（B1 工具调用抽象层）。
 *
 * 把 [ToolParameterSchema] 转成 OpenAI function calling 兼容的 JSON schema 字符串。
 *
 * 输出格式示例（WebSearchTool）：
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "query": { "type": "string", "description": "搜索关键词" },
 *     "limit": { "type": "integer", "description": "结果数量", "default": 5 }
 *   },
 *   "required": ["query"]
 * }
 * ```
 *
 * 实现选型：
 * - 用 Android 内置的 `org.json.JSONObject` / `JSONArray`，不引入新依赖
 * - `JSONObject.toString()` 产生 compact JSON（无多余空白），LLM 解析无歧义
 * - 默认值（[ParamSpec.default]）按运行时类型派发，保证数字 / 布尔不被序列化成字符串
 *
 * OpenAI 兼容性：
 * - 顶层 `type` 固定为 `"object"`（function calling 参数规范）
 * - `required` 数组只在 [ToolParameterSchema.required] 非空时输出
 * - `enum` 只在 [ParamSpec.enum] 非空时输出
 * - `default` 只在 [ParamSpec.default] 非空时输出
 */
fun buildJsonSchema(schema: ToolParameterSchema): String {
    val root = JSONObject()
    root.put("type", "object")

    // properties: 把 required + optional 合并到一个 object
    val properties = JSONObject()
    schema.required.forEach { properties.put(it.name, paramSpecToJson(it)) }
    schema.optional.forEach { properties.put(it.name, paramSpecToJson(it)) }
    root.put("properties", properties)

    // required: 仅当存在必填参数时输出
    if (schema.required.isNotEmpty()) {
        val requiredArray = JSONArray()
        schema.required.forEach { requiredArray.put(it.name) }
        root.put("required", requiredArray)
    }

    return root.toString()
}

/**
 * 单个 [ParamSpec] 转 JSON schema property object。
 *
 * 输出形如：
 * `{ "type": "string", "description": "...", "enum": ["a","b"], "default": "a" }`
 *
 * enum / default 字段按需输出，避免空字段污染 schema。
 */
private fun paramSpecToJson(spec: ParamSpec): JSONObject {
    val obj = JSONObject()
    obj.put("type", spec.type.jsonTypeName())
    obj.put("description", spec.description)

    spec.enum?.let { enumValues ->
        val arr = JSONArray()
        enumValues.forEach { arr.put(it) }
        obj.put("enum", arr)
    }

    spec.default?.let { defaultValue ->
        // JSONObject.put(key, Any) 自动按运行时类型派发：
        //   String -> string, Boolean -> bool, Int/Long/Double -> number
        // 注：Float/Double 的 NaN / Infinite 会被 Android JSONObject 拒绝（抛 JSONException），
        // 此处兜底转字符串，保证零异常。
        try {
            obj.put("default", defaultValue)
        } catch (_: org.json.JSONException) {
            obj.put("default", defaultValue.toString())
        }
    }

    return obj
}

/**
 * 把 [ParamType] 映射到 JSON schema 的类型字符串。
 *
 * 与 OpenAI / JSON Schema 2020-12 规范一致。
 */
private fun ParamType.jsonTypeName(): String = when (this) {
    ParamType.STRING  -> "string"
    ParamType.NUMBER -> "number"
    ParamType.INTEGER -> "integer"
    ParamType.BOOLEAN -> "boolean"
    ParamType.ARRAY  -> "array"
    ParamType.OBJECT -> "object"
}
