package com.aibox.translate

/**
 * 模块描述符(在 Service meta-data 中以字符串
 * "com.aibox.translate.TranslateModuleDescriptor" 引用)。
 *
 * 让宿主在 PackageManager 扫描阶段识别这是翻译模块,
 * 功能是翻译(type="translate"),不打包任何模型,只协调 ASR / MT / TTS 三步工作流。
 * 宿主读取 module.json 获取详细配置(workflow / 支持语言列表 / prompt 模板等)。
 *
 * 字段设计原则:
 * - 与 [com.aibox.plugin.PluginInfo] / IPluginDescriptor 字段名一致(便于宿主统一视图),
 * - 但本类不实现 IPluginDescriptor 接口:那是推理框架插件的契约,翻译模块不需要。
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
 * @property type 模块类型,固定 "translate";与聊天模块("chat")区分。
 */
data class TranslateModuleDescriptor(
    val name: String = "translate",
    val displayName: String = "翻译",
    val version: String = "1.0.0",
    val type: String = "translate"
)
