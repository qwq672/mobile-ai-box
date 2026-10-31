// IPluginLogCallback.aidl
package com.aibox.plugin;

/**
 * 插件日志回调接口：让插件把日志发回宿主 LogSystem。
 *
 * 插件 APK 自己不写日志文件，统一通过此接口把日志送到宿主
 * [com.aibox.util.log.LogSystem]；这样保证：
 * 1. 插件 / 宿主日志混合在同一文件，便于排查交互问题。
 * 2. 宿主的崩溃前紧急刷盘、ring buffer、会话独立日志等机制对插件也生效。
 *
 * - level: android.util.Log.* 的级别常量（VERBOSE=2, DEBUG=3, INFO=4, WARN=5, ERROR=6）。
 * - tag: 日志 tag，建议前缀 "aibox.plugin.<pluginName>"，便于 LogSystem.setTagLevel 过滤。
 * - message: 日志消息文本。
 *
 * 宿主在 enablePlugin 时调用 [IPluginInterface.setLogCallback] 注入本接口实现。
 */
interface IPluginLogCallback {
    void onLog(int level, String tag, String message);
}
