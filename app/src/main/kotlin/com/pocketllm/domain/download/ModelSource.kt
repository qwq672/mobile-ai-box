package com.pocketllm.domain.download

/**
 * 下载源抽象。三源结构差异较大，但都按 HF API 风格做适配。
 *
 * - HuggingFace：原始源，国内访问慢
 * - HF-Mirror：HF 的国内镜像，路径完全兼容
 * - ModelScope：阿里魔搭，国内最快，但 API 风格不同（独立适配）
 */
interface ModelSource {
    val id: String
    val displayNameZh: String
    val displayNameEn: String
    val baseUrl: String

    /**
     * 列出某仓库下的所有 .gguf 文件。
     * @param repo 例如 "Qwen/Qwen2.5-1.5B-Instruct-GGUF"
     */
    suspend fun listGgufFiles(repo: String): List<RemoteGgufFile>

    /**
     * 拿到某文件的下载 URL。
     */
    fun fileUrl(repo: String, path: String): String
}

data class RemoteGgufFile(
    val path: String,        // 仓库内相对路径，如 "qwen2.5-1.5b-instruct-q4_k_m.gguf"
    val sizeBytes: Long,
    val lastModified: String
)
