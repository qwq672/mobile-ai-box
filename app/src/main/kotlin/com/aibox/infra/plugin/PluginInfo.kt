package com.aibox.infra.plugin

/**
 * 插件基本信息（宿主侧 domain 对象）。
 *
 * 与 AIDL parcelable `com.aibox.plugin.PluginInfo` 字段一一对应；本类用于宿主进程内
 * 不需要跨进程的场景，避免每次访问都解 parcel。跨进程时通过 [ParcelableConverters]
 * 在本类与 AIDL parcelable 之间互转。
 *
 * @property packageName 插件 APK 包名，全局唯一标识一个插件（如 `com.aibox.plugin.litert`）。
 * @property descriptorClass 插件 PluginService 在 manifest 中声明的、提供 [com.aibox.plugin.IPluginDescriptor]
 *   的 Service 类全限定名；用于宿主 bindService 时构造 ComponentName。
 * @property version 插件版本字符串，如 "0.1.0"。
 * @property displayName 用户可见的显示名，如 "LiteRT-LM"。
 * @property sizeBytes 插件 APK 安装后占用字节数；用于 UI 显示体积提示。
 */
data class PluginInfo(
    val packageName: String,
    val descriptorClass: String,
    val version: String,
    val displayName: String,
    val sizeBytes: Long
)
