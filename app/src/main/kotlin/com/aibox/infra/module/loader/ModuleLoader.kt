package com.aibox.infra.module.loader

import android.content.Context
import android.os.Build
import com.aibox.infra.module.manifest.ModuleManifestDto
import com.aibox.infra.module.manifest.ModuleManifestParser
import com.aibox.infra.module.manifest.ParseResult
import com.aibox.util.log.LogSystem

/**
 * 模块加载器:整合 manifest 解析 + 依赖解析 + native 库加载。
 *
 * **职责**:
 * 1. 从模块 APK assets 解析 manifest
 * 2. ABI 过滤(设备 ABI 不在 manifest.abi_filters 中则拒绝)
 * 3. min_host_api 检查(宿主 API 版本 < manifest.min_host_api 则拒绝)
 * 4. 通过 [DependencyResolver] 做拓扑排序
 * 5. 按拓扑序加载每个模块的 native 库(由 [NativeLibLoader] 做 SONAME 去重 + 引用计数)
 * 6. 注册模块到宿主能力注册表(供其他模块按 capabilities 查询)
 *
 * **零异常约定**:所有公开方法返回 [LoadResult] / [UnloadResult],不抛异常。
 *
 * **线程安全**:所有公开方法 synchronized,串行加载。
 *
 * **架构层级**(对应 DeepSeek 设计):
 * - L0 宿主核心:本类 + [DependencyResolver] + [NativeLibLoader]
 * - L1 共享运行时:depends_on_libs[].shared=true 的库
 * - L2 模块私有:depends_on_libs[].shared=false 的库
 *
 * @param context 宿主 Context
 * @param currentHostApi 宿主 API 版本(由 host 编译时定义,目前 = 1)
 * @param libPathResolver 把 soname 解析成绝对路径(因为不同模块 APK 的 lib 路径不同)
 */
class ModuleLoader(
    private val context: Context,
    private val currentHostApi: Int = 1,
    private val libPathResolver: LibPathResolver = DefaultLibPathResolver(context)
) {

    private val tag = "aibox.module.loader"
    private val parser = ModuleManifestParser(strict = true)
    private val nativeLoader = NativeLibLoader()

    /** 已加载模块。module_id -> LoadedModule */
    private val loaded = mutableMapOf<String, LoadedModule>()

    /**
     * 加载一组模块(以 manifest JSON 文本输入)。
     *
     * @param manifestTexts 一组 manifest JSON 字符串(可来自不同模块 APK)
     * @return [BatchLoadResult] 含每个模块的加载状态
     */
    @Synchronized
    fun loadAll(manifestTexts: List<String>): BatchLoadResult {
        // 1. 解析所有 manifest
        val parsed = mutableListOf<ModuleManifestDto>()
        val parseErrors = mutableListOf<ManifestError>()
        for ((idx, text) in manifestTexts.withIndex()) {
            when (val r = parser.parse(text)) {
                is ParseResult.Ok -> parsed.add(r.manifest)
                is ParseResult.Err -> parseErrors.add(ManifestError(
                    source = "manifest#${idx}",
                    reason = r.reason,
                    detail = r.detail
                ))
            }
        }
        if (parseErrors.isNotEmpty()) {
            return BatchLoadResult.PartiallyFailed(
                loaded = emptyList(),
                failed = parseErrors
            )
        }

        // 2. ABI 过滤 + host API 检查
        val deviceAbi = getDeviceAbi()
        val filtered = mutableListOf<ModuleManifestDto>()
        val filteredOut = mutableListOf<ManifestError>()
        for (m in parsed) {
            val allowedAbis = m.abiFilters.map { it.androidName }
            if (deviceAbi.none { it in allowedAbis }) {
                filteredOut.add(ManifestError(
                    source = m.moduleId,
                    reason = "ABI 不匹配",
                    detail = "manifest ABI=$allowedAbis, device ABI=$deviceAbi"
                ))
                continue
            }
            if (m.minHostApi > currentHostApi) {
                filteredOut.add(ManifestError(
                    source = m.moduleId,
                    reason = "host API 版本不足",
                    detail = "manifest min_host_api=${m.minHostApi}, current=$currentHostApi"
                ))
                continue
            }
            filtered.add(m)
        }
        if (filtered.isEmpty()) {
            return BatchLoadResult.PartiallyFailed(emptyList(), filteredOut)
        }

        // 3. 依赖解析 + 拓扑排序
        val resolved = DependencyResolver.resolve(filtered)
        val ordered = when (resolved) {
            is ResolveResult.Ok               -> resolved.ordered
            is ResolveResult.Err              -> return BatchLoadResult.PartiallyFailed(emptyList(),
                listOf(ManifestError("dependency-resolver", resolved.reason)))
            is ResolveResult.MissingDependencies -> return BatchLoadResult.PartiallyFailed(
                emptyList(),
                resolved.missing.map {
                    ManifestError(it.dependent, "缺失依赖: ${it.missingModuleId}", it.reason)
                }
            )
            is ResolveResult.CycleDetected    -> return BatchLoadResult.PartiallyFailed(
                emptyList(),
                listOf(ManifestError(
                    "dependency-cycle",
                    "循环依赖: ${resolved.inCycle.joinToString(" -> ")}",
                    "需拆环或合并模块"
                ))
            )
        }

        // 4. 按拓扑序加载 native 库
        val loadedModules = mutableListOf<LoadedModule>()
        val loadErrors = mutableListOf<ManifestError>()

        for (m in ordered) {
            // 4.1 该模块的所有 native 库按 manifest.depends_on_libs 顺序加载
            val libHandles = mutableListOf<Pair<String, Long>>()
            var allLibsOk = true

            for (dep in m.dependsOnLibs) {
                val libPath = libPathResolver.resolve(dep.soname, m.moduleId)
                if (libPath == null) {
                    loadErrors.add(ManifestError(
                        source = m.moduleId,
                        reason = "无法解析 libPath",
                        detail = "soname=${dep.soname}"
                    ))
                    allLibsOk = false
                    break
                }
                when (val lr = nativeLoader.load(
                    soname = dep.soname,
                    libPath = libPath,
                    shared = dep.shared,
                    ownerModuleId = m.moduleId
                )) {
                    is NativeLibLoader.LoadResult.Ok -> libHandles.add(dep.soname to lr.handle)
                    is NativeLibLoader.LoadResult.Err -> {
                        loadErrors.add(ManifestError(
                            source = m.moduleId,
                            reason = "加载 native 库失败: ${lr.reason}",
                            detail = "soname=${dep.soname}, path=$libPath, ${lr.detail ?: ""}"
                        ))
                        allLibsOk = false
                        break
                    }
                }
            }

            if (!allLibsOk) {
                // 回滚:卸载本模块已加载的库
                nativeLoader.unloadAll(m.moduleId)
                continue
            }

            // 4.2 注册模块
            val lm = LoadedModule(
                moduleId = m.moduleId,
                version = m.version,
                moduleType = m.moduleType,
                exports = m.exports,
                capabilities = m.capabilities,
                safeContext = m.safeContext,
                interfaceAidl = m.interfaceAidl,
                libHandles = libHandles.toMap(),
                loadedAt = System.currentTimeMillis()
            )
            loaded[m.moduleId] = lm
            loadedModules.add(lm)
            LogSystem.info(tag, "模块已加载: ${m.moduleId}@${m.version} (libs=${libHandles.size})")
        }

        return if (loadErrors.isEmpty()) {
            BatchLoadResult.AllOk(loadedModules)
        } else {
            BatchLoadResult.PartiallyFailed(loadedModules, loadErrors)
        }
    }

    /** 卸载一个模块(及其 native 库的引用计数减 1)。 */
    @Synchronized
    fun unload(moduleId: String): UnloadResult {
        val lm = loaded.remove(moduleId) ?: return UnloadResult.Err("模块未加载: $moduleId")
        val unloaded = nativeLoader.unloadAll(moduleId)
        LogSystem.info(tag, "模块已卸载: $moduleId (libs removed: $unloaded)")
        return UnloadResult.Ok(unloaded)
    }

    /** 卸载所有模块。 */
    @Synchronized
    fun unloadAll(): List<String> {
        val ids = loaded.keys.toList()
        ids.forEach { unload(it) }
        return ids
    }

    /** 查询已加载模块。 */
    @Synchronized
    fun listLoaded(): List<LoadedModule> = loaded.values.toList()

    /** 按能力查询已加载模块(供 agent/roleplay 查"哪个模块提供 chat_completion")。 */
    @Synchronized
    fun findByCapability(capability: String): List<LoadedModule> =
        loaded.values.filter { it.capabilities.contains(capability) }

    /** 按 export 查询已加载模块(供其他模块按 aibox_chat_v1 找 chat 模块)。 */
    @Synchronized
    fun findByExport(exportName: String): LoadedModule? =
        loaded.values.firstOrNull { it.exports.contains(exportName) }

    // ---- 私有 ----

    private fun getDeviceAbi(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS.toList()
        } else {
            @Suppress("DEPRECATION")
            listOf(Build.CPU_ABI, Build.CPU_ABI2).filter { it.isNotBlank() }
        }
    }

    // ---- Result 类型 ----

    data class LoadedModule(
        val moduleId: String,
        val version: String,
        val moduleType: com.aibox.infra.module.manifest.ModuleTypeDto,
        val exports: List<String>,
        val capabilities: List<String>,
        val safeContext: com.aibox.infra.module.manifest.SafeContextDto?,
        val interfaceAidl: String?,
        val libHandles: Map<String, Long>,
        val loadedAt: Long
    )

    sealed class BatchLoadResult {
        data class AllOk(val loaded: List<LoadedModule>) : BatchLoadResult()
        data class PartiallyFailed(
            val loaded: List<LoadedModule>,
            val failed: List<ManifestError>
        ) : BatchLoadResult()
    }

    sealed class UnloadResult {
        data class Ok(val unloadedLibs: List<String>) : UnloadResult()
        data class Err(val reason: String) : UnloadResult()
    }
}

data class ManifestError(
    val source: String,    // 模块 ID 或 "manifest#N"
    val reason: String,
    val detail: String? = null
)

// ---- LibPathResolver ----

/**
 * 把 (soname, moduleId) 解析成绝对路径。
 *
 * 不同模块 APK 自带的 .so 路径不同:
 * - 已安装的模块 APK:`/data/app/<pkg>/lib/arm64/libfoo.so`
 * - 宿主 cacheDir 解压:`<cacheDir>/modules/<moduleId>/lib/arm64/libfoo.so`
 */
interface LibPathResolver {
    fun resolve(soname: String, moduleId: String): String?
}

/** 默认实现:从 cacheDir/modules/<moduleId>/ 解析。 */
class DefaultLibPathResolver(private val context: Context) : LibPathResolver {
    override fun resolve(soname: String, moduleId: String): String? {
        // 实际生产环境需要根据模块 APK 的 packageName 拼装路径
        // 这里给出基于 cacheDir 的示例路径
        val baseDir = context.cacheDir.resolve("modules/$moduleId/lib/arm64-v8a")
        val libPath = baseDir.resolve(soname)
        return if (libPath.exists()) libPath.absolutePath else null
    }
}
