package com.pocketllm.domain.download

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Path

/** HuggingFace 原始源 */
open class HuggingFaceSource : ModelSource {
    override val id = "hf"
    override val displayNameZh = "HuggingFace（海外）"
    override val displayNameEn = "HuggingFace (Global)"
    override val baseUrl = "https://huggingface.co"

    // 必须用 `by lazy`：若直接用 `=` 初始化，子类 HfMirrorSource 在父类 init 阶段
    // 调用 retrofitBuild(baseUrl) 时，虚分派会读到子类尚未初始化的 baseUrl（null），
    // Kotlin 的非空校验会抛 NPE，导致 Application.onCreate 失败 → 启动闪退。
    private val api: HfApi by lazy { retrofitBuild(baseUrl).create(HfApi::class.java) }

    override suspend fun listGgufFiles(repo: String): List<RemoteGgufFile> {
        val tree = api.listTree(repo)
        return tree.filter { it.path.endsWith(".gguf") }.map {
            RemoteGgufFile(it.path, it.size ?: 0, it.lastCommit?.date ?: "")
        }
    }

    override fun fileUrl(repo: String, path: String): String =
        "$baseUrl/$repo/resolve/main/$path"
}

@JsonClass(generateAdapter = true)
data class HfTreeItem(
    @Json(name = "path") val path: String,
    @Json(name = "size") val size: Long? = null,
    @Json(name = "oid") val oid: String? = null,
    @Json(name = "lastCommit") val lastCommit: HfCommit? = null,
    @Json(name = "type") val type: String? = null
)

@JsonClass(generateAdapter = true)
data class HfCommit(
    @Json(name = "date") val date: String? = null,
    @Json(name = "oid") val oid: String? = null
)

interface HfApi {
    @GET("api/models/{repo}/tree/main?recursive=true")
    suspend fun listTree(@Path("repo") repo: String): List<HfTreeItem>
}

internal fun retrofitBuild(baseUrl: String): retrofit2.Retrofit {
    return retrofit2.Retrofit.Builder()
        .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
        .client(
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        )
        // 注册 KotlinJsonAdapterFactory：项目里没启用 moshi-kotlin-codegen（KSP），
        // 不加这个 Moshi 无法反射构造 Kotlin data class，所有 @JsonClass 注解失效。
        .addConverterFactory(retrofit2.converter.moshi.MoshiConverterFactory.create(
            com.squareup.moshi.Moshi.Builder()
                .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                .build()
        ))
        .build()
}
