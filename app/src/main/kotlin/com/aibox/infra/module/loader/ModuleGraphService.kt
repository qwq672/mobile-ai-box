package com.aibox.infra.module.loader

import android.content.Context
import com.aibox.infra.module.manifest.ModuleManifestDto
import com.aibox.infra.module.manifest.ModuleManifestParser
import com.aibox.infra.module.manifest.ParseResult
import com.aibox.util.log.LogSystem

/**
 * 扫描已安装模块 APK 里的 `assets/module_manifest/manifest.json`，
 * 生成一份可展示的依赖计划。
 *
 * 这是一个**只读**服务：不改插件启用状态，只回答
 * 「按当前已安装版本，哪些模块能一起加载、顺序是什么、谁被冲突/版本卡住」。
 */
class ModuleGraphService(private val context: Context) {

    private val tag = "aibox.module.graph"
    private val parser = ModuleManifestParser(strict = false)

    data class Entry(
        val packageName: String,
        val moduleId: String?,
        val version: String?,
        /** 没有 manifest / 解析失败的原因；null 表示正常。 */
        val error: String?
    )

    data class Report(
        val entries: List<Entry>,
        val plan: ModuleResolutionPlan
    )

    fun scan(packageNames: List<String>, preferredModules: Set<String> = emptySet()): Report {
        val manifests = mutableListOf<ModuleManifestDto>()
        val entries = mutableListOf<Entry>()

        for (pkg in packageNames.distinct()) {
            val text = readManifestText(pkg)
            if (text == null) {
                entries += Entry(pkg, null, null, "未提供 assets/module_manifest/manifest.json")
                continue
            }
            when (val r = parser.parse(text)) {
                is ParseResult.Ok -> {
                    manifests += r.manifest
                    entries += Entry(pkg, r.manifest.moduleId, r.manifest.version, null)
                }
                is ParseResult.Err -> {
                    entries += Entry(pkg, null, null, "${r.reason}${r.detail?.let { "：$it" }.orEmpty()}")
                    LogSystem.warn(tag, "manifest parse failed for $pkg: ${r.reason} ${r.detail}")
                }
            }
        }

        return Report(entries, ModuleGraphResolver.resolve(manifests, preferredModules))
    }

    private fun readManifestText(packageName: String): String? {
        return try {
            val pkgContext = context.createPackageContext(packageName, 0)
            val candidates = listOf(
                "module_manifest/manifest.json",
                "module_manifest.json"
            )
            for (path in candidates) {
                val text = runCatching {
                    pkgContext.assets.open(path).bufferedReader().use { it.readText() }
                }.getOrNull()
                if (!text.isNullOrBlank()) return text
            }
            null
        } catch (e: Throwable) {
            LogSystem.warn(tag, "readManifestText($packageName) failed: ${e.message}", e)
            null
        }
    }
}
