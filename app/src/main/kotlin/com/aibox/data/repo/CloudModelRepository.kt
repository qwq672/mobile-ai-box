package com.aibox.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aibox.domain.cloud.CloudConfig
import com.aibox.util.security.CloudKeyCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 用户保存的云端模型配置（可多个，独立出现在模型列表里）。
 *
 * 与 `settings` DataStore 里那份「当前生效的 CloudConfig」不同：
 * 这里是配置集合，选中后才会写入 settings 并启用云端。
 */
@Serializable
data class CloudModelProfile(
    val id: Long = 0,
    val name: String,
    val baseUrl: String,
    val apiKey: String = "",
    val model: String,
    val supportsVision: Boolean = false,
    val supportsAudio: Boolean = false,
    val builtinWebSearch: Boolean = false,
    val builtinUrlReader: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

private val Context.cloudModelsDataStore by preferencesDataStore("cloud_models")

/**
 * 云端模型配置集合的持久化。
 *
 * 用 JSON 存在单个 key 里：条目少（通常 1–5 个），无需 Room 迁移，
 * 也方便以后导出/导入整份配置。
 */
class CloudModelRepository(private val context: Context) {

    private val profilesKey = stringPreferencesKey("profiles_json")
    private val activeKey = stringPreferencesKey("active_id")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val keyCipher = CloudKeyCipher(context.applicationContext)

    val profiles: Flow<List<CloudModelProfile>> = context.cloudModelsDataStore.data.map { p ->
        withContext(Dispatchers.IO) {
            decode(p[profilesKey]).map { it.copy(apiKey = decryptKey(it.apiKey)) }
        }
    }

    /** 当前选中的云端模型 id（0 = 未选）。 */
    val activeId: Flow<Long> = context.cloudModelsDataStore.data.map { p ->
        p[activeKey]?.toLongOrNull() ?: 0L
    }

    suspend fun list(): List<CloudModelProfile> = profiles.first()

    suspend fun get(id: Long): CloudModelProfile? = profiles.first().firstOrNull { it.id == id }

    suspend fun upsert(profile: CloudModelProfile): Long {
        val id = if (profile.id > 0) profile.id else System.currentTimeMillis()
        val saved = profile.copy(
            id = id,
            apiKey = withContext(Dispatchers.IO) {
                if (profile.apiKey.isBlank()) "" else keyCipher.encrypt(profile.apiKey)
            }
        )
        context.cloudModelsDataStore.edit { p ->
            val current = decode(p[profilesKey]).filterNot { it.id == id } + saved
            p[profilesKey] = json.encodeToString(current.sortedBy { it.createdAt })
        }
        return id
    }

    suspend fun delete(id: Long) {
        context.cloudModelsDataStore.edit { p ->
            val next = decode(p[profilesKey]).filterNot { it.id == id }
            p[profilesKey] = json.encodeToString(next)
            if (p[activeKey]?.toLongOrNull() == id) p.remove(activeKey)
        }
    }

    suspend fun setActive(id: Long) {
        context.cloudModelsDataStore.edit { it[activeKey] = id.toString() }
    }

    /** 转成引擎/设置层使用的 [CloudConfig]。 */
    fun toConfig(profile: CloudModelProfile, enabled: Boolean = true): CloudConfig = CloudConfig(
        enabled = enabled,
        baseUrl = profile.baseUrl.trim(),
        apiKey = profile.apiKey.trim(),
        model = profile.model.trim(),
        supportsVision = profile.supportsVision,
        supportsAudio = profile.supportsAudio,
        builtinWebSearch = profile.builtinWebSearch,
        builtinUrlReader = profile.builtinUrlReader
    )

    private fun decryptKey(raw: String): String {
        if (raw.isBlank()) return ""
        // 旧版本存的是明文：解密失败时按明文处理，下次保存会自动迁移成密文
        return keyCipher.decrypt(raw).ifBlank { raw }
    }

    private fun decode(raw: String?): List<CloudModelProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<CloudModelProfile>>(raw) }
            .getOrDefault(emptyList())
    }
}
