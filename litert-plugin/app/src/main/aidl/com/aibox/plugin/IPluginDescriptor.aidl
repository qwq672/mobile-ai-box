// IPluginDescriptor.aidl
package com.aibox.plugin;

import com.aibox.plugin.PluginInfo;
import com.aibox.plugin.SettingFieldEntry;
import com.aibox.plugin.BackendCapabilitiesParcel;

/**
 * 插件描述符接口：插件 APK 暴露给宿主的元信息。
 *
 * 宿主通过此接口查询插件的：
 * - 基本信息（PluginInfo：包名 / 版本 / 显示名 / 大小）
 * - 能力声明（BackendCapabilitiesParcel：支持的任务 / 模态 / 模型格式）
 * - 设置 schema（SettingFieldEntry 列表，用于动态生成设置 UI）
 * - 默认设置值（Map<String, String>）
 *
 * 对应 Kotlin 侧 [com.aibox.domain.inference.BackendDescriptor]，
 * 由 [com.aibox.infra.plugin.PluginDescriptorAdapter] 包装为 V2 接口。
 *
 * 注：本接口只读，宿主不应通过此接口修改插件状态。
 */
interface IPluginDescriptor {
    PluginInfo getPluginInfo();
    String getName();
    String getDisplayName();
    String getVersion();
    BackendCapabilitiesParcel describeCapabilities();
    List<SettingFieldEntry> getSettingsSchema();
    Map<String, String> getDefaultSettings();
}
