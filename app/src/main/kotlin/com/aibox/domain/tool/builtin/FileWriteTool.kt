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
 * FileWriteTool（C1 高级工具）。
 *
 * 写入设备上的文件内容（按绝对路径）。支持 append（追加）与 overwrite（覆盖）两种模式。
 *
 * - 工具 ID：`file_write`（默认关闭，需用户在工具设置面板手动启用）
 * - 分类：[ToolCategory.ADVANCED]，每次执行前需用户确认（除非加入自动确认白名单）
 *
 * 安全约束（与 FileReadTool 一致）：
 * - 仅允许"应用私有目录 + SAF 已授权路径 + 用户主动选择"
 * - OS 权限拦不到的目录（如 externalStorage 根目录）会被自身写入失败兜底
 * - 高风险操作：**覆盖已存在文件会丢失原内容**；默认 mode=overwrite，UI 已通过确认 dialog 提示
 *
 * 设计原则：
 * - 零异常约定：所有 IO 失败 → [Outcome.Failure]
 * - IO 走 [Dispatchers.IO]
 * - 编码：默认 UTF-8；通过 charset() 解析失败时直接报错（避免写错编码）
 * - 自动创建父目录：[File.mkdirs]（若已存在 no-op）
 *
 * 输出：
 * - [ToolResult.output]：写入结果摘要（路径 / 模式 / 写入字节数）
 * - [ToolResult.structuredData]：Map 含 path / mode / bytesWritten / fileExistsBefore
 *
 * 失败场景：
 * - path 或 content 缺失 → "Missing path" / "Missing content"
 * - mode 非 overwrite/append → "Invalid mode"
 * - 编码不支持 → [Outcome.Failure]
 * - 父目录不可写 / 创建失败 → [Outcome.Failure]
 * - 写入 IO 异常 → [Outcome.Failure] 含原因
 */
class FileWriteTool : Tool {
    override val id: String = "file_write"
    override val displayName: String = "写入文件"
    override val description: String =
        "写入设备上的文件(按绝对路径)。支持覆盖与追加模式。需要用户确认。"
    override val category: ToolCategory = ToolCategory.ADVANCED
    override val icon: String = "📝"

    override fun getParameterSchema(): ToolParameterSchema = ToolParameterSchema(
        required = listOf(
            ParamSpec(
                name = "path",
                type = ParamType.STRING,
                description = "文件绝对路径"
            ),
            ParamSpec(
                name = "content",
                type = ParamType.STRING,
                description = "要写入的内容"
            )
        ),
        optional = listOf(
            ParamSpec(
                name = "mode",
                type = ParamType.STRING,
                description = "写入模式: overwrite(覆盖,默认) 或 append(追加)",
                enum = listOf("overwrite", "append"),
                default = "overwrite"
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
        val content = params["content"] as? String
            ?: return Outcome.Failure("Missing content")
        val mode = (params["mode"] as? String)?.takeIf { it.isNotBlank() } ?: "overwrite"
        if (mode != "overwrite" && mode != "append") {
            return Outcome.Failure("Invalid mode: $mode (must be overwrite or append)")
        }
        val encoding = (params["encoding"] as? String)?.takeIf { it.isNotBlank() } ?: DEFAULT_ENCODING

        return try {
            LogSystem.info(TAG, "FileWrite: path=$path mode=$mode contentLen=${content.length}")
            withContext(Dispatchers.IO) {
                val charset = try {
                    charset(encoding)
                } catch (_: Throwable) {
                    return@withContext Outcome.Failure("Unsupported encoding: $encoding")
                }

                val file = File(path)
                val existedBefore = file.exists()

                // 创建父目录(已存在 no-op；不可写时抛 SecurityException 被外层兜)
                val parent = file.parentFile
                if (parent != null && !parent.exists()) {
                    if (!parent.mkdirs()) {
                        return@withContext Outcome.Failure(
                            "Cannot create parent dir: ${parent.absolutePath}"
                        )
                    }
                }

                val bytesWritten = try {
                    if (mode == "append") {
                        file.appendText(content, charset)
                    } else {
                        file.writeText(content, charset)
                    }
                    content.toByteArray(charset).size
                } catch (e: SecurityException) {
                    return@withContext Outcome.Failure(
                        "No permission to write: $path (${e.message})"
                    )
                }

                LogSystem.info(TAG, "FileWrite done: $path mode=$mode bytes=$bytesWritten")

                Outcome.Success(
                    ToolResult(
                        success = true,
                        output = buildString {
                            appendLine("写入完成: $path")
                            appendLine("模式: $mode")
                            appendLine("写入字节数: $bytesWritten")
                            appendLine("文件已存在(写前): $existedBefore")
                        },
                        structuredData = mapOf<String, Any>(
                            "path" to path,
                            "mode" to mode,
                            "bytesWritten" to bytesWritten,
                            "fileExistsBefore" to existedBefore,
                            "encoding" to encoding
                        ),
                        executionTimeMs = 0
                    )
                )
            }
        } catch (e: Throwable) {
            LogSystem.error(TAG, "FileWrite error: ${e.message}", e)
            Outcome.Failure("FileWriteTool error: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "aibox.tool.file_write"
        private const val DEFAULT_ENCODING = "UTF-8"
    }
}
