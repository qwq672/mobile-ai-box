package com.aibox.domain.search.providers

/**
 * 设置项注册中心。
 *
 * 把所有"用户能在 UI 上搜索到的设置项"以 metadata 形式集中声明，作为 [SettingSearchProvider]
 * 的数据源。预留两个扩展点：
 *
 * 1. **可热插拔**：[register] / [unregister] 在运行时增删（如插件设置项由 PluginManager 注册）。
 * 2. **结构化路径**：每项含 [path] 字段，形如 "推理框架 > 优化 > TTFT"，
 *    便于搜索结果显示层级 + 跳转时定位到具体 section。
 *
 * 设计原则：
 * - 这里**只存 metadata**，不存当前值；搜索不关心当前值（避免每次搜索都查 DataStore）。
 * - 每个 [SettingEntry] 有全局唯一 key，与 SettingsRepository / OptimizationSettingsRepository
 *   的实际 key 对齐，便于点击结果后跳转到 SettingsScreen 并定位到对应 section。
 *
 * @see SettingEntry
 */
object SettingRegistry {

    private val entries = mutableListOf<SettingEntry>()

    init {
        // ============== 推理框架（SettingsScreen 的"后端" + "性能"） ==============
        register(SettingEntry(
            key = "backend",
            title = "推理后端",
            description = "选择 CPU / Vulkan / NPU / OpenCL 后端",
            path = listOf("推理框架", "后端"),
            screen = "settings",
            sectionAnchor = "backend"
        ))
        register(SettingEntry(
            key = "n_gpu_layers",
            title = "GPU/NPU 层数",
            description = "向 GPU/NPU 卸载多少层；0=自动",
            path = listOf("推理框架", "后端"),
            screen = "settings",
            sectionAnchor = "backend"
        ))
        register(SettingEntry(
            key = "cpu_threads",
            title = "CPU 线程数",
            description = "推理使用的 CPU 线程数；0=按大核数",
            path = listOf("推理框架", "性能"),
            screen = "settings",
            sectionAnchor = "performance"
        ))
        register(SettingEntry(
            key = "physical_batch",
            title = "物理批处理",
            description = "physical batch；影响 prompt 评估速度与内存",
            path = listOf("推理框架", "性能"),
            screen = "settings",
            sectionAnchor = "performance"
        ))
        register(SettingEntry(
            key = "batch",
            title = "逻辑批处理",
            description = "batch；影响 prompt 评估速度与内存",
            path = listOf("推理框架", "性能"),
            screen = "settings",
            sectionAnchor = "performance"
        ))
        register(SettingEntry(
            key = "kv_quant",
            title = "KV Cache 量化",
            description = "F16 / Q8_0 / Q4_0；越低越省内存带宽，质量略降",
            path = listOf("推理框架", "性能"),
            screen = "settings",
            sectionAnchor = "performance"
        ))
        register(SettingEntry(
            key = "context_len",
            title = "上下文长度",
            description = "模型可保留的对话历史长度",
            path = listOf("推理框架", "性能"),
            screen = "settings",
            sectionAnchor = "performance"
        ))
        register(SettingEntry(
            key = "anti_rollback",
            title = "防回退（Anti-Rollback）",
            description = "开：保持 GPU/NPU 频率不被热降档；关：允许热感知软降",
            path = listOf("推理框架", "热管理"),
            screen = "settings",
            sectionAnchor = "thermal"
        ))

        // ============== 采样 ==============
        register(SettingEntry(
            key = "temperature",
            title = "Temperature",
            description = "采样温度；越高越发散，越低越保守",
            path = listOf("推理框架", "采样"),
            screen = "settings",
            sectionAnchor = "sampling"
        ))
        register(SettingEntry(
            key = "top_k",
            title = "Top-K",
            description = "采样时保留概率最高的 K 个 token",
            path = listOf("推理框架", "采样"),
            screen = "settings",
            sectionAnchor = "sampling"
        ))
        register(SettingEntry(
            key = "top_p",
            title = "Top-P",
            description = "采样时按累积概率 P 截断",
            path = listOf("推理框架", "采样"),
            screen = "settings",
            sectionAnchor = "sampling"
        ))
        register(SettingEntry(
            key = "repeat_penalty",
            title = "Repeat Penalty",
            description = "重复惩罚；抑制模型复读",
            path = listOf("推理框架", "采样"),
            screen = "settings",
            sectionAnchor = "sampling"
        ))

        // ============== 启动行为 + 对话 ==============
        // G-settings-fix：删除 auto_load 条目（与 opt.preloadModel 重复，autoLoadModel 已合并到 OptimizationSettings）
        // 用户搜「模型预加载」会命中 opt.preloadModel 条目（screen=optimization）
        register(SettingEntry(
            key = "system_prompt",
            title = "系统提示词",
            description = "每次会话前置的 system prompt",
            path = listOf("对话", "通用"),
            screen = "settings",
            sectionAnchor = "chat"
        ))
        register(SettingEntry(
            key = "thinking_mode",
            title = "思考模式",
            description = "Qwen3 /think 标志；让模型先思考再回答",
            path = listOf("对话", "通用"),
            screen = "settings",
            sectionAnchor = "chat"
        ))

        // ============== 外观 / 日志 ==============
        register(SettingEntry(
            key = "dynamic_color",
            title = "Material You 动态取色",
            description = "跟随系统壁纸自动配色（Android 12+）",
            path = listOf("外观", "主题"),
            screen = "settings",
            sectionAnchor = "appearance"
        ))
        register(SettingEntry(
            key = "dark_theme",
            title = "主题模式",
            description = "跟随系统 / 浅色 / 深色",
            path = listOf("外观", "主题"),
            screen = "settings",
            sectionAnchor = "appearance"
        ))
        register(SettingEntry(
            key = "logging_enabled",
            title = "启用文件日志",
            description = "写入外部存储，便于排查问题",
            path = listOf("日志", "通用"),
            screen = "settings",
            sectionAnchor = "log"
        ))
        register(SettingEntry(
            key = "dl_source",
            title = "下载源",
            description = "HfMirror / HuggingFace / ModelScope",
            path = listOf("下载", "通用"),
            screen = "settings",
            sectionAnchor = "download"
        ))
        register(SettingEntry(
            key = "lang",
            title = "语言",
            description = "中文 / English",
            path = listOf("外观", "国际化"),
            screen = "settings",
            sectionAnchor = "appearance"
        ))

        // ============== 优化（OptimizationSettingsScreen） ==============
        register(SettingEntry(
            key = "opt.preset",
            title = "优化预设",
            description = "一键切换优化模式：默认 / 性能优先 / 省电模式 / 自定义",
            path = listOf("推理框架", "优化", "预置"),
            screen = "optimization",
            sectionAnchor = "preset"
        ))
        register(SettingEntry(
            key = "opt.preloadModel",
            title = "模型预加载",
            description = "App 启动时异步加载常用模型，避开冷启动延迟",
            path = listOf("推理框架", "优化", "TTFT"),
            screen = "optimization",
            sectionAnchor = "ttft"
        ))
        register(SettingEntry(
            key = "opt.warmupAfterLoad",
            title = "加载后预热",
            description = "加载完成后跑 1 token 假推理初始化内部状态",
            path = listOf("推理框架", "优化", "TTFT"),
            screen = "optimization",
            sectionAnchor = "ttft"
        ))
        register(SettingEntry(
            key = "opt.preallocateKV",
            title = "KV cache 预分配",
            description = "加载时按 contextLength 一次性分配 KV cache",
            path = listOf("推理框架", "优化", "TTFT"),
            screen = "optimization",
            sectionAnchor = "ttft"
        ))
        register(SettingEntry(
            key = "opt.prebuildSampler",
            title = "采样器预构造",
            description = "加载时即构建采样器对象",
            path = listOf("推理框架", "优化", "TTFT"),
            screen = "optimization",
            sectionAnchor = "ttft"
        ))
        register(SettingEntry(
            key = "opt.streamFirstTokenImmediately",
            title = "首 token 立即返回",
            description = "流式输出首 token 一就绪就 push",
            path = listOf("推理框架", "优化", "TTFT"),
            screen = "optimization",
            sectionAnchor = "ttft"
        ))
        register(SettingEntry(
            key = "opt.forceFlashAttention",
            title = "强制 Flash Attention",
            description = "Flash Attention 2 / 3；后端不支持则静默忽略",
            path = listOf("推理框架", "优化", "TG"),
            screen = "optimization",
            sectionAnchor = "tg"
        ))
        register(SettingEntry(
            key = "opt.aggressiveGraphOpt",
            title = "激进算子融合",
            description = "启用激进的算子融合 / 常量折叠",
            path = listOf("推理框架", "优化", "TG"),
            screen = "optimization",
            sectionAnchor = "tg"
        ))
        register(SettingEntry(
            key = "opt.continuousKVLayout",
            title = "KV cache 连续布局",
            description = "使用连续 KV 内存布局，提速但占内存更整块",
            path = listOf("推理框架", "优化", "TG"),
            screen = "optimization",
            sectionAnchor = "tg"
        ))
        register(SettingEntry(
            key = "opt.reuseSampler",
            title = "采样器复用",
            description = "同会话内复用 sampler 实例",
            path = listOf("推理框架", "优化", "TG"),
            screen = "optimization",
            sectionAnchor = "tg"
        ))
        register(SettingEntry(
            key = "opt.zeroCopyStream",
            title = "流式输出 zero-copy",
            description = "流式输出走零拷贝路径",
            path = listOf("推理框架", "优化", "TG"),
            screen = "optimization",
            sectionAnchor = "tg"
        ))
        register(SettingEntry(
            key = "opt.enableGraphCache",
            title = "推理图缓存",
            description = "缓存编译后的计算图，加速同模型二次加载",
            path = listOf("推理框架", "优化", "TG"),
            screen = "optimization",
            sectionAnchor = "tg"
        ))
        register(SettingEntry(
            key = "opt.thermalAwareThrottle",
            title = "散热感知降频",
            description = "温度过高时主动通知后端降低 batch / 线程数",
            path = listOf("推理框架", "优化", "能耗"),
            screen = "optimization",
            sectionAnchor = "energy"
        ))
        register(SettingEntry(
            key = "opt.idleUnloadTimeout",
            title = "空闲超时卸载",
            description = "App 长时间未推理时自动卸载模型省电",
            path = listOf("推理框架", "优化", "能耗"),
            screen = "optimization",
            sectionAnchor = "energy"
        ))
        register(SettingEntry(
            key = "opt.lowPowerOnBattery",
            title = "电池模式低功耗",
            description = "电池供电时进入低功耗档",
            path = listOf("推理框架", "优化", "能耗"),
            screen = "optimization",
            sectionAnchor = "energy"
        ))
    }

    /** 注册一个设置项；key 重复时覆盖。 */
    @Synchronized
    fun register(entry: SettingEntry) {
        entries.removeAll { it.key == entry.key }
        entries.add(entry)
    }

    /** 注销一个设置项（按 key）；不存在时幂等返回。 */
    @Synchronized
    fun unregister(key: String) {
        entries.removeAll { it.key == key }
    }

    /** 取当前所有注册的设置项（快照）。 */
    @Synchronized
    fun all(): List<SettingEntry> = entries.toList()
}

/**
 * 单个设置项的 metadata（不含当前值）。
 *
 * @property key 全局唯一 ID；与 SettingsRepository / OptimizationSettingsRepository 的实际 key 对齐
 * @property title 用户可见标题
 * @property description 描述（用于副标题 + 关键词扩展）
 * @property path 层级路径，如 ["推理框架", "优化", "TTFT"]；UI 显示为 "推理框架 > 优化 > TTFT"
 * @property screen 跳转目标页面：当前支持 "settings" / "optimization"
 * @property sectionAnchor 跳转锚点；用于未来实现"滚动到对应 section"
 */
data class SettingEntry(
    val key: String,
    val title: String,
    val description: String,
    val path: List<String>,
    val screen: String,
    val sectionAnchor: String
) {
    /** 把 path 列表拼接成 UI 显示字符串。 */
    fun pathDisplay(): String = path.joinToString(" > ")
}
