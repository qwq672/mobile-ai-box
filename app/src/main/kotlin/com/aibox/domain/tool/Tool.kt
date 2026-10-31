package com.aibox.domain.tool

import com.aibox.util.outcome.Outcome

/**
 * 工具统一接口（B1 工具调用抽象层）。
 *
 * - 所有工具（基础 / 高级）实现此接口
 * - 工具通过 [ToolRegistry] 注册和管理
 * - LLM 通过 function calling 调用工具（[ToolRegistry.getActiveToolsForLLM] 暴露 schema）
 * - 高级工具（[ToolCategory.ADVANCED]）执行前需用户确认（[requiresConfirmation]）
 *
 * 设计原则：
 * - [execute] 返回 [Outcome]，零异常约定（实现方内部 try-catch 兜底，不抛异常出界）
 * - 参数 schema 通过 [getParameterSchema] 暴露，转 OpenAI JSON schema 后给 LLM
 * - 进度回调通过 [ToolContext.progressCallback] 暴露，工具实现可选使用
 * - 确认回调通过 [ToolContext.confirmCallback] 暴露，由 [ToolRegistry.execute] 统一调起
 *
 * 工具分类：
 * - [ToolCategory.BASIC]：基础工具，自动执行（联网搜索 / JS 运行 / URL 阅读 / 基础设备信息 / DateTime / Calculator）
 * - [ToolCategory.ADVANCED]：高级工具，需用户确认（文件读写 / 邮件 / 高级设备信息 / AI 操作手机）
 *
 * 工具 ID 约定：snake_case，如 `web_search` / `file_read` / `phone_control`。
 */
interface Tool {
    /** 工具唯一 ID，如 "web_search" */
    val id: String

    /** 用户可见的显示名，如 "联网搜索" */
    val displayName: String

    /** 给 LLM 看的功能描述（影响 LLM 何时选择调用此工具） */
    val description: String

    /** 工具分类：基础(自动执行) / 高级(需确认) */
    val category: ToolCategory

    /**
     * 是否需要用户确认执行。
     *
     * 默认按 [category] 推导：[ToolCategory.ADVANCED] 返回 true，其余返回 false。
     * 个别高级工具若风险极低（如只读设备信息），可重写此属性为 false 跳过确认。
     */
    val requiresConfirmation: Boolean
        get() = category == ToolCategory.ADVANCED

    /** 图标 emoji 或资源 key，如 "🔍" / "@drawable/ic_search" */
    val icon: String

    /** 工具版本，默认 "1.0.0" */
    val version: String
        get() = "1.0.0"

    /** 工具作者，默认 "AIBox" */
    val author: String
        get() = "AIBox"

    /** 参数 schema（给 LLM function calling 用） */
    fun getParameterSchema(): ToolParameterSchema

    /**
     * 执行工具。
     *
     * - 实现方应捕获所有异常并返回 [Outcome.Failure]，不抛出界
     * - 长任务可通过 [ToolContext.progressCallback] 报告进度（0.0..1.0）
     * - 输出文本（[ToolResult.output]）是给 LLM 看的；结构化数据（[ToolResult.structuredData]）给 UI 用
     */
    suspend fun execute(params: Map<String, Any>, context: ToolContext): Outcome<ToolResult>
}

/**
 * 工具分类。
 *
 * - [BASIC]：基础工具，自动执行（无需用户确认）
 * - [ADVANCED]：高级工具，需用户确认（默认开 [Tool.requiresConfirmation]）
 */
enum class ToolCategory {
    BASIC,      // 自动执行
    ADVANCED    // 需用户确认
}

/**
 * 工具参数 schema。
 *
 * 由两组 [ParamSpec] 组成：[required] + [optional]。
 * 通过 [toJsonSchema] 转成 OpenAI 兼容 JSON schema 字符串。
 *
 * 注：参数顺序按 [required] 在前、[optional] 在后；UI 渲染时也可按此顺序展示。
 */
data class ToolParameterSchema(
    val required: List<ParamSpec> = emptyList(),
    val optional: List<ParamSpec> = emptyList()
) {
    /** 转成 LLM function calling 用的 JSON schema 字符串（OpenAI 兼容）。 */
    fun toJsonSchema(): String {
        return buildJsonSchema(this)
    }
}

/**
 * 单个参数规格。
 *
 * @param name 参数名（snake_case，如 "query"）
 * @param type 参数类型
 * @param description 给 LLM 看的参数描述
 * @param enum 枚举可选值；非空时 LLM 只能从这些值里选
 * @param default 默认值；可选参数可填，LLM 未传时使用
 */
data class ParamSpec(
    val name: String,
    val type: ParamType,
    val description: String,
    val enum: List<String>? = null,
    val default: Any? = null
)

/**
 * 参数类型枚举，与 JSON schema 类型对齐。
 */
enum class ParamType {
    STRING,
    NUMBER,
    INTEGER,
    BOOLEAN,
    ARRAY,
    OBJECT
}

/**
 * 工具执行结果。
 *
 * @param success 是否成功（[Outcome.Success] 时仍可能 success=false，表示工具正常返回但业务失败）
 * @param output 文本输出，给 LLM 看的（自然语言 / markdown）
 * @param structuredData 结构化数据，给 UI 用（任意类型，由工具定义）
 * @param error 错误描述（success=false 时填）
 * @param executionTimeMs 执行耗时（毫秒）
 */
data class ToolResult(
    val success: Boolean,
    val output: String,
    val structuredData: Any? = null,
    val error: String? = null,
    val executionTimeMs: Long = 0
)

/**
 * 工具执行上下文。
 *
 * 由 AgentRunner（或 UI 层）在调用 [ToolRegistry.execute] 前构造，
 * 携带会话信息、设备信息、确认回调、进度回调。
 *
 * @param sessionId 推理会话 ID（与 LogSystem 会话日志对齐）
 * @param userId 用户 ID（多用户场景用，默认 "default"）
 * @param deviceInfo 设备信息快照（执行时刻的）
 * @param confirmCallback 高级工具确认回调；返回 true 表示用户同意执行
 *   - 第一个参数：确认提示文案（含工具显示名 + 参数摘要）
 *   - 第二个参数：原始参数 map（UI 可详细展示）
 *   - 默认实现返回 false（拒绝），保证未接入 UI 时不误执行高级工具
 * @param progressCallback 进度回调；null 表示 UI 不需要进度更新
 *   - 第一个参数：0.0..1.0 进度
 *   - 第二个参数：当前阶段描述
 */
data class ToolContext(
    val sessionId: String,
    val userId: String = "default",
    val deviceInfo: DeviceInfo,
    val confirmCallback: suspend (String, Map<String, Any>) -> Boolean = { _, _ -> false },
    val progressCallback: ((Float, String) -> Unit)? = null
)

/**
 * 设备信息（工具执行时可用）。
 *
 * 由调用方在构造 [ToolContext] 时填充。基础设备信息工具（B 系列）会原样返回此快照；
 * 高级设备信息工具（C 系列）会读取更详细字段（如传感器列表、应用列表）。
 *
 * 字段说明：
 * @param deviceModel 设备型号（如 "Pixel 9 Pro"）
 * @param androidVersion Android 版本字符串（如 "14"）
 * @param sdkVersion SDK 整数版本（如 34）
 * @param abis CPU ABI 列表（如 ["arm64-v8a"]）
 * @param totalRamMb 总内存（MB）
 * @param availableRamMb 可用内存（MB）
 * @param batteryLevel 电量百分比（0..100，-1 表示读不到）
 * @param isCharging 是否充电中
 */
data class DeviceInfo(
    val deviceModel: String,
    val androidVersion: String,
    val sdkVersion: Int,
    val abis: List<String>,
    val totalRamMb: Long,
    val availableRamMb: Long,
    val batteryLevel: Int,
    val isCharging: Boolean
)
