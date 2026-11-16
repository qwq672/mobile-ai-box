package com.aibox.infra.module.loader

import com.aibox.infra.module.manifest.ModuleDependencyDto
import com.aibox.infra.module.manifest.ModuleManifestDto
import com.aibox.infra.module.manifest.SemVer
import com.aibox.infra.module.manifest.VersionRanges

/**
 * 模块依赖解析结果。
 *
 * @property groups 加载分组，按“被依赖者在前”排序；同一组表示循环依赖，
 *                  必须一起加载（不能按先后拆开）。
 * @property excluded 被排除的模块及原因（缺依赖、版本不满足、冲突等）。
 * @property warnings 不阻断加载、但需要让用户知道的提示。
 */
data class ModuleResolutionPlan(
    val groups: List<ModuleLoadGroup> = emptyList(),
    val excluded: List<ExcludedModule> = emptyList(),
    val warnings: List<String> = emptyList()
)

data class ModuleLoadGroup(
    val moduleIds: List<String>,
    /** true = 这组模块互相依赖（D↔E），需要作为一个整体加载。 */
    val cyclic: Boolean
)

data class ExcludedModule(val moduleId: String, val reason: String)

/**
 * 模块依赖图解析器（在旧版只做拓扑排序的基础上扩展）。
 *
 * 目标场景：
 * - A 依赖 B 的 so + 宿主 so；C 依赖 A、B 但不需要宿主 so → 通过 `depends_on_libs.provider` 表达
 * - D ↔ E 互相依赖 → 用 SCC 合成一个“循环加载组”，而不是整体判定失败
 * - F 依赖宿主 so；G 与 F 不兼容 → `conflicts_with` + `priority`
 * - H 只兼容 I 1.0、J 只兼容 I 1.1 → `version_range`（`>=1.0 <1.1` / `^1.1`）
 *
 * 说明：Android 同一时刻只能安装同一个包名的一个版本，所以“同时装 I 1.0 和 1.1”
 * 不在解析器里做多版本选择；解析器做的是“用已安装版本去校验每个模块的约束”，
 * 不满足的模块被排除并给出原因。
 */
object ModuleGraphResolver {

    /**
     * @param manifests 已安装模块的 manifest（同一 module_id 可有多份，取最高版本）
     * @param preferredModules 用户手动优先保留的模块 id（冲突时优先）
     */
    fun resolve(
        manifests: List<ModuleManifestDto>,
        preferredModules: Set<String> = emptySet()
    ): ModuleResolutionPlan {
        if (manifests.isEmpty()) return ModuleResolutionPlan()

        val warnings = mutableListOf<String>()
        val excluded = linkedMapOf<String, String>()

        // ---- 1. module_id 去重（保留最高版本） ----
        val byId = linkedMapOf<String, ModuleManifestDto>()
        for (m in manifests) {
            val prev = byId[m.moduleId]
            if (prev == null) {
                byId[m.moduleId] = m
            } else {
                val chosen = if (versionOf(m) > versionOf(prev)) m else prev
                byId[m.moduleId] = chosen
                warnings += "module_id '${m.moduleId}' 出现多个版本，使用 ${chosen.version}"
            }
        }

        val alive = byId.keys.toMutableSet()

        fun exclude(id: String, reason: String) {
            if (alive.remove(id)) excluded[id] = reason
        }

        // ---- 2. 冲突解析（双向视为冲突，按 preferred / priority / version 选保留者） ----
        for (m in byId.values) {
            if (m.moduleId !in alive) continue
            for (c in m.conflictsWith) {
                val other = byId[c.moduleId] ?: continue
                if (other.moduleId !in alive) continue
                if (!VersionRanges.satisfies(other.version, c.versionRange)) continue

                val winner = chooseWinner(m, other, preferredModules)
                val loser = if (winner.moduleId == m.moduleId) other else m
                if (winner.moduleId == m.moduleId && other.priority == m.priority &&
                    preferredModules.none { it == m.moduleId || it == other.moduleId }
                ) {
                    warnings += "${m.moduleId} 与 ${other.moduleId} 冲突且优先级相同，默认保留 ${winner.moduleId}"
                }
                exclude(
                    loser.moduleId,
                    "与 ${winner.moduleId} 冲突" + c.reason.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
                )
            }
        }

        // ---- 3. 依赖校验（迭代到稳定，处理传递排除） ----
        var changed = true
        while (changed) {
            changed = false
            for (m in byId.values) {
                if (m.moduleId !in alive) continue
                var bad: String? = null
                for (dep in m.dependsOnModules) {
                    val depManifest = byId[dep.moduleId]
                    bad = when {
                        depManifest == null -> "缺少依赖模块 ${dep.moduleId}"
                        dep.moduleId !in alive -> "依赖 ${dep.moduleId} 已被排除"
                        !dependencyVersionOk(depManifest, dep) ->
                            "依赖 ${dep.moduleId}=${depManifest.version} 不满足 ${describeRange(dep)}"
                        else -> null
                    }
                    if (bad != null) break
                }
                if (bad != null) {
                    exclude(m.moduleId, bad)
                    if (m.moduleId in excluded) changed = true
                }
            }
        }

        // ---- 4. native 库提供方校验 ----
        for (m in byId.values) {
            if (m.moduleId !in alive) continue
            for (lib in m.dependsOnLibs) {
                val provider = lib.provider
                if (provider.isNullOrBlank()) {
                    val providerModule = byId.values.firstOrNull {
                        it.moduleId in alive &&
                            it.nativeLibsBundled.any { b -> b.soname == lib.soname }
                    }
                    if (providerModule == null) {
                        // 没有模块声明自带 → 视为宿主 / 系统库。常见于 libc++_shared、liblog 等。
                        warnings += "${m.moduleId} 的 ${lib.soname} 未声明 provider，按宿主/系统库处理"
                    }
                } else if (provider != "host") {
                    val pm = byId[provider]
                    when {
                        pm == null || provider !in alive ->
                            exclude(m.moduleId, "native 库 ${lib.soname} 由 ${provider} 提供，但该模块不可用")
                        pm.nativeLibsBundled.none { it.soname == lib.soname } ->
                            warnings += "${provider} 未在 manifest 声明自带 ${lib.soname}（可能依赖运行时扫描）"
                    }
                }
            }
        }

        // ---- 5. 循环依赖：Tarjan SCC ----
        val aliveModules = byId.values.filter { it.moduleId in alive }
        val components = tarjan(aliveModules, alive)
        val order = orderComponents(components, aliveModules)

        val groups = order.map { comp ->
            ModuleLoadGroup(
                moduleIds = comp,
                cyclic = comp.size > 1 || hasSelfDependency(comp.firstOrNull(), byId)
            )
        }

        return ModuleResolutionPlan(
            groups = groups,
            excluded = excluded.map { (id, reason) -> ExcludedModule(id, reason) },
            warnings = warnings.distinct()
        )
    }

    // ---------------- 内部 ----------------

    private fun versionOf(m: ModuleManifestDto): SemVer =
        SemVer.parse(m.version) ?: SemVer(0, 0, 0)

    private fun chooseWinner(
        a: ModuleManifestDto,
        b: ModuleManifestDto,
        preferred: Set<String>
    ): ModuleManifestDto {
        if (a.moduleId in preferred && b.moduleId !in preferred) return a
        if (b.moduleId in preferred && a.moduleId !in preferred) return b
        if (a.priority != b.priority) return if (a.priority > b.priority) a else b
        val av = versionOf(a)
        val bv = versionOf(b)
        return if (av >= bv) a else b
    }

    private fun dependencyVersionOk(depManifest: ModuleManifestDto, dep: ModuleDependencyDto): Boolean {
        if (!dep.versionRange.isNullOrBlank()) {
            return VersionRanges.satisfies(depManifest.version, dep.versionRange)
        }
        val v = SemVer.parse(depManifest.version) ?: return false
        val min = SemVer.parse(dep.minVersion) ?: SemVer(0, 0, 0)
        if (v < min) return false
        val max = dep.maxVersion?.let { SemVer.parse(it) }
        if (max != null && v > max) return false
        return true
    }

    private fun describeRange(dep: ModuleDependencyDto): String =
        dep.versionRange?.takeIf { it.isNotBlank() }
            ?: buildString {
                append(">=${dep.minVersion}")
                dep.maxVersion?.let { append(" <=").append(it) }
            }

    private fun hasSelfDependency(id: String?, byId: Map<String, ModuleManifestDto>): Boolean {
        if (id == null) return false
        val m = byId[id] ?: return false
        return m.dependsOnModules.any { it.moduleId == id }
    }

    /** Tarjan 强连通分量；只在 alive 集合内连边。 */
    private fun tarjan(
        modules: List<ModuleManifestDto>,
        alive: Set<String>
    ): List<List<String>> {
        val index = mutableMapOf<String, Int>()
        val low = mutableMapOf<String, Int>()
        val onStack = mutableSetOf<String>()
        val stack = ArrayDeque<String>()
        val result = mutableListOf<List<String>>()
        var counter = 0

        fun strongConnect(v: String) {
            index[v] = counter
            low[v] = counter
            counter++
            stack.addLast(v)
            onStack.add(v)

            val manifest = modules.firstOrNull { it.moduleId == v }
            for (dep in manifest?.dependsOnModules.orEmpty()) {
                val w = dep.moduleId
                if (w !in alive) continue
                if (w !in index) {
                    strongConnect(w)
                    low[v] = minOf(low.getValue(v), low.getValue(w))
                } else if (w in onStack) {
                    low[v] = minOf(low.getValue(v), index.getValue(w))
                }
            }

            if (low.getValue(v) == index.getValue(v)) {
                val comp = mutableListOf<String>()
                while (true) {
                    val w = stack.removeLast()
                    onStack.remove(w)
                    comp.add(w)
                    if (w == v) break
                }
                result.add(comp)
            }
        }

        for (m in modules) {
            if (m.moduleId !in index) strongConnect(m.moduleId)
        }
        return result
    }

    /** 把 SCC 按“依赖在前”的顺序排好（组内循环依赖保持为一个整体）。 */
    private fun orderComponents(
        components: List<List<String>>,
        modules: List<ModuleManifestDto>
    ): List<List<String>> {
        val compOf = mutableMapOf<String, Int>()
        components.forEachIndexed { idx, comp -> comp.forEach { compOf[it] = idx } }
        val deps = Array(components.size) { mutableSetOf<Int>() }
        for (m in modules) {
            val from = compOf[m.moduleId] ?: continue
            for (dep in m.dependsOnModules) {
                val to = compOf[dep.moduleId] ?: continue
                if (from != to) deps[from].add(to)
            }
        }
        val visited = mutableSetOf<Int>()
        val order = mutableListOf<List<String>>()
        fun visit(c: Int) {
            if (!visited.add(c)) return
            deps[c].forEach { visit(it) }
            order.add(components[c])
        }
        components.indices.forEach { visit(it) }
        return order
    }
}
