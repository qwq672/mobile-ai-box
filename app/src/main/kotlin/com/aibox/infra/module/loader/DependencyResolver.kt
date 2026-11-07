package com.aibox.infra.module.loader

import com.aibox.infra.module.manifest.ModuleManifestDto

/**
 * 依赖解析器:拓扑排序 + 环检测。
 *
 * 输入:一组 [ModuleManifestDto]。
 * 输出:加载顺序(被依赖模块在前,依赖者在后)。
 *
 * **算法**:Kahn's algorithm(BFS 拓扑排序),节点入度 = 它依赖多少个其他模块。
 *
 * **环检测**:如果拓扑序最后没有包含所有节点 → 有环。
 *
 * **缺失依赖**:如果某个 module 的 depends_on_modules 中的 module_id 不在输入集合中,
 * 视为"缺失依赖",返回 [ResolveResult.MissingDependencies]。
 *
 * **零异常约定**:不抛异常。
 *
 * **线程安全**:无状态(所有方法都是纯函数),可并发调用。
 *
 * @see ModuleManifestDto
 */
object DependencyResolver {

    /**
     * 解析多个模块的依赖,返回加载顺序。
     *
     * @param manifests 一组模块的 manifest
     * @return [ResolveResult.Ok] 含拓扑序;或 [ResolveResult.Err] / [MissingDependencies] / [CycleDetected]
     */
    fun resolve(manifests: List<ModuleManifestDto>): ResolveResult {
        if (manifests.isEmpty()) return ResolveResult.Ok(emptyList())

        // 1. module_id 唯一性检查
        val idCount = manifests.groupingBy { it.moduleId }.eachCount()
        val duplicate = idCount.entries.firstOrNull { it.value > 1 }
        if (duplicate != null) {
            return ResolveResult.Err("module_id 重复: '${duplicate.key}' 出现 ${duplicate.value} 次")
        }

        val idToManifest = manifests.associateBy { it.moduleId }

        // 2. 缺失依赖检查
        val missing = mutableListOf<MissingDep>()
        for (m in manifests) {
            for (dep in m.dependsOnModules) {
                val depManifest = idToManifest[dep.moduleId]
                if (depManifest == null) {
                    missing.add(MissingDep(
                        dependent = m.moduleId,
                        missingModuleId = dep.moduleId,
                        reason = "module not in input set"
                    ))
                } else {
                    // 版本检查
                    if (!versionSatisfies(depManifest.version, dep.minVersion)) {
                        missing.add(MissingDep(
                            dependent = m.moduleId,
                            missingModuleId = dep.moduleId,
                            reason = "version ${depManifest.version} < min_version ${dep.minVersion}"
                        ))
                    }
                }
            }
        }
        if (missing.isNotEmpty()) {
            return ResolveResult.MissingDependencies(missing)
        }

        // 3. Kahn's algorithm
        // in-degree[node] = 该节点依赖的其他节点数
        val inDegree = manifests.associate { it.moduleId to it.dependsOnModules.size }.toMutableMap()
        // adjacency: 被依赖 -> 依赖者列表
        val adjacency = manifests.associate { it.moduleId to mutableListOf<String>() }
        for (m in manifests) {
            for (dep in m.dependsOnModules) {
                adjacency[dep.moduleId]?.add(m.moduleId)
            }
        }

        // 初始队列:in-degree=0 的节点
        val queue: ArrayDeque<String> = ArrayDeque()
        inDegree.entries.filter { it.value == 0 }.forEach { queue.addLast(it.key) }

        val topo = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            topo.add(cur)
            for (next in adjacency[cur] ?: emptyList()) {
                val newDeg = (inDegree[next] ?: 0) - 1
                inDegree[next] = newDeg
                if (newDeg == 0) queue.addLast(next)
            }
        }

        // 4. 环检测
        if (topo.size != manifests.size) {
            val inCycle = manifests.map { it.moduleId }.filterNot { it in topo }
            return ResolveResult.CycleDetected(inCycle)
        }

        // 5. 把 module_id 转换回 manifest
        val ordered = topo.mapNotNull { idToManifest[it] }
        return ResolveResult.Ok(ordered)
    }

    /**
     * 简化版语义版本比较:`a.b.c` 比较。
     *
     * 仅支持主版本.次版本.修订版本(忽略 -prerelease)。
     * 例:"1.16.0" >= "1.16.0" → true; "1.16.1" >= "1.16.0" → true; "1.15.0" >= "1.16.0" → false。
     */
    fun versionSatisfies(actual: String, required: String): Boolean {
        val aParts = actual.split('-').first().split('.')
        val rParts = required.split('-').first().split('.')
        if (aParts.size < 3 || rParts.size < 3) return false
        for (i in 0..2) {
            val a = aParts[i].toIntOrNull() ?: return false
            val r = rParts[i].toIntOrNull() ?: return false
            if (a > r) return true
            if (a < r) return false
        }
        return true  // 三段都相等
    }
}

// ---- Result 类型 ----

sealed class ResolveResult {
    /** @param ordered 拓扑序(被依赖在前) */
    data class Ok(val ordered: List<ModuleManifestDto>) : ResolveResult()

    /** 通用错误 */
    data class Err(val reason: String) : ResolveResult()

    /** 缺失或版本不满足的依赖 */
    data class MissingDependencies(val missing: List<MissingDep>) : ResolveResult()

    /** 检测到循环依赖,@param inCycle 参与环的 module_id 列表 */
    data class CycleDetected(val inCycle: List<String>) : ResolveResult()
}

data class MissingDep(
    val dependent: String,         // 依赖者
    val missingModuleId: String,   // 被依赖但找不到的 module_id
    val reason: String             // 找不到的原因: not_in_set / version_low
)
