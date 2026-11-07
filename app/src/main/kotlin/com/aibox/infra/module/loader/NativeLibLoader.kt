package com.aibox.infra.module.loader

import com.aibox.util.log.LogSystem

/**
 * Native 库加载器:基于 dlopen + 引用计数 + SONAME 去重。
 *
 * **核心机制**:
 * 1. **SONAME 去重**:同一 SONAME 的 .so 全局只 dlopen 一次。
 * 2. **引用计数**:每个 .so 维护 owner set(按 module_id),所有 owner 卸载后才 dlclose。
 * 3. **加载模式**:shared=true 用 RTLD_NOW|RTLD_GLOBAL;shared=false 用 RTLD_NOW|RTLD_LOCAL。
 * 4. **加载顺序**:由 [DependencyResolver] 决定拓扑序,本类按传入顺序串行 dlopen。
 *
 * **零异常约定**:所有方法返回 [LoadResult],不抛异常。
 *
 * **线程安全**:所有公开方法 synchronized(this) 保护。
 *
 * **与系统 System.loadLibrary 的关系**:
 * 本类**只**用于模块 APK 自带的 .so 文件(通过 unpacked lib 路径),不替代
 * `System.loadLibrary("aibox")`(那是宿主自己链接进来的库,启动时已加载)。
 */
class NativeLibLoader {

    private val tag = "aibox.module.nativeloader"

    /** 已加载库状态。 */
    private data class LoadedLib(
        val soname: String,
        val shared: Boolean,
        val handle: Long,
        val owners: MutableSet<String>,  // module_id set
        val loadedAt: Long
    )

    /** SONAME -> LoadedLib。 */
    private val loaded = mutableMapOf<String, LoadedLib>()

    /**
     * 加载一个 .so。如果同 SONAME 已加载 → 仅添加 owner,不重复 dlopen。
     *
     * @param soname 库的 SONAME,如 "libonnxruntime.so.1.16"
     * @param libPath 库文件的绝对路径(已 unpack 到 data/local 或 libDir)
     * @param shared true=RTLD_GLOBAL, false=RTLD_LOCAL
     * @param ownerModuleId 谁请求加载(用于引用计数)
     * @return [LoadResult.Ok] 含 native handle; [LoadResult.Err] 含失败原因
     */
    @Synchronized
    fun load(
        soname: String,
        libPath: String,
        shared: Boolean,
        ownerModuleId: String
    ): LoadResult {
        // 1. 去重:SONAME 已存在 → 只追加 owner
        loaded[soname]?.let { existing ->
            if (existing.shared != shared) {
                // 同 SONAME 两次请求 shared 不一致 → 拒绝,要求 manifest 修正
                val err = "SONAME '$soname' 加载模式冲突: 已 ${existing.shared},请求 $shared"
                LogSystem.warn(tag, err)
                return LoadResult.Err(err)
            }
            existing.owners.add(ownerModuleId)
            LogSystem.info(tag, "复用已加载 $soname (+owner=$ownerModuleId, total=${existing.owners.size})")
            return LoadResult.Ok(existing.handle)
        }

        // 2. 检查路径
        if (libPath.isBlank()) {
            return LoadResult.Err("libPath 为空")
        }

        // 3. 调用 native dlopen
        val flags = if (shared) {
            (RTLD_NOW or RTLD_GLOBAL)
        } else {
            (RTLD_NOW or RTLD_LOCAL)
        }

        val handle = nativeDlopen(libPath, flags)
        if (handle == 0L) {
            val err = nativeDlerror() ?: "unknown"
            LogSystem.error(tag, "dlopen 失败: $libPath ($err)")
            return LoadResult.Err("dlopen 失败: $err", detail = libPath)
        }

        // 4. 记录
        val lib = LoadedLib(
            soname = soname,
            shared = shared,
            handle = handle,
            owners = mutableSetOf(ownerModuleId),
            loadedAt = System.currentTimeMillis()
        )
        loaded[soname] = lib
        LogSystem.info(tag, "dlopen 成功: $soname (shared=$shared, owner=$ownerModuleId) handle=$handle")

        return LoadResult.Ok(handle)
    }

    /**
     * 卸载一个 .so 的某个 owner。引用计数减 1,只有最后一个 owner 卸载才真正 dlclose。
     *
     * @return [UnloadResult.Ok] 含是否真正 dlclose; [UnloadResult.Err] 含失败原因
     */
    @Synchronized
    fun unload(soname: String, ownerModuleId: String): UnloadResult {
        val lib = loaded[soname] ?: run {
            return UnloadResult.Err("SONAME '$soname' 未加载")
        }

        if (!lib.owners.remove(ownerModuleId)) {
            // 不是 owner,无影响
            LogSystem.warn(tag, "owner $ownerModuleId 不是 $soname 的 owner,跳过")
            return UnloadResult.Ok(false)
        }

        if (lib.owners.isNotEmpty()) {
            LogSystem.info(tag, "$soname 仍有 ${lib.owners.size} 个 owner,不卸载")
            return UnloadResult.Ok(false)
        }

        // 最后一个 owner → dlclose
        val ok = nativeDlclose(lib.handle)
        if (!ok) {
            val err = nativeDlerror() ?: "unknown"
            LogSystem.error(tag, "dlclose 失败: $soname ($err)")
            // 仍从 map 删除,避免重复尝试
            loaded.remove(soname)
            return UnloadResult.Err("dlclose 失败: $err")
        }

        loaded.remove(soname)
        LogSystem.info(tag, "dlclose 成功: $soname")
        return UnloadResult.Ok(true)
    }

    /** 卸载某 module_id 持有的所有库(用于模块整体卸载)。 */
    @Synchronized
    fun unloadAll(ownerModuleId: String): List<String> {
        val sonames = loaded.values
            .filter { it.owners.contains(ownerModuleId) }
            .map { it.soname }
            .toList()
        sonames.forEach { unload(it, ownerModuleId) }
        return sonames
    }

    /** 查询当前所有已加载库(调试用)。 */
    @Synchronized
    fun listLoaded(): List<LoadedLibInfo> = loaded.values.map {
        LoadedLibInfo(
            soname = it.soname,
            shared = it.shared,
            owners = it.owners.toSet(),
            loadedAt = it.loadedAt
        )
    }

    /** 已加载库信息(无 handle,纯调试)。 */
    data class LoadedLibInfo(
        val soname: String,
        val shared: Boolean,
        val owners: Set<String>,
        val loadedAt: Long
    )

    // ---- Result 类型 ----
    sealed class LoadResult {
        data class Ok(val handle: Long) : LoadResult()
        data class Err(val reason: String, val detail: String? = null) : LoadResult()
    }

    sealed class UnloadResult {
        /** @param actuallyClosed true=真正 dlclose,false=仅减少引用计数 */
        data class Ok(val actuallyClosed: Boolean) : UnloadResult()
        data class Err(val reason: String) : UnloadResult()
    }

    companion object {
        /** Linux RTLD flags(从 dlfcn.h)。 */
        private const val RTLD_NOW = 0x2
        private const val RTLD_GLOBAL = 0x100
        private const val RTLD_LOCAL = 0x0

        @JvmStatic private external fun nativeDlopen(path: String, flags: Int): Long
        @JvmStatic private external fun nativeDlclose(handle: Long): Boolean
        @JvmStatic private external fun nativeDlerror(): String?

        init {
            // 不强行加载(可能本宿主没编译该 native 库,允许在没集成到 native 时降级运行)
            try {
                System.loadLibrary("aibox_module_loader")
            } catch (e: UnsatisfiedLinkError) {
                LogSystem.warn("aibox.module.nativeloader", "libaibox_module_loader 未加载,NativeLibLoader 不可用")
            }
        }
    }
}
