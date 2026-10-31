package com.aibox.domain.shortcut

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 快捷方式持久化仓库（E 主页 Hybrid 设计）。
 *
 * 持久化策略：
 * - 用独立 DataStore 文件名空间 `shortcut_store`，避免污染 `settings` / `tool_settings` 等其它 DataStore。
 * - 整列表序列化为一个 JSON 字符串存到单一 [shortcutsKey]；列表规模预期很小（< 50 个），
 *   用单一 key 读写效率足够，避免逐条 stringSet 操作时反序列化整集合的额外开销。
 * - 反序列化失败时回退到空列表（不抛异常），并写入 error 日志。
 *
 * API：
 * - [shortcuts]：Flow<List<Shortcut>>，UI 订阅用。
 * - [create]：分配新 id 后插入；返回 id。
 * - [update]：按 id 找到后用 transform 修改并保存。
 * - [delete]：按 id 删除。
 * - [get]：按 id 一次性查询。
 * - [touchUsage]：更新 lastUsedAt（点击时调用，用于"最近使用"排序）。
 * - [listByTargetType]：按 targetType 过滤；首页"翻译"tile 计数等场景用。
 *
 * 并发安全：DataStore 内部串行化 edit；外部 Flow 自动重发；零异常约定（失败仅记日志）。
 */
private val Context.shortcutDataStore by preferencesDataStore("shortcut_store")

class ShortcutRepository(private val context: Context) {

    private val tag = "aibox.shortcut.repo"
    private val shortcutsKey = stringPreferencesKey("shortcuts_json")

    /**
     * Json 实例配置：忽略未知字段（向前兼容），允许结构化 map（[Shortcut.presetConfig.customPayload]）。
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    /** Observable 流：当前所有快捷方式（按 lastUsedAt 降序，未使用排最后）。 */
    val shortcuts: Flow<List<Shortcut>> = context.shortcutDataStore.data.map { prefs ->
        val raw = prefs[shortcutsKey]
        if (raw.isNullOrBlank()) emptyList()
        else decodeList(raw)
    }

    /** 一次性快照：当前所有快捷方式。 */
    suspend fun listAll(): List<Shortcut> = shortcuts.first()

    /**
     * 新建快捷方式。
     *
     * @param shortcut 模板；id 字段会被覆盖为新生成的 UUID，createdAt 覆盖为当前时间。
     * @return 新生成的 id
     */
    suspend fun create(shortcut: Shortcut): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val toSave = shortcut.copy(id = id, createdAt = now)
        context.shortcutDataStore.edit { prefs ->
            val current = decodeList(prefs[shortcutsKey])
            prefs[shortcutsKey] = encodeList(current + toSave)
        }
        LogSystem.info(tag, "Created shortcut id=$id title=${shortcut.title} type=${shortcut.targetType}")
        return id
    }

    /**
     * 更新快捷方式。
     *
     * @param id 目标 id
     * @param transform 修改函数（输入旧值，返回新值）；找不到时 no-op
     */
    suspend fun update(id: String, transform: (Shortcut) -> Shortcut) {
        context.shortcutDataStore.edit { prefs ->
            val current = decodeList(prefs[shortcutsKey])
            val idx = current.indexOfFirst { it.id == id }
            if (idx < 0) {
                LogSystem.warn(tag, "update: shortcut id=$id not found")
                return@edit
            }
            val updated = transform(current[idx])
            prefs[shortcutsKey] = encodeList(current.toMutableList().apply { set(idx, updated) })
        }
    }

    /** 删除快捷方式；id 不存在时幂等成功。 */
    suspend fun delete(id: String) {
        context.shortcutDataStore.edit { prefs ->
            val current = decodeList(prefs[shortcutsKey])
            val next = current.filterNot { it.id == id }
            if (next.size != current.size) {
                prefs[shortcutsKey] = encodeList(next)
                LogSystem.info(tag, "Deleted shortcut id=$id")
            }
        }
    }

    /** 按 id 查询；找不到返回 null。 */
    suspend fun get(id: String): Shortcut? = shortcuts.first().firstOrNull { it.id == id }

    /** 更新 lastUsedAt 为当前时间；用于"最近使用"排序。 */
    suspend fun touchUsage(id: String) {
        update(id) { it.copy(lastUsedAt = System.currentTimeMillis()) }
    }

    /** 按 targetType 过滤。 */
    suspend fun listByTargetType(type: ShortcutTargetType): List<Shortcut> =
        shortcuts.first().filter { it.targetType == type }

    // ----------------- 序列化辅助 -----------------

    private fun encodeList(list: List<Shortcut>): String = try {
        json.encodeToString(ListSerializer(Shortcut.serializer()), list)
    } catch (t: Throwable) {
        LogSystem.error(tag, "encodeList failed: ${t.message}", t)
        "[]"
    }

    private fun decodeList(raw: String?): List<Shortcut> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(ListSerializer(Shortcut.serializer()), raw)
        } catch (t: Throwable) {
            LogSystem.error(tag, "decodeList failed (corrupted JSON?): ${t.message}", t)
            emptyList()
        }
    }
}
