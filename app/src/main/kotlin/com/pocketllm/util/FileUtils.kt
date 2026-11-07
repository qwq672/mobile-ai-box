package com.pocketllm.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object FileUtils {

    /** 应用专属模型目录 */
    fun modelsDir(context: Context): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 从 SAF Uri 复制到 models 目录，返回本地 File */
    fun copyUriToModels(context: Context, uri: Uri): File {
        val name = queryName(context, uri)
        val target = File(modelsDir(context), name)
        val input = context.contentResolver.openInputStream(uri) ?: error("无法打开 uri")
        input.use { ins ->
            FileOutputStream(target).use { fos ->
                ins.copyTo(fos, bufferSize = 1 shl 20)  // 1MB buffer
            }
        }
        return target
    }

    /** 计算 .gguf 文件的真实大小（MB） */
    fun fileSizeMb(file: File): Long = file.length() / 1024 / 1024

    /** 列出所有已导入的 .gguf */
    fun listGguf(context: Context): List<File> =
        modelsDir(context).listFiles { f -> f.isFile && f.name.endsWith(".gguf") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    private fun queryName(context: Context, uri: Uri): String {
        var name = "imported_${System.currentTimeMillis()}.gguf"
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
        }
        if (!name.endsWith(".gguf")) name = "$name.gguf"
        return name
    }
}
