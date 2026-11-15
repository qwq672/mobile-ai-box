package com.aibox.util

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

    /**
     * 列出所有已导入的模型文件（gguf / tflite / litertlm / onnx / mnn / bin）。
     * 不要只列 .gguf：否则 LiteRT 等模型导入后不会出现在列表里。
     */
    fun listGguf(context: Context): List<File> =
        modelsDir(context).listFiles { f -> f.isFile && isModelFile(f.name) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    private val MODEL_EXTENSIONS = setOf("gguf", "tflite", "litertlm", "onnx", "mnn", "bin")

    private fun isModelFile(name: String): Boolean =
        MODEL_EXTENSIONS.any { name.endsWith(".$it", ignoreCase = true) }

    private fun queryName(context: Context, uri: Uri): String {
        var name = "imported_${System.currentTimeMillis()}.gguf"
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
        }
        // 关键：保留模型真实后缀（.litertlm / .tflite …）。
        // 之前无条件补 .gguf 会把 LFM2.5-230M_int8.litertlm 变成 .litertlm.gguf，
        // 导致路由到 llama.cpp 后加载失败。
        val hasKnownExt = isModelFile(name)
        if (!hasKnownExt && !name.contains('.')) name = "$name.gguf"
        return name
    }
}
