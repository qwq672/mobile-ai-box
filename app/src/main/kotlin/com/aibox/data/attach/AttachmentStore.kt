package com.aibox.data.attach

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.aibox.data.model.ChatAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 附件导入：把用户从相册 / 文件选择器选中的内容落到应用私有目录，
 * 避免 content:// 授权在重启后失效，同时给云端多模态请求一个稳定来源。
 */
object AttachmentStore {

    private const val MAX_IMAGE_BYTES = 16L * 1024 * 1024
    private const val MAX_FILE_BYTES = 32L * 1024 * 1024
    private const val MAX_TEXT_CHARS = 24000

    sealed interface Result {
        data class Ok(val attachment: ChatAttachment) : Result
        data class Error(val message: String) : Result
    }

    private fun attachmentsDir(context: Context): File =
        File(context.filesDir, "attachments").apply { if (!exists()) mkdirs() }

    /** 导入图片（相册 / 相机）。 */
    suspend fun importImage(context: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "image/jpeg"
            val ext = when {
                mime.contains("png") -> "png"
                mime.contains("webp") -> "webp"
                mime.contains("gif") -> "gif"
                else -> "jpg"
            }
            val dest = File(attachmentsDir(context), "${UUID.randomUUID()}.$ext")
            val input = resolver.openInputStream(uri) ?: error("无法读取该图片")
            input.use { ins ->
                dest.outputStream().use { out ->
                    val copied = ins.copyTo(out)
                    if (copied > MAX_IMAGE_BYTES) {
                        dest.delete()
                        error("图片超过 16MB，请压缩后再发送")
                    }
                    if (copied == 0L) {
                        dest.delete()
                        error("图片为空")
                    }
                }
            }
            Result.Ok(
                ChatAttachment(
                    kind = "image",
                    uri = dest.absolutePath,
                    name = displayName(context, uri) ?: dest.name,
                    mime = mime,
                    sizeBytes = dest.length()
                )
            )
        }.getOrElse { e -> Result.Error(e.message ?: "导入图片失败") }
    }

    /** 导入文件。文本类文件抽取正文；PDF / 二进制暂不支持解析。 */
    suspend fun importFile(context: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val name = displayName(context, uri) ?: "attachment"
            val mime = resolver.getType(uri) ?: guessMime(name)
            val isText = isTextLike(name, mime)
            if (!isText) {
                return@runCatching Result.Error(
                    if (mime.contains("pdf")) "PDF 解析暂未支持，请先转成文本或图片"
                    else "暂不支持该文件类型（$mime）"
                )
            }
            val text = resolver.openInputStream(uri)?.use { ins ->
                val bytes = ins.readBytes()
                if (bytes.size > MAX_FILE_BYTES) {
                    return@runCatching Result.Error("文件超过 32MB")
                }
                String(bytes, Charsets.UTF_8)
            } ?: return@runCatching Result.Error("无法读取该文件")

            // 同时落一份原文件，便于后续引用
            val dest = File(attachmentsDir(context), "${UUID.randomUUID()}-${name.takeLast(64)}")
            runCatching {
                resolver.openInputStream(uri)?.use { ins -> dest.outputStream().use { ins.copyTo(it) } }
            }

            Result.Ok(
                ChatAttachment(
                    kind = "text",
                    uri = dest.absolutePath,
                    name = name,
                    mime = mime,
                    text = text.take(MAX_TEXT_CHARS),
                    sizeBytes = text.length.toLong()
                )
            )
        }.getOrElse { e -> Result.Error(e.message ?: "导入文件失败") }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "txt" -> "text/plain"
        "md" -> "text/markdown"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "kt", "java", "py", "js", "ts", "c", "cpp", "h" -> "text/plain"
        "html" -> "text/html"
        "xml" -> "application/xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    private fun isTextLike(name: String, mime: String): Boolean {
        if (mime.startsWith("text/")) return true
        if (mime.contains("json") || mime.contains("xml")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf(
            "txt", "md", "markdown", "json", "csv", "tsv", "log", "yaml", "yml",
            "kt", "java", "py", "js", "ts", "tsx", "jsx", "c", "cpp", "h", "hpp",
            "html", "css", "xml", "sh", "rs", "go", "swift"
        )
    }
}
