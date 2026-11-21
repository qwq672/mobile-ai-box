package com.aibox.data.backup

import com.aibox.AIBoxApp
import com.aibox.data.model.ChatAttachment
import com.aibox.data.model.ChatMessage
import com.aibox.data.model.MsgPart
import com.aibox.data.model.ToolHit
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * DeepSeek 新版全量导出（seek_data-20xx.zip）流式导入器。
 *
 * 真实结构（已用脱敏样例核对）：
 * ```
 * seek_data-20xx/
 *   chat_session_index.json              // [{id,title,message_count,inserted_at,updated_at}, ...]
 *   chat_sessions/
 *     <uuid>.json                         // 一个会话一个 json（直接位于 chat_sessions/ 下）
 *     <uuid>_files/<id>_<name>.webp       // 该会话的图片附件（export_path 指向这里）
 * ```
 * 旧版/变体里会话也可能是 `chat_sessions/<uuid>/xxx.json`，一并兼容。
 *
 * 单条会话 json：
 * ```
 * { id, title, current_message_id, inserted_at, updated_at,
 *   messages: [ { id, parent_id, role: USER|ASSISTANT, status, inserted_at,
 *                 fragments: [
 *                   {type:REQUEST, content}, {type:THINK, content},
 *                   {type:TOOL_SEARCH, queries, results:[{url,title,snippet}]},
 *                   {type:TOOL_OPEN, url, title, snippet},
 *                   {type:RESPONSE, content}, {type:FILE, files:[{export_path,file_name,is_image,file_size}]},
 *                   {type:CONTENT_RISK|TIP, ...}   // 丢弃
 *                 ] } ] }
 * ```
 *
 * 映射规则（对齐 DeepSeek 官方 chat-demo 的 toLocal）：
 * - REQUEST → 用户正文；THINK → [MsgPart.think]；RESPONSE → [MsgPart.text]
 * - TOOL_SEARCH → web_search 工具段（queries + results → [ToolHit]）
 * - TOOL_OPEN → url_reader 工具段（url + snippet）
 * - FILE(is_image) → [ChatAttachment](kind="image")，图片字节从 zip 落到应用私有目录
 * - CONTENT_RISK/TIP 等无正文片段丢弃
 * - branch：按 parent_id 从 current_message_id 回溯出当前分支链，链上的消息才落库；
 *   同一 parent 下的其它分支作为「助手版本」写入 [ChatMessage.versions]，可切换。
 * - 时间：会话用 inserted_at/updated_at，消息用各自的 inserted_at（ISO-8601）。
 *
 * 流式铁律：整包不入内存。两遍扫描，第二遍里图片逐个落地、会话文本按 uuid 缓存，
 * 峰值内存 = 单个会话 JSON 树（通常几十 KB~几 MB）。
 */
object DeepSeekZipImporter {

    data class Progress(val done: Int, val total: Int)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val TAG = "aibox.backup.dszip"
    private const val MAX_IMAGE_BYTES = 16L * 1024 * 1024

    suspend fun import(
        openStream: () -> InputStream?,
        onProgress: (Progress) -> Unit = {},
    ): ImportReport = withContext(Dispatchers.IO) {
        val warnings = mutableListOf<String>()
        val metaMap = mutableMapOf<String, SessionMeta>()

        // ---------- 第一遍：读索引（标题 + 真实时间 + 消息数） ----------
        runCatching {
            openStream()?.use { raw ->
                ZipInputStream(raw.buffered()).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        val path = normalize(entry.name)
                        if (!entry.isDirectory && path.endsWith("chat_session_index.json", true)) {
                            parseIndex(String(zis.readBytes(), Charsets.UTF_8), metaMap)
                        }
                        zis.closeEntry()
                    }
                }
            }
        }.onFailure { warnings.add("读取会话索引失败：${it.message ?: "格式错误"}（将用会话内标题或文件夹名）") }

        // ---------- 第二遍：落地图片 + 缓存会话文本 ----------
        // key = 会话 uuid；value = 该会话的所有 json 文本（新版一个文件，旧版可能多个）
        val sessionTexts = LinkedHashMap<String, MutableList<String>>()
        // export_path（归一化，去掉顶层目录）→ 应用私有目录里的绝对路径
        val imagePaths = HashMap<String, String>()

        runCatching {
            openStream()?.use { raw ->
                ZipInputStream(raw.buffered()).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        if (entry.isDirectory) { zis.closeEntry(); continue }
                        val path = normalize(entry.name)

                        if (isSessionJson(path)) {
                            val uuid = uuidFromJsonPath(path)
                            if (uuid != null && entry.size < 40L * 1024 * 1024) {
                                sessionTexts.getOrPut(uuid) { mutableListOf() }
                                    .add(String(zis.readBytes(), Charsets.UTF_8))
                            }
                        } else if (isImageEntry(path)) {
                            // 图片逐张落地，绝不缓存字节到内存
                            runCatching { extractImage(zis, path, imagePaths) }
                                .onFailure { LogSystem.warn(TAG, "落地图片失败 path=$path: ${it.message}") }
                        }
                        zis.closeEntry()
                    }
                }
            } ?: warnings.add("无法打开所选 zip 文件")
        }.onFailure {
            LogSystem.warn(TAG, "scan zip failed: ${it.message}", it)
            warnings.add("扫描 zip 失败：${it.message ?: "未知错误"}")
        }

        // ---------- 逐会话解析并落库 ----------
        val total = sessionTexts.size
        var sessions = 0
        var messages = 0
        for ((uuid, texts) in sessionTexts) {
            val count = flushSession(uuid, texts, metaMap[uuid], imagePaths, warnings)
            if (count != null) {
                sessions++
                messages += count
            }
            onProgress(Progress(sessions, maxOf(total, sessions)))
        }

        if (sessions == 0) {
            warnings.add("未在 zip 中找到 chat_sessions/ 会话数据，请确认是 DeepSeek 新版全量导出包")
        }
        ImportReport(conversations = sessions, messages = messages, warnings = warnings)
    }

    // ---------------- 会话落库 ----------------

    private suspend fun flushSession(
        uuid: String,
        texts: List<String>,
        meta: SessionMeta?,
        imagePaths: Map<String, String>,
        warnings: MutableList<String>,
    ): Int? {
        val root = texts.firstNotNullOfOrNull { t ->
            runCatching { json.parseToJsonElement(t) as? JsonObject }.getOrNull()
        } ?: run {
            if (warnings.size < 30) warnings.add("会话 $uuid JSON 解析失败，已跳过")
            return null
        }

        val title = meta?.title?.takeIf { it.isNotBlank() }
            ?: root.str("title")?.takeIf { it.isNotBlank() }
            ?: prettyFolderName(uuid)

        val messagesJson = root["messages"] as? JsonArray
        if (messagesJson == null || messagesJson.isEmpty()) {
            if (warnings.size < 30) warnings.add("会话「$title」没有消息，已跳过")
            return null
        }

        // 解析全部消息（含 id/parent_id），按 id 排序（DS 的 id 是按时间递增的整数）
        val all = mutableListOf<DsMessage>()
        messagesJson.forEach { el ->
            parseMessage(el as? JsonObject ?: return@forEach, imagePaths)?.let { all += it }
        }
        if (all.isEmpty()) return null
        val byId = all.associateBy { it.id }

        // 选当前分支：从 current_message_id 沿 parent_id 回溯到根
        val currentId = root.long("current_message_id")
        val activeIds = activeBranchIds(byId, currentId)
        val active = all.filter { it.id in activeIds }.sortedBy { it.id }
        val chain = if (active.isNotEmpty()) active else all.sortedBy { it.id }

        // 每条用户消息下，同 parent 的其它用户分支 → 作为该用户消息的 versions 供切换
        val userVersions = buildUserVersions(all, chain)

        val createdAt = meta?.createdAt ?: root.isoTime("inserted_at") ?: System.currentTimeMillis()
        val updatedAt = meta?.updatedAt ?: root.isoTime("updated_at") ?: createdAt

        return runCatching {
            val repo = AIBoxApp.instance.container.chatRepository
            val newId = repo.createSessionAt(title, "CHAT", createdAt, updatedAt)
            var written = 0
            chain.forEachIndexed { _, dm ->
                // 跳过完全没有可见内容的消息（例如只有被丢弃的 CONTENT_RISK 片段）
                if (dm.content.isBlank() && dm.parts.isEmpty() && dm.attachments.isEmpty()) return@forEachIndexed
                val ts = if (dm.ts > 0) dm.ts else createdAt
                val versions = userVersions[dm.id] ?: listOf(dm.content)
                val vi = versions.indexOf(dm.content).let { if (it >= 0) it else 0 }
                // USER 分支消息：把同 parent 的其它用户提问作为版本写入
                val isUser = dm.role == "user"
                val msg = ChatMessage(
                    sessionId = newId,
                    role = dm.role,
                    content = dm.content,
                    ts = ts,
                    parts = if (dm.parts.isNotEmpty() && !isUser) dm.parts else emptyList(),
                    attachments = dm.attachments,
                    // 助手消息也带上自己的正文作为单版本；分支切换主要作用于用户消息
                    versions = if (isUser) versions else listOf(dm.content),
                    versionIndex = if (isUser) vi else 0
                )
                repo.addMessageAt(newId, msg)
                written++
            }
            if (written > 0) repo.setSessionMessageCount(newId, written)
            // 定稿时间：即便落消息链里有人 touch 过，也把列表显示/排序用的 lastUsedAt
            // 锁回数据里标注的时间，确保历史列表按真实时间而非导入时刻排列。
            repo.setSessionTimes(newId, createdAt, updatedAt)
            written
        }.onFailure {
            if (warnings.size < 30) warnings.add("会话「$title」落库失败：${it.message}")
        }.getOrNull()?.takeIf { it > 0 }
    }

    /**
     * 对用户消息，收集「同一 parent 下的所有用户分支」正文作为 versions；
     * 这样导入后可在消息上用版本切换器查看 DS 里的多条用户分支。
     */
    private fun buildUserVersions(
        all: List<DsMessage>,
        chain: List<DsMessage>
    ): Map<Long, List<String>> {
        val out = HashMap<Long, List<String>>()
        chain.filter { it.role == "user" }.forEach { u ->
            val siblings = all.filter { it.role == "user" && it.parentId == u.parentId }
                .sortedBy { it.id }
                .map { it.content }
                .filter { it.isNotBlank() }
                .distinct()
            if (siblings.size > 1) out[u.id] = siblings
        }
        return out
    }

    /** 从 current_message_id 回溯 parent 链；current 无效时返回空集（调用方回退到全部消息）。 */
    private fun activeBranchIds(byId: Map<Long, DsMessage>, currentId: Long?): Set<Long> {
        if (currentId == null || currentId <= 0) return emptySet()
        val out = HashSet<Long>()
        var cur: Long? = currentId
        val guard = IntArray(1)
        while (cur != null && byId.containsKey(cur) && guard[0]++ < 10000) {
            out.add(cur)
            cur = byId[cur]?.parentId
        }
        return out
    }

    // ---------------- 单条消息解析 ----------------

    private data class DsMessage(
        val id: Long,
        val parentId: Long?,
        val role: String,
        val ts: Long,
        val content: String,
        val parts: List<MsgPart>,
        val attachments: List<ChatAttachment>
    )

    private fun parseMessage(o: JsonObject, imagePaths: Map<String, String>): DsMessage? {
        val id = o.long("id") ?: return null
        val parentId = o.long("parent_id")
        val roleRaw = o.str("role").orEmpty()
        val role = if (roleRaw.equals("USER", true) || roleRaw.equals("user", true)) "user" else "assistant"
        val ts = o.isoTime("inserted_at")

        val contentParts = StringBuilder()
        val timeline = mutableListOf<MsgPart>()
        val attachments = mutableListOf<ChatAttachment>()
        val isUser = role == "user"

        val fragments = o["fragments"] as? JsonArray ?: JsonArray(emptyList())
        fragments.forEach { fe ->
            val f = fe as? JsonObject ?: return@forEach
            when (f.str("type")?.uppercase()) {
                "REQUEST" -> f.str("content")?.let { contentParts.append(it) }
                "RESPONSE" -> f.str("content")?.let { text ->
                    contentParts.append(text)
                    // 助手正文也进时间线，与 think/tool 保持 DS 原有的交错顺序；
                    // 否则 parts 非空时 UI 只渲染 parts，正文会整段消失。
                    if (!isUser && text.isNotBlank()) timeline += MsgPart.text(text)
                }
                "THINK" -> f.str("content")?.takeIf { it.isNotBlank() }?.let { timeline += MsgPart.think(it) }
                "TOOL_SEARCH" -> {
                    val queries = (f["queries"] as? JsonArray).orEmpty()
                        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    val hits = (f["results"] as? JsonArray).orEmpty().mapNotNull { r ->
                        val ro = r as? JsonObject ?: return@mapNotNull null
                        ToolHit(
                            title = ro.str("title").orEmpty(),
                            url = ro.str("url").orEmpty(),
                            snippet = ro.str("snippet").orEmpty()
                        )
                    }.filter { it.url.isNotBlank() }
                    timeline += MsgPart.tool(
                        toolId = "web_search",
                        toolName = "联网搜索",
                        args = buildJsonObject {
                            putJsonArray("queries") { queries.forEach { add(it) } }
                        }.toString(),
                        output = queries.joinToString("、"),
                        hits = hits
                    )
                }
                "TOOL_OPEN" -> {
                    val url = f.str("url").orEmpty()
                    val snippet = f.str("snippet").orEmpty()
                    timeline += MsgPart.tool(
                        toolId = "url_reader",
                        toolName = "网页阅读",
                        args = buildJsonObject { put("url", url) }.toString(),
                        output = snippet,
                        hits = if (url.isNotBlank()) listOf(ToolHit(f.str("title").orEmpty(), url, snippet)) else emptyList()
                    )
                }
                "FILE" -> {
                    val files = f["files"] as? JsonArray ?: return@forEach
                    files.forEach { fileEl ->
                        val fo = fileEl as? JsonObject ?: return@forEach
                        val exportPath = fo.str("export_path")
                        val isImage = fo.str("is_image")?.toBooleanStrictOrNull() == true || exportPath?.isImageName() == true
                        val local = exportPath?.let { normalize(it) }?.let { imagePaths[it] }
                        if (isImage && local != null) {
                            val ext = local.substringAfterLast('.', "webp")
                            attachments += ChatAttachment(
                                kind = "image",
                                uri = local,
                                name = fo.str("file_name") ?: File(local).name,
                                mime = imageMime(ext),
                                sizeBytes = fo.long("file_size") ?: runCatching { File(local).length() }.getOrDefault(0)
                            )
                        } else if (exportPath != null) {
                            // 图片没落地成功也留一条文本占位，避免用户以为消息里什么都没有
                            contentParts.append("\n\n[附件：${fo.str("file_name") ?: exportPath}]")
                        }
                    }
                }
                // CONTENT_RISK / TIP / 未知类型：丢弃
                else -> Unit
            }
        }

        // 没有任何 think/tool 段时，把时间线收敛为空：让 UI 回退到 content 走 Markdown
        // 渲染（效果一致且更省）；有 think/tool 时保留完整交错时间线。
        val hasStructured = timeline.any { it.kind != MsgPart.KIND_TEXT }
        val finalTimeline = if (hasStructured) timeline else emptyList()
        return DsMessage(
            id = id,
            parentId = parentId,
            role = role,
            ts = ts,
            content = contentParts.toString().trim(),
            parts = finalTimeline,
            attachments = attachments
        )
    }

    // ---------------- 图片落地 ----------------

    private fun extractImage(zis: ZipInputStream, zipPath: String, out: MutableMap<String, String>) {
        val ctx = AIBoxApp.instance.container.appContext
        val dir = File(ctx.filesDir, "attachments").apply { if (!exists()) mkdirs() }
        val ext = zipPath.substringAfterLast('.', "webp").lowercase()
            .let { if (it.length <= 5 && it.all { c -> c.isLetter() || c.isDigit() }) it else "webp" }
        val dest = File(dir, "ds_${UUID.randomUUID()}.$ext")
        val bytes = zis.readBytes()
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) {
            if (dest.exists()) dest.delete()
            return
        }
        dest.outputStream().use { it.write(bytes) }
        // zipPath 已去掉顶层目录，形如 chat_sessions/<uuid>_files/xxx.webp；
        // export_path 归一化后同样是这个形态，可直接作为 key。
        out[zipPath] = dest.absolutePath
    }

    // ---------------- 索引 / 路径 / JSON 工具 ----------------

    private data class SessionMeta(
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
        val messageCount: Int
    )

    private fun parseIndex(text: String, out: MutableMap<String, SessionMeta>) {
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
            if (id.isNullOrBlank()) return@forEach
            out[id] = SessionMeta(
                title = o.str("title") ?: o.str("name") ?: o.str("session_name").orEmpty(),
                createdAt = o.isoTime("inserted_at") ?: o.isoTime("created_at") ?: 0L,
                updatedAt = o.isoTime("updated_at") ?: o.isoTime("updated_at") ?: 0L,
                messageCount = o.long("message_count")?.toInt() ?: 0
            )
        }
    }

    /** 去掉 zip 顶层目录（seek_data-xxx/），统一分隔符。 */
    private fun normalize(raw: String): String {
        var p = raw.replace('\\', '/').trimStart('/')
        // 只去掉第一层目录；保留 chat_sessions/... 结构
        val firstSlash = p.indexOf('/')
        if (firstSlash > 0 && !p.startsWith("chat_sessions", true) &&
            !p.startsWith("chat_session_index", true)
        ) {
            p = p.substring(firstSlash + 1)
        }
        return p
    }

    private fun isSessionJson(path: String): Boolean =
        path.startsWith("chat_sessions", true) && path.endsWith(".json", true)

    /** 新版 chat_sessions/<uuid>.json，旧版 chat_sessions/<uuid>/xxx.json。 */
    private fun uuidFromJsonPath(path: String): String? {
        val rest = path.substringAfter("chat_sessions/", "")
        if (rest.isBlank()) return null
        val first = rest.substringBefore('/')
        // 新版：<uuid>.json
        if (first.contains('.')) return first.substringBefore('.').takeIf { it.isNotBlank() }
        // 旧版：<uuid>/xxx.json
        return first.takeIf { it.isNotBlank() && !it.startsWith(".") }
    }

    private fun isImageEntry(path: String): Boolean =
        path.startsWith("chat_sessions", true) && path.contains("_files/") && path.isImageName()

    private fun String.isImageName(): Boolean =
        substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic")

    private fun imageMime(ext: String): String = when (ext.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "heic" -> "image/heic"
        else -> "image/jpeg"
    }

    private fun prettyFolderName(folder: String): String {
        val base = folder.substringBefore('_')
        return if (base.length >= 8) "导入会话 ${base.take(8)}" else "导入会话 $folder"
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    /** ISO-8601（如 2026-06-27T14:40:40.281Z）→ epoch millis；兼容纯数字毫秒/秒。 */
    private fun JsonObject.isoTime(key: String): Long {
        val raw = str(key) ?: return 0L
        raw.toLongOrNull()?.let { n ->
            return if (n > 1_000_000_000_000L) n else n * 1000L
        }
        return runCatching {
            java.time.Instant.parse(raw).toEpochMilli()
        }.getOrElse {
            runCatching { java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrDefault(0L)
        }
    }
}
