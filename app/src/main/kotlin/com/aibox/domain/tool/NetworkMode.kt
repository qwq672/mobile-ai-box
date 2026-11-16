package com.aibox.domain.tool

/**
 * 输入区「联网」三态。
 *
 * 只做 UI 聚合，底层仍是 [ToolSettingsRepository] 里两个独立开关：
 * - OFF    → 两个都关
 * - READ   → 只开 url_reader（读你给的链接）
 * - SEARCH → url_reader + web_search 都开
 *
 * 这样工具设置页仍然可以精确控制单项，不会出现两套状态互相打架。
 */
enum class NetworkMode(val label: String, val description: String) {
    OFF("联网", "不访问网络"),
    READ("阅读", "只读取你提供的链接"),
    SEARCH("搜索", "允许主动搜索并读取网页");

    fun next(): NetworkMode = when (this) {
        OFF -> READ
        READ -> SEARCH
        SEARCH -> OFF
    }

    /** 应用后应启用的工具集合（保留其它工具的启用状态）。 */
    fun applyTo(enabledIds: Set<String>): Set<String> {
        val without = enabledIds - WEB_SEARCH - URL_READER
        return when (this) {
            OFF -> without
            READ -> without + URL_READER
            SEARCH -> without + URL_READER + WEB_SEARCH
        }
    }

    companion object {
        const val WEB_SEARCH = "web_search"
        const val URL_READER = "url_reader"

        /** 从当前启用集合推导三态；两者都开时优先显示“搜索”。 */
        fun fromEnabled(enabledIds: Set<String>): NetworkMode = when {
            WEB_SEARCH in enabledIds -> SEARCH
            URL_READER in enabledIds -> READ
            else -> OFF
        }
    }
}
