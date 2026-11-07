package com.pocketllm.domain.download

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 阿里魔搭 ModelScope —— 国内下载最快。
 * API 与 HF 不同，需要独立适配。
 */
class ModelScopeSource : ModelSource {
    override val id = "modelscope"
    override val displayNameZh = "魔搭 ModelScope（国内）"
    override val displayNameEn = "ModelScope (China)"
    override val baseUrl = "https://modelscope.cn"

    private val api: MsApi = retrofitBuild(baseUrl).create(MsApi::class.java)

    override suspend fun listGgufFiles(repo: String): List<RemoteGgufFile> {
        // ModelScope 仓库名形如 "AI-ModelScope/Qwen2.5-1.5B-Instruct-GGUF"
        val resp = api.listFiles(repo, Root = "master")
        return resp.data?.files?.filter { it.path.endsWith(".gguf") }?.map {
            RemoteGgufFile(it.path, it.size, it.revision ?: "")
        } ?: emptyList()
    }

    override fun fileUrl(repo: String, path: String): String =
        "$baseUrl/api/v1/models/$repo/repo?Revision=master&FilePath=$path"
}

@JsonClass(generateAdapter = true)
data class MsListResp(
    @Json(name = "Data") val data: MsData? = null
)

@JsonClass(generateAdapter = true)
data class MsData(
    @Json(name = "Files") val files: List<MsFile>? = null
)

@JsonClass(generateAdapter = true)
data class MsFile(
    @Json(name = "Path") val path: String,
    @Json(name = "Size") val size: Long = 0,
    @Json(name = "Revision") val revision: String? = null
)

interface MsApi {
    @GET("api/v1/models/{repo}/repo/files")
    suspend fun listFiles(
        @Path("repo") repo: String,
        @Query("Revision") Root: String = "master",
        @Query("Root") root: String = "NULL"
    ): MsListResp
}
