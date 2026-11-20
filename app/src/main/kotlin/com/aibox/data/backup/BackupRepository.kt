package com.aibox.data.backup

import android.content.Context
import android.util.Base64
import com.aibox.AIBoxApp
import com.aibox.data.model.ChatMessage
import com.aibox.data.repo.CloudModelProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 应用数据备份 / 恢复仓库。
 *
 * 铁律：绝不因导入而丢数据。
 * - 导出：按用户勾选聚合会话/消息、云端模型（不含 API Key）、设置、图片附件，序列化为 JSON。
 * - 导入（本软件格式）：会话与消息一律「新建追加」，不覆盖、不删除任何现有会话。
 * - 导入（ChatGPT / DeepSeek）：解析为会话 + 消息后同样追加。
 */
class BackupRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val container get() = AIBoxApp.instance.container

    private fun exportsDir(): File = File(context.filesDir, "exports").apply { if (!exists()) mkdirs() }

    /** 导出为 JSON 字符串。 */
    suspend fun export(selection: ExportSelection): String = withContext(Dispatchers.IO) {
        val repo = container
        val conversations = if (selection.conversations) {
            repo.chatRepository.observeSessions().first().map { s ->
                val msgs = repo.chatRepository.getMessages(s.id)
                ConversationBackup(
                    session = SessionBackup(
                        title = s.title, modelPath = s.modelPath, mode = s.mode,
                        createdAt = s.createdAt, lastUsedAt = s.lastUsedAt
                    ),
                    messages = msgs,
                )
            }
        } else null

        // API Key 不进备份文件：只导出配置外壳，导入后用户需重新填 Key
        val cloudModels = if (selection.cloudModels) {
            repo.cloudModelRepository.list().map { it.copy(apiKey = "") }
        } else null

        val settings = if (selection.settings) repo.settingsRepository.exportAll() else null

        val images = if (selection.images) collectImages() else null

        val backup = AiboxBackup(
            exportedAt = System.currentTimeMillis(),
            appVersion = "aibox-backup-v1",
            conversations = conversations,
            cloudModels = cloudModels,
            settings = settings,
            images = images,
            note = selection.note.ifBlank { null },
        )
        json.encodeToString(backup)
    }

    /** 导出并写入 filesDir/exports，返回文件（供 FileProvider 分享）。 */
    suspend fun exportToFile(selection: ExportSelection): File = withContext(Dispatchers.IO) {
        val f = File(exportsDir(), "aibox-backup-${System.currentTimeMillis()}.json")
        f.writeText(export(selection))
        f
    }

    /** 识别并导入：本软件备份 / ChatGPT / DeepSeek 对话。 */
    suspend fun import(raw: String): ImportReport = withContext(Dispatchers.IO) {
        val trimmed = raw.trim()
        val format = runCatching {
            (json.parseToJsonElement(trimmed) as? kotlinx.serialization.json.JsonObject)
                ?.get("format")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        }.getOrNull()

        when (format) {
            AiboxBackup.FORMAT -> importAibox(trimmed)
            else -> {
                // 非本软件格式：先按特征字段轻量探测来源，只解析一次。
                // 此前「ChatGPT / DeepSeek 各 parse 一遍取最大」会在大文件上构造两棵完整
                // JSON 树导致 OOM；detectSource 只扫前几个 node，开销恒定。
                val source = ExternalChatParser.detectSource(trimmed)
                val parsed = source?.let { ExternalChatParser.parse(trimmed, it) }
                    ?: runCatching { ExternalChatParser.parse(trimmed, ExternalChatParser.Source.CHATGPT) }
                        .getOrElse { ExternalChatParser.Result(emptyList()) }
                importExternal(parsed.conversations, parsed.warnings)
            }
        }
    }

    private suspend fun importAibox(raw: String): ImportReport {
        val backup = runCatching { json.decodeFromString<AiboxBackup>(raw) }.getOrElse {
            return ImportReport(warnings = listOf("备份解析失败：${it.message ?: "格式错误"}"))
        }
        val warnings = mutableListOf<String>()
        var sessions = 0
        var messages = 0
        var clouds = 0
        var settings = 0
        var images = 0

        backup.conversations?.forEach { conv ->
            runCatching {
                val suffix = " · 导入 ${java.text.SimpleDateFormat("MMdd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
                val newId = container.chatRepository.createSession(
                    title = conv.session.title + suffix,
                    modelPath = conv.session.modelPath,
                    mode = conv.session.mode.ifBlank { "CHAT" },
                )
                sessions++
                conv.messages.forEach { m ->
                    container.chatRepository.addMessage(newId, m.copy(id = 0, sessionId = newId))
                    messages++
                }
            }.onFailure { warnings.add("会话「${conv.session.title}」导入失败：${it.message}") }
        }

        backup.cloudModels?.forEach { p: CloudModelProfile ->
            runCatching {
                container.cloudModelRepository.upsert(p.copy(id = 0, apiKey = ""))
                clouds++
            }.onFailure { warnings.add("云端模型「${p.name}」导入失败：${it.message}") }
        }

        if (!backup.settings.isNullOrEmpty()) {
            runCatching {
                container.settingsRepository.importFrom(backup.settings)
                settings = backup.settings.size
            }.onFailure { warnings.add("设置导入失败：${it.message}") }
        }

        backup.images?.forEach { img ->
            runCatching {
                val bytes = Base64.decode(img.base64, Base64.DEFAULT)
                val safe = img.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val dir = File(context.filesDir, "attachments").apply { if (!exists()) mkdirs() }
                File(dir, "import_${System.currentTimeMillis()}_$safe").writeBytes(bytes)
                images++
            }.onFailure { warnings.add("图片「${img.name}」导入失败：${it.message}") }
        }

        return ImportReport(sessions, messages, clouds, settings, images, warnings)
    }

    private suspend fun importExternal(
        conversations: List<ExternalChatParser.ParsedConversation>,
        parseWarnings: List<String>,
    ): ImportReport {
        val warnings = parseWarnings.toMutableList()
        var sessions = 0
        var messages = 0
        val now = System.currentTimeMillis()
        conversations.forEach { conv ->
            runCatching {
                val newId = container.chatRepository.createSession(
                    title = conv.title, modelPath = null, mode = "CHAT"
                )
                sessions++
                conv.messages.forEach { m ->
                    val ts = if (m.ts > 0) m.ts else now
                    container.chatRepository.addMessage(
                        newId,
                        ChatMessage(sessionId = newId, role = m.role, content = m.content, ts = ts)
                    )
                    messages++
                }
            }.onFailure { warnings.add("会话「${conv.title}」导入失败：${it.message}") }
        }
        if (sessions == 0 && warnings.isEmpty()) warnings.add("未识别到可导入的对话，请选择正确的来源格式")
        return ImportReport(conversations = sessions, messages = messages, warnings = warnings)
    }

    /** 收集 attachments 目录下的图片为 base64（仅在用户勾选时调用）。 */
    private fun collectImages(): List<ImageBackup> {
        val dir = File(context.filesDir, "attachments")
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif") }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .take(200) // 防止备份文件过大
            .mapNotNull { f ->
                runCatching {
                    val mime = when (f.extension.lowercase()) {
                        "png" -> "image/png"
                        "webp" -> "image/webp"
                        "gif" -> "image/gif"
                        else -> "image/jpeg"
                    }
                    ImageBackup(f.name, mime, Base64.encodeToString(f.readBytes(), Base64.DEFAULT))
                }.getOrNull()
            }
    }
}
