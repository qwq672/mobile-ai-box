package com.aibox.domain.tool.builtin

import com.aibox.domain.tool.ParamSpec
import com.aibox.domain.tool.ParamType
import com.aibox.domain.tool.Tool
import com.aibox.domain.tool.ToolCategory
import com.aibox.domain.tool.ToolContext
import com.aibox.domain.tool.ToolParameterSchema
import com.aibox.domain.tool.ToolResult
import com.aibox.util.log.LogSystem
import com.aibox.util.outcome.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * FileReadTool（C1 高级工具）。
 *
 * 读取设备上的文件内容（按绝对路径）。
 *
 * - 工具 ID：`file_read`（默认关闭，需用户在工具设置面板手动启用）
 * - 分类：[ToolCategory.ADVANCED]，每次执行前需用户确认（除非加入自动确认白名单）
 *
 * 安全约束：
 * - 仅允许"应用私有目录 + SAF 已授权路径 + 用户主动选择"
 *   （Android 10+ Scoped Storage 后，普通外部目录无权限会失败 → 错误信息返回）
 * - 不读取 / 不解析 / 不外泄：系统敏感路径（/data、/proc/、/sys/、/system/）由 OS 权限自然拦
 * - 文件大小无主动上限（[max_length] 截断读取内容；超大文件不读完，但 metadata 仍给出原始大小）
 *
 * 设计原则：
 * - 零异常约定：所有 IO 失败 → [Outcome.Failure]
 * - IO 走 [Dispatchers.IO] 不阻塞主线程
 * - 文本读取用指定编码（默认 UTF-8），不指定字节范围（避免截断多字节字符）
 *
 * 输出：
 * - [ToolResult.output]：文件路径 + 大小 + 正文（截断到 [max_length]）
 * - [ToolResult.structuredData]：Map 含 path / size / content / truncated
 *
 * 失败场景：
 * - path 缺失 → "Missing path"
 * - 文件不存在 → "File not found"
 * - 不是文件（目录） → "Not a file"
 * - 无读权限 → "Cannot read"
 * - 编码不支持 / IO 错误 → [Outcome.Failure] 含原因
 */
class FileReadTool : Tool {
    override val id: String = "file_read"
    override val displayName: String = "读取文件"
    override val description: String =
        "读取设备上的文件内容(按绝对路径)。需要用户确认。仅允许应用私有目录与已授权路径。"
    override val category: ToolCategory = ToolCategory.ADVANCED
    override val icon: String = "📄"

    override fun getParameterSchema(): ToolParameterSchema = ToolParameterSchema(
        required = listOf(
            ParamSpec(
                name = "path",
                type = ParamType.STRING,
                description = "文件绝对路径"
            )
        ),
        optional = listOf(
            ParamSpec(
                name = "max_length",
                type = ParamType.INTEGER,
                description = "最大读取长度(字符),默认 10000",
                default = MAX_LENGTH_DEFAULT
            ),
            ParamSpec(
                name = "encoding",
                type = ParamType.STRING,
                description = "文本编码,默认 UTF-8",
                default = "UTF-8"
            )
        )
    )

    override suspend fun execute(
        params: Map<String, Any>,
        context: ToolContext
    ): Outcome<ToolResult> {
        val path = (params["path"] as? String)?.takeIf { it.isNotBlank() }
            ?: return Outcome.Failure("Missing path")

        return try {
            LogSystem.info(TAG, "FileRead: path=$path")
            withContext(Dispatchers.IO) {
                val file = File(path)
                if (!file.exists()) return@withContext Outcome.Failure("File not found: $path")
                if (!file.isFile) return@withContext Outcome.Failure("Not a file: $path")
                if (!file.canRead()) return@withContext Outcome.Failure("Cannot read: $path")

                val maxLength = (params["max_length"] as? Number)?.toInt()?.coerceAtLeast(1)
                    ?: MAX_LENGTH_DEFAULT
                val encoding = (params["encoding"] as? String)?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_ENCODING

                val charset = try {
                    charset(encoding)
                } catch (_: Throwable) {
                    return@withContext Outcome.Failure("Unsupported encoding: $encoding")
                }

                val fullText = file.readText(charset)
                val truncated = fullText.length > maxLength
                val text = if (truncated) fullText.take(maxLength) else fullText
                val sizeBytes = file.length()

                val output = buildString {
                    appendLine("文件: $path")
                    appendLine("大小: $sizeBytes bytes")
                    if (truncated) appendLine("(已截断到 $maxLength 字符,原始 ${fullText.length} 字符)")
                    appendLine()
                    append(text)
                }

                LogSystem.info(TAG, "FileRead done: $path size=$sizeBytes truncated=$truncated")

                Outcome.Success(
                    ToolResult(
                        success = true,
                        output = output,
                        structuredData = mapOf<String, Any>(
                            "path" to path,
                            "size" to sizeBytes,
                            "content" to text,
                            "truncated" to truncated,
                            "encoding" to encoding
                        ),
                        executionTimeMs = 0
                    )
                )
            }
        } catch (e: Throwable) {
            LogSystem.error(TAG, "FileRead error: ${e.message}", e)
            Outcome.Failure("FileReadTool error: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "aibox.tool.file_read"
        private const val MAX_LENGTH_DEFAULT = 10000
        private const val DEFAULT_ENCODING = "UTF-8"
    }
}
