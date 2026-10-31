package com.aibox.domain.search

/**
 * 可搜索实体的统一接口。
 *
 * 设计原则：
 * - 所有可被全局搜索的数据源（聊天 / 模型 / 设置 / 工具 / 模块 / 基准测试）都实现本接口，
 *   由 [SearchEngine] 通过 [SearchProvider] 统一调度。
 * - 字段语义稳定：searchId 唯一、searchTitle 主标题、searchSubtitle 副标题、
 *   searchKeywords 关键词、searchDomain 归属域、searchTimestamp 时间戳、searchData 附加数据。
 * - searchData 是 Map<String, Any>，由各 Provider 自定义内容；调用方按 domain 分发跳转逻辑时再解析。
 *   例如 CHAT_MESSAGE 域约定 searchData 含 "sessionId" / "messageId"，
 *   SETTING 域含 "screen" / "sectionKey"。
 *
 * 实现建议：
 * - 用 data class 实现，并把 searchTimestamp 设为可空（设置 / 工具类无时间戳概念）。
 * - searchKeywords 应包含标题分词 + 用户可见标签，但不包含完整正文（正文匹配走 searchSubtitle
 *   或专门字段，避免内存暴涨）。
 */
interface SearchableEntity {
    /** 全局唯一 ID，建议格式 "<domain>:<id>"，如 "chat_msg:42" / "model:7" / "setting:opt.preset" */
    val searchId: String

    /** 主标题（在搜索结果列表第一行展示，被高亮） */
    val searchTitle: String

    /** 副标题（在搜索结果列表第二行展示，灰色） */
    val searchSubtitle: String

    /** 关键词列表（用于多关键词模糊匹配 + 权重计算） */
    val searchKeywords: List<String>

    /** 归属域（用于域过滤标签 + 跳转分发） */
    val searchDomain: SearchDomain

    /** 时间戳（毫秒），用于排序；null 表示无时间戳（如设置项） */
    val searchTimestamp: Long?

    /** 附加数据，点击结果跳转时用；各域自定义 */
    val searchData: Map<String, Any>
}

/**
 * 搜索域枚举。
 *
 * 用于 [SearchEngine.search] 的 domains 过滤参数 + UI 上 FilterChip 多选标签。
 * 新增域时在此追加，UI 层与各 Provider 自动支持。
 */
enum class SearchDomain {
    /** 聊天会话（按标题搜索） */
    CHAT_CONVERSATION,

    /** 聊天消息（按正文搜索） */
    CHAT_MESSAGE,

    /** 已下载 / 导入的模型 */
    MODEL,

    /** 设置项 */
    SETTING,

    /** 工具（依赖 ToolRegistry，B1 实现后填充） */
    TOOL,

    /** 已安装的模块（聊天模块 / 后端插件） */
    MODULE,

    /** 历史基准测试结果 */
    BENCHMARK
}
