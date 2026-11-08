package com.aibox.data.repo

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.aibox.domain.inference.FrameworkId
import com.aibox.domain.inference.FrameworkOverride
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

private val Context.frameworkDataStore by preferencesDataStore("framework_overrides")

/**
 * 框架级配置覆盖仓库。
 *
 * 持久化方式:DataStore 存一个 JSON 字符串,内含 Map<FrameworkId, FrameworkOverride>。
 *
 * 用法:
 * ```
 * val repo = FrameworkConfigRepository(context)
 * repo.overrideFor(FrameworkId.LITERT).collect { override ->
 *     // override 可能为 null
 * }
 * repo.setOverride(FrameworkId.LITERT, FrameworkOverride(
 *     frameworkId = FrameworkId.LITERT,
 *     contextLength = 2048,
 *     kvQuant = "f16"
 * ))
 * ```
 */
class FrameworkConfigRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Key 是 FrameworkId.name,String 序列化整个 map。 */
    private val OVERRIDES_JSON = stringPreferencesKey("overrides_json_v1")

    private val mapSerializer = MapSerializer(String.serializer(), FrameworkOverride.serializer())

    /** 全部框架覆盖 map。可能为空。 */
    val allOverrides: Flow<Map<FrameworkId, FrameworkOverride>> =
        context.frameworkDataStore.data.map { p ->
            val s = p[OVERRIDES_JSON] ?: ""
            if (s.isBlank()) emptyMap()
            else runCatching {
                val raw: Map<String, FrameworkOverride> = json.decodeFromString(mapSerializer, s)
                // 把 String key 转成 FrameworkId enum(无效 key 静默丢弃)
                raw.mapNotNull { (k, v) ->
                    runCatching { FrameworkId.valueOf(k) to v }.getOrNull()
                }.toMap()
            }.getOrDefault(emptyMap())
        }

    /** 单个框架的 override,可能为 null。 */
    fun overrideFor(framework: FrameworkId): Flow<FrameworkOverride?> =
        allOverrides.map { it[framework] }

    /** 设置或替换某框架的 override。null 表示删除该框架的 override。 */
    suspend fun setOverride(framework: FrameworkId, override: FrameworkOverride?) {
        context.frameworkDataStore.edit { p ->
            val current = (p[OVERRIDES_JSON] ?: "").let { s ->
                if (s.isBlank()) emptyMap<String, FrameworkOverride>()
                else runCatching { json.decodeFromString(mapSerializer, s) }.getOrDefault(emptyMap())
            }.toMutableMap()
            if (override == null) {
                current.remove(framework.name)
            } else {
                current[framework.name] = override
            }
            if (current.isEmpty()) {
                p.remove(OVERRIDES_JSON)
            } else {
                p[OVERRIDES_JSON] = json.encodeToString(mapSerializer, current)
            }
        }
    }

    /** 清除所有框架覆盖(回到全局默认)。 */
    suspend fun clearAll() {
        context.frameworkDataStore.edit { it.remove(OVERRIDES_JSON) }
    }
}
