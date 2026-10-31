package com.aibox.infra.plugin

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 插件状态持久化（基于 SharedPreferences）。
 *
 * 设计原则：
 * 1. **零异常约定**：所有方法 try-catch，失败返回 fallback 值（emptyList / false / null）。
 * 2. **每插件独立 namespace**：用 `<pluginPkg>.settings` 命名 SharedPreferences 文件，
 *    互不干扰；卸载时直接 deleteSharedPreferences。
 * 3. **设置值统一存 JSON**：因 [SettingField.defaultValue] 是 Any（Boolean / Number / String），
 *    用 JSON 包装保证类型不丢；读回时按 schema 还原。
 * 4. **已启用列表**：单独一份 SharedPreferences 文件 `aibox_plugins.xml` 存 enabled 列表，
 *    避免每次启动都要扫描全部已安装 APK 才能知道"用户启用过哪些"。
 *
 * 不持久化：插件运行期状态（如已加载模型 / 正在推理）——这些归 BackendManager 内存态。
 */
class PluginStore(private val context: Context) {

    /** 全局 SharedPreferences：存"已启用的插件包名列表"。 */
    private val enabledPrefs = context.getSharedPreferences(PREFS_ENABLED, Context.MODE_PRIVATE)

    /** 单个插件的 SharedPreferences：存该插件的设置值。 */
    private fun pluginPrefs(packageName: String): SharedPreferences =
        context.getSharedPreferences("$PREFS_PLUGIN_PREFIX$packageName", Context.MODE_PRIVATE)

    /** 设置值单键：JSON 字符串。 */
    private fun settingsKey(packageName: String): String = "settings.$packageName"

    // =================================================
    // 已启用列表
    // =================================================

    /** 列出所有已启用插件的包名。 */
    fun listEnabled(): List<String> {
        val raw = enabledPrefs.getString(KEY_ENABLED_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optString(i)?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** 标记插件为"已启用"（持久化）。 */
    fun setEnabled(packageName: String, enabled: Boolean) {
        val current = listEnabled().toMutableSet()
        if (enabled) current.add(packageName) else current.remove(packageName)
        val arr = JSONArray()
        current.forEach { arr.put(it) }
        enabledPrefs.edit().putString(KEY_ENABLED_LIST, arr.toString()).apply()
    }

    /** 查询某插件是否在"已启用"列表中（不验证当前是否真的能加载）。 */
    fun isEnabled(packageName: String): Boolean = listEnabled().contains(packageName)

    // =================================================
    // 单插件设置
    // =================================================

    /** 读取某插件的所有设置值（key → value，value 为 Boolean / Number / String）。 */
    fun getSettings(packageName: String): Map<String, Any> {
        val raw = pluginPrefs(packageName).getString(settingsKey(packageName), null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            buildMap {
                for (key in obj.keys()) {
                    val v: Any? = when (val any = obj.get(key)) {
                        is Boolean -> any
                        is Number -> any
                        is String -> any
                        else -> any.toString()
                    }
                    if (v != null) put(key, v)
                }
            }
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    /** 写入某插件的全部设置值（覆盖式）。 */
    fun putSettings(packageName: String, values: Map<String, Any>) {
        val obj = JSONObject()
        values.forEach { (k, v) ->
            when (v) {
                is Boolean -> obj.put(k, v)
                is Number -> obj.put(k, v)
                is String -> obj.put(k, v)
                else -> obj.put(k, v.toString())
            }
        }
        pluginPrefs(packageName).edit().putString(settingsKey(packageName), obj.toString()).apply()
    }

    /** 读取某插件的单个设置值；不存在返回 null。 */
    fun getSetting(packageName: String, key: String): Any? = getSettings(packageName)[key]

    /** 写入某插件的单个设置值（合并，不覆盖其它键）。 */
    fun putSetting(packageName: String, key: String, value: Any) {
        val current = getSettings(packageName).toMutableMap()
        current[key] = value
        putSettings(packageName, current)
    }

    /** 重置某插件的设置为空。 */
    fun clearSettings(packageName: String) {
        pluginPrefs(packageName).edit().clear().apply()
    }

    // =================================================
    // 清理（卸载时调用）
    // =================================================

    /** 清除某插件产生的所有数据（设置 + 从已启用列表移除）。 */
    fun clearAll(packageName: String) {
        // 1. 清空该插件的设置 SharedPreferences
        runCatching { pluginPrefs(packageName).edit().clear().apply() }
        // 2. 从已启用列表移除
        setEnabled(packageName, false)
    }

    companion object {
        private const val PREFS_ENABLED = "aibox_plugins"
        private const val PREFS_PLUGIN_PREFIX = "aibox_plugin_"
        private const val KEY_ENABLED_LIST = "enabled_list"
    }
}
