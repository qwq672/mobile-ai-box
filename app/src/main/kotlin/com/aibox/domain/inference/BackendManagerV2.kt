package com.aibox.domain.inference

/**
 * 多后端路由器（v2）。
 *
 * - 接收 [InferenceRequest]
 * - 根据 `taskType + 模型格式 + 用户偏好` 选择合适的 [InferenceBackendV2]
 * - 失败可自动 fallback（如 NPU 不可用 → CPU）
 *
 * 与 V1 [InferenceEngine] 的区别：
 * - V1 [InferenceEngine] 把"后端选择 + 配置调优 + 推理执行"耦合在一个类里，且只支持 LLM 任务。
 * - V2 [BackendManagerV2] **只做路由**，不做配置调优（threads / KV 等由 [InferenceConfigV2] 或
 *   自动调优模块决定），也不直接执行推理（执行由调用方拿 [InferenceBackendV2] 后自己调 infer / streamInfer）。
 *
 * 路由策略（当前版本，可后续扩展）：
 * 1. 找出支持 [InferenceRequest.taskType] 的所有后端。
 * 2. 在候选中筛选能加载指定 `modelFormat` 的（如 "gguf" / "tflite"）。
 * 3. 取第一个匹配的后端（未来可加用户偏好排序：性能优先 / 省电优先 / 手动指定）。
 * 4. 若 2 无匹配，回退到 1 的第一个候选（即"能跑此任务但模型格式不直接支持"的兜底，由该后端自己做转码）。
 *
 * 线程安全：所有 `register*` / `unregister*` 调用应在主线程集中完成，运行期不在推理途中增删后端。
 * 本类内部用 `mutableMapOf`，未加锁；若未来需要支持热插拔，再加 ConcurrentHashMap + 读写锁。
 */
class BackendManagerV2 {

    /** 已注册的后端实例，按 [InferenceBackendV2.getName] 索引。 */
    private val backends = mutableMapOf<String, InferenceBackendV2>()

    /** 已注册的后端描述符，按 [BackendDescriptor.getName] 索引（与 backends 同键）。 */
    private val descriptors = mutableMapOf<String, BackendDescriptor>()

    /**
     * 已加载模型注册表（P3 新增）。
     *
     * 按 backend name 索引一组 [LoadedModel] 句柄；调用方在 [InferenceBackendV2.loadModel] 成功后
     * 调 [trackLoaded] 注册，[InferenceBackendV2.unload] 后调 [untrackLoaded] 取消注册。
     *
     * 用途：
     * - [com.aibox.domain.optimization.EnergyOptimizer] 的 idle watchdog 遍历本表卸载空闲模型。
     * - [com.aibox.AIBoxApp] 的崩溃报告 `activeBackendsProvider` 可结合本表显示"哪些后端正在跑哪些模型"。
     *
     * 设计：每个 backend 可有多个 LoadedModel（理论上），但当前调用方一次只 load 一个，因此用 [MutableSet] 简化并发。
     * 内部用 [mutableMapOf] + [mutableSetOf]，未加锁；调用方应在主线程集中 track/untrack。
     */
    private val loadedModels: MutableMap<String, MutableSet<LoadedModel>> = mutableMapOf()

    /**
     * 注册一个后端 + 描述符。
     *
     * 后端与描述符必须 `getName()` 一致（即同一身份），否则抛 [IllegalArgumentException]。
     * 重复注册同名后端会覆盖旧值（便于热更新）。
     */
    fun registerBackend(backend: InferenceBackendV2, descriptor: BackendDescriptor) {
        require(backend.getName() == descriptor.getName()) {
            "Backend name '${backend.getName()}' != descriptor name '${descriptor.getName()}'"
        }
        backends[backend.getName()] = backend
        descriptors[descriptor.getName()] = descriptor
    }

    /** 注销指定名称的后端及其描述符。不存在则 no-op。 */
    fun unregisterBackend(name: String) {
        backends.remove(name)
        descriptors.remove(name)
    }

    /** 列出所有已注册后端的描述符，供 UI 渲染"后端列表 / 设置页"。 */
    fun listBackends(): List<BackendDescriptor> = descriptors.values.toList()

    /**
     * 便捷方法：列出已注册后端的名称（[InferenceBackendV2.getName]）。
     *
     * 主要用于崩溃报告、UI 状态显示等"只需要名称"的场景，避免每次都拿描述符再 map。
     * 与 [com.aibox.infra.plugin.PluginManager.listActive] 对称（同语义）。
     */
    fun listActive(): List<String> = listBackends().map { it.getName() }

    /** 按名称取后端实例；未注册返回 null。 */
    fun getBackend(name: String): InferenceBackendV2? = backends[name]

    /** 按名称取描述符；未注册返回 null。 */
    fun getDescriptor(name: String): BackendDescriptor? = descriptors[name]

    /**
     * 根据请求路由到合适的后端。
     *
     * @param request 推理请求。
     * @param modelFormat 模型文件格式（如 "gguf" / "tflite"），用于筛选能加载此后端。
     * @return 匹配的后端；无任何后端支持该 taskType 时返回 null。
     */
    fun routeBackend(request: InferenceRequest, modelFormat: String): InferenceBackendV2? {
        // 1. 找出支持该 taskType 的后端
        val taskCandidates = backends.values.filter { backend ->
            backend.describeCapabilities().supportedTasks.contains(request.taskType)
        }
        // 2. 在候选中筛选能加载该模型格式的
        val compatible = taskCandidates.filter { backend ->
            backend.describeCapabilities().supportedModelFormats.contains(modelFormat)
        }
        // 3. 优先用格式匹配的；若无，则回退到任何能跑此任务的（让该后端自己处理格式兼容，如转码）
        return compatible.firstOrNull() ?: taskCandidates.firstOrNull()
    }

    // ===================================================
    // P3：已加载模型注册表（供 EnergyOptimizer idle watchdog 用）
    // ===================================================

    /**
     * 注册一个已加载模型到本后端的 loaded 表。
     *
     * 调用方应在 [InferenceBackendV2.loadModel] 成功后立即调本方法，把 [LoadedModel] 句柄登记，
     * 便于 [com.aibox.domain.optimization.EnergyOptimizer] 的 idle watchdog 在空闲超时时统一 unload。
     *
     * 重复 track 同一 [LoadedModel] 实例 no-op（基于 [LoadedModel] 的 data class equals）。
     */
    fun trackLoaded(backendName: String, model: LoadedModel) {
        val set = loadedModels.getOrPut(backendName) { mutableSetOf() }
        set.add(model)
    }

    /**
     * 取消注册一个已加载模型。
     *
     * 调用方应在 [InferenceBackendV2.unload] 后调本方法，把 [LoadedModel] 从 loaded 表移除，
     * 避免 idle watchdog 重复 unload 已释放的句柄。
     *
     * 不存在则 no-op。
     */
    fun untrackLoaded(backendName: String, model: LoadedModel) {
        val set = loadedModels[backendName] ?: return
        set.remove(model)
        if (set.isEmpty()) loadedModels.remove(backendName)
    }

    /**
     * 列出所有已 tracked 的 loaded 模型，扁平为 `List<Pair<backendName, LoadedModel>>`。
     *
     * 供 [com.aibox.domain.optimization.EnergyOptimizer] idle watchdog 遍历卸载。
     */
    fun listLoadedModels(): List<Pair<String, LoadedModel>> {
        val result = mutableListOf<Pair<String, LoadedModel>>()
        loadedModels.forEach { (name, set) ->
            set.forEach { model -> result.add(name to model) }
        }
        return result
    }

    /**
     * 把指定 [OptimizationFlags] 广播到所有已注册后端（P3 新增）。
     *
     * 用途：用户在设置页改了 TG 优化开关 → [com.aibox.domain.optimization.TgOptimizer.applyToAll] 调本方法
     * 让所有后端立即应用新 flags（无需 reload 模型）。
     *
     * 不抛异常：任一后端 applyOptimizations 失败仅 LogSystem.warn（在调用方处理），不影响其他后端。
     */
    fun applyOptimizationsToAll(flags: OptimizationFlags) {
        backends.values.forEach { backend ->
            try {
                backend.applyOptimizations(flags)
            } catch (t: Throwable) {
                // 静默忽略；调用方（TgOptimizer）已记录日志
            }
        }
    }
}
