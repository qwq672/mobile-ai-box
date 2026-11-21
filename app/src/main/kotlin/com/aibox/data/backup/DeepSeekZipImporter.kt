package com.aibox.data.backup

import com.aibox.AIBoxApp
import com.aibox.data.model.ChatMessage
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * DeepSeek 新版全量导出（seek_data-20xx.zip）流式导入器。
 *
 * 新版结构与旧版 conversations.json 完全不同：
 * ```
 * seek_data-20xx/
 *   chat_session_index.json     // 会话索引（id → 标题/时间）
 *   manifest.json / user.json   // 元数据，忽略
 *   chat_sessions/
 *     <uuid>/  *.json           // 每个会话一个文件夹，内含消息 json
 * ```
 *
 * 设计铁律（对应用户诉求：不拆包、不减数据、整包不入内存）：
 * - 两遍流式扫描 zip：第一遍只读索引建立 id→标题映射；第二遍逐个会话文件夹解析并落库。
 * - 峰值内存 = 单个会话文件的 JSON 树（通常几十 KB~几 MB），而非整包 300+MB。
 * - [openStream] 每次返回一个新的 InputStream（SAF uri 可重复 openInputStream）。
 * - 消息字段自适应：DS 各版本字段名会漂移，用宽容探测提取 role/content/timestamp，
 *   认不出的结构记录 warning 跳过，绝不因单条格式异常中断整包导入。
 */
object DeepSeekZipImporter {

    data class Progress(val done: Int, val total: Int)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val TAG = "aibox.backup.dszip"

    /**
     * @param openStream 每次调用返回一个全新的 zip InputStream（由调用方从 SAF uri 打开）。
     * @param onProgress 每落库一个会话回调一次（done/total）。
     */
    suspend fun import(
        openStream: () -> InputStream?,
        onProgress: (Progress) -> Unit = {},
    ): ImportReport = withContext(Dispatchers.IO) {
        val warnings = mutableListOf<String>()
        val titleMap = mutableMapOf<String, String>()
        var estimatedTotal = 0

        // ---------- 第一遍：读索引 + 统计会话文件夹数 ----------
        runCatching {
            openStream()?.use { raw ->
                ZipInputStream(raw.buffered()).use { zis ->
                    val seenFolders = HashSet<String>()
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        val path = entry.name.replace('\\', '/')
                        if (!entry.isDirectory && path.endsWith("chat_session_index.json", true)) {
                            val text = zis.readBytes().toString(Charsets.UTF_8)
                            parseIndex(text, titleMap)
                        }
                        // 统计 chat_sessions/<uuid>/ 下的会话文件夹数（用于进度 total）
                        sessionIdFromPath(path)?.let { seenFolders.add(it) }
                        zis.closeEntry()
                    }
                    estimatedTotal = seenFolders.size
                }
            }
        }.onFailure { warnings.add("读取会话索引失败：${it.message ?: "格式错误"}（将用文件夹名作为标题）") }

        // ---------- 第二遍：逐会话文件夹解析并落库 ----------
        var sessions = 0
        var messages = 0
        // 同一 uuid 文件夹的多个 json 先聚合，换文件夹/流结束时再一次性落库。
        var currentUuid: String? = null
        val buffer = mutableListOf<String>()

        suspend fun flushCurrent() {
            val uuid = currentUuid ?: return
            flushSession(uuid, buffer.toList(), titleMap, warnings)?.let { n ->
                sessions++
                messages += n
                onProgress(Progress(sessions, maxOf(estimatedTotal, sessions)))
            }
            buffer.clear()
            currentUuid = null
        }

        runCatching {
            openStream()?.use { raw ->
                ZipInputStream(raw.buffered()).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        val path = entry.name.replace('\\', '/')
                        if (!entry.isDirectory && path.contains("chat_sessions/") && path.endsWith(".json", true)) {
                            val uuid = sessionIdFromPath(path)
                            if (uuid != null) {
                                if (currentUuid != null && currentUuid != uuid) flushCurrent()
                                currentUuid = uuid
                                buffer += zis.readBytes().toString(Charsets.UTF_8)
                            }
                        }
                        zis.closeEntry()
                    }
                }
            } ?: warnings.add("无法打开所选 zip 文件")
            flushCurrent() // 收尾最后一个会话
        }.onFailure {
            LogSystem.warn(TAG, "scan zip failed: ${it.message}", it)
            warnings.add("扫描 zip 失败：${it.message ?: "未知错误"}")
        }

        if (sessions == 0 && warnings.none { it.contains("索引") }) {
            warnings.add("未在 zip 中找到 chat_sessions/ 会话数据，请确认是 DeepSeek 新版全量导出包")
        }
        ImportReport(conversations = sessions, messages = messages, warnings = warnings)
    }

    /** 从 chat_sessions/<uuid>/xxx.json 中取 uuid（文件夹名）。 */
    private fun sessionIdFromPath(path: String): String? {
        val marker = "chat_sessions/"
        val i = path.indexOf(marker, ignoreCase = true)
        if (i < 0) return null
        val rest = path.substring(i + marker.length)
        val folder = rest.substringBefore('/')
        return folder.takeIf { it.isNotBlank() && !it.startsWith(".") }
    }

    /** 解析 chat_session_index.json，提取 id→标题。结构漂移时降级为空映射。 */
    private fun parseIndex(text: String, out: MutableMap<String, String>) {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return
        val elems: List<JsonElement> = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["sessions"] as? JsonArray)
                ?: (root["data"] as? JsonArray)
                ?: (root["chat_sessions"] as? JsonArray)
                ?: listOf(root)
            else -> return
        }
        elems.forEach { e ->
            val o = e as? JsonObject ?: return@forEach
            val id = o.str("id") ?: o.str("session_id") ?: o.str("uuid") ?: o.str("sessionId")
            val title = o.str("title") ?: o.str("name") ?: o.str("session_name")
            if (!id.isNullOrBlank() && !title.isNullOrBlank()) out[id] = title
        }
    }

    /**
     * 一个会话可能对应多个 json 文件：全部解析、合并、按时间排序后一次落库。
     * @return 落库的消息条数；无可提取消息时返回 null（不创建空会话）。
     */
    private suspend fun flushSession(
        uuid: String,
        texts: List<String>,
        titleMap: Map<String, String>,
        warnings: MutableList<String>,
    ): Int? {
        val msgs = mutableListOf<ExternalChatParser.ParsedMessage>()
        texts.forEach { t ->
            val root = runCatching { json.parseToJsonElement(t) }.getOrNull() ?: return@forEach
            msgs += extractMessages(root)
        }
        val valid = msgs.filter { it.content.isNotBlank() && (it.role == "user" || it.role == "assistant") }
            .distinctBy { Triple(it.role, it.content, it.ts) }
            .sortedBy { if (it.ts > 0) it.ts else Long.MAX_VALUE }
        if (valid.isEmpty()) {
            if (warnings.size < 30) warnings.add("会话 $uuid 未提取到有效消息，已跳过")
            return null
        }
        val title = titleMap[uuid] ?: titleMap.keys.firstOrNull { uuid.startsWith(it) }?.let { titleMap[it] }
        ?: prettyFolderName(uuid)
        return runCatching {
            val newId = AIBoxApp.instance.container.chatRepository.createSession(
                title = title, modelPath = null, mode = "CHAT"
            )
            valid.forEach { m ->
                val ts = if (m.ts > 0) m.ts else System.currentTimeMillis()
                AIBoxApp.instance.container.chatRepository.addMessage(
                    newId,
                    ChatMessage(sessionId = newId, role = m.role, content = m.content, ts = ts)
                )
            }
            valid.size
        }.onFailure {
            if (warnings.size < 30) warnings.add("会话「$title」落库失败：${it.message}")
        }.getOrNull()
    }

    /** UUID 文件夹名美化：去掉 _files 等后缀，取前 8 位作为短标题。 */
    private fun prettyFolderName(folder: String): String {
        val base = folder.substringBefore('_')
        return if (base.length >= 8) "导入会话 ${base.take(8)}" else "导入会话 $folder"
    }

    /**
     * 自适应提取消息：兼容多种 DS 版本字段。
     * - 容器：顶层数组 / {messages:[...]} / {chat:[...]} / {data:[...]} / 单条对象
     * - role：role / sender / author / type / from（user/human/request→user，其余→assistant）
     * - content：content / text / message / body / value（字符串或嵌套 parts）
     * - ts：timestamp / created_at / createdAt / inserted_at / time / ts（毫秒秒/ISO）
     */
    private fun extractMessages(root: JsonElement): List<ExternalChatParser.ParsedMessage> {
        val items: List<JsonElement> = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["messages"] as? JsonArray)
                ?: (root["chat"] as? JsonArray)
                ?: (root["data"] as? JsonArray)
                ?: (root["conversation"] as? JsonArray)
                ?: listOf(root)
            else -> return emptyList()
        }
        return items.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val roleRaw = o.str("role") ?: o.str("sender") ?: o.str("author")
                ?: o.str("type") ?: o.str("from") ?: return@mapNotNull null
            val role = when {
                roleRaw.equals("user", true) || roleRaw.equals("human", true) ||
                    roleRaw.equals("REQUEST", true) || roleRaw.equals("question", true) -> "user"
                roleRaw.equals("system", true) -> "user" // 系统提示并入用户侧，避免丢失
                else -> "assistant"
            }
            val content = extractContent(o) ?: return@mapNotNull null
            val ts = extractTs(o)
            ExternalChatParser.ParsedMessage(role, content, ts)
        }
    }

    private fun extractContent(o: JsonObject): String? {
        val direct = o.str("content") ?: o.str("text") ?: o.str("message")
            ?: o.str("body") ?: o.str("value")
        if (!direct.isNullOrBlank()) return direct
        // content 可能是 {parts:[...]} 或 fragments:[{content}]（思考链+正文合并）
        val parts = (o["content"] as? JsonObject)?.get("parts") as? JsonArray
            ?: o["parts"] as? JsonArray
            ?: o["fragments"] as? JsonArray
        val texts = parts?.mapNotNull { p ->
            when (p) {
                is JsonPrimitive -> p.contentOrNull
                is JsonObject -> p.str("content") ?: p.str("text")
                else -> null
            }
        }?.filter { !it.isNullOrBlank() }?.joinToString("\n")
        return texts?.takeIf { it.isNotBlank() }
    }

    private fun extractTs(o: JsonObject): Long {
        val raw = o.str("timestamp") ?: o.str("created_at") ?: o.str("createdAt")
            ?: o.str("inserted_at") ?: o.str("time") ?: o.str("ts") ?: return 0L
        raw.toLongOrNull()?.let { n ->
            return if (n > 1_000_000_000_000L) n else n * 1000L
        }
        return runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrDefault(0L)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
