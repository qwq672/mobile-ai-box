package com.aibox.data.backup

import com.aibox.data.model.ChatMessage
import com.aibox.data.repo.CloudModelProfile
import kotlinx.serialization.Serializable

/**
 * AIBox 备份契约（v1）。
 *
 * 设计约束：
 * - 所有项都是 nullable，导出时只写入用户勾选的项，未勾选为 null（不导出）。
 * - 导入一律「追加」：对话新建会话、云端模型 upsert、设置覆盖显式导入项，绝不删除现有数据。
 * - API Key 等敏感信息由导出方显式决定是否包含，UI 给出风险提示。
 */
@Serializable
data class AiboxBackup(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAt: Long = 0L,
    val appVersion: String = "",
    val conversations: List<ConversationBackup>? = null,
    val cloudModels: List<CloudModelProfile>? = null,
    val settings: Map<String, SettingValue>? = null,
    val images: List<ImageBackup>? = null,
    val note: String? = null,
) {
    companion object {
        const val FORMAT = "aibox-backup"
        const val VERSION = 1
    }
}

/** DataStore 单项：type 标记原始类型（b/i/l/f/s/ss），value 为字符串表示。 */
@Serializable
data class SettingValue(val type: String, val value: String)

/** 会话快照 + 其全部消息（含 parts/versions/版本分支字段，通过 ChatMessage 无损承载）。 */
@Serializable
data class ConversationBackup(
    val session: SessionBackup,
    val messages: List<ChatMessage> = emptyList(),
)

@Serializable
data class SessionBackup(
    val title: String,
    val modelPath: String? = null,
    val mode: String = "CHAT",
    val createdAt: Long = 0L,
    val lastUsedAt: Long = 0L,
)

/** 图片附件：以 base64 内嵌，导入时按 name 还原到附件目录。 */
@Serializable
data class ImageBackup(
    val name: String,
    val mime: String,
    val base64: String,
)

/** 导出勾选项。 */
data class ExportSelection(
    val conversations: Boolean = true,
    val cloudModels: Boolean = true,
    val settings: Boolean = true,
    val images: Boolean = false,
    val note: String = "",
)

/** 导入结果汇总（供 UI 展示）。 */
data class ImportReport(
    val conversations: Int = 0,
    val messages: Int = 0,
    val cloudModels: Int = 0,
    val settings: Int = 0,
    val images: Int = 0,
    val warnings: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = conversations + messages + cloudModels + settings + images == 0
}
