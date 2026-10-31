// PluginInfo.aidl
package com.aibox.plugin;

/**
 * 插件基本信息（AIDL parcelable）。
 *
 * 跨进程传递的"插件身份"，对应 Kotlin 侧 [com.aibox.infra.plugin.PluginInfo]。
 */
parcelable PluginInfo {
    String packageName;
    String descriptorClass;
    String version;
    String displayName;
    long sizeBytes;
}
