package com.easychat

/**
 * 模块描述符(在 Service meta-data 中以字符串 "com.easychat.EasyChatModuleDescriptor" 引用)。
 *
 * 让宿主在 PackageManager 扫描阶段识别这是 Easy Chat 模块,
 * 功能是聊天(type="chat"),模型是 Qwen2.5-0.5B Q4_K_M(architecture="qwen2")。
 * 宿主读取 module.json 获取详细配置(context_length / kv_cache_type / 设备分层等)。
 *
 * 字段设计原则:
 * - 与 [com.aibox.plugin.PluginInfo] / IPluginDescriptor 字段名一致(便于宿主统一视图),
 * - 但本类不实现 IPluginDescriptor 接口:那是推理框架插件的契约,Easy Chat 不需要。
 * - 仅作为元数据"哨兵类":宿主通过 Class.forName 加载本类即可确认模块存在,
 *   实例字段不参与运行时逻辑(模块所有运行时数据都从 module.json 读)。
 *
 * 之所以不写成 object 而是 data class:
 * - data class 自带 toString / equals / hashCode,宿主反射构造实例即可,
 * - object 反射访问 INSTANCE 字段稍麻烦,本工程希望让宿主代码尽量简单。
 *
 * @property name 模块唯一标识(短名,用于宿主内部映射)。
 * @property displayName 给用户看的名字。
 * @property version 语义化版本号,宿主据此决定是否触发"模块有更新"提示。
 * @property description 一句话简介。
 * @property type 模块类型,目前固定 "chat";将来可能有 "agent" / "asr" / "tts" 等。
 * @property modelArchitecture 模型架构(qwen2 / llama / qwen3-moe 等),
 *                            宿主据此校验内置 llama.cpp 是否支持。
 */
data class EasyChatModuleDescriptor(
    val name: String = "easy-chat",
    val displayName: String = "Easy Chat",
    val version: String = "1.0.0",
    val description: String = "零配置即用的轻量聊天模块",
    val type: String = "chat",
    val modelArchitecture: String = "qwen2"
)
