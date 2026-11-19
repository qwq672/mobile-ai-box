package com.aibox.infra.module.loader

import com.aibox.infra.module.manifest.AbiDto
import com.aibox.infra.module.manifest.ModuleDependencyDto
import com.aibox.infra.module.manifest.ModuleManifestDto
import com.aibox.infra.module.manifest.ModuleTypeDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleGraphResolverTest {

    private fun manifest(
        id: String,
        version: String = "1.0.0",
        deps: List<ModuleDependencyDto> = emptyList()
    ) = ModuleManifestDto(
        schemaVersion = "1.0.0",
        moduleId = id,
        version = version,
        moduleType = ModuleTypeDto.IMAGE_GEN,
        minHostApi = 1,
        abiFilters = listOf(AbiDto.ARM64_V8A),
        dependsOnModules = deps,
        exports = listOf("aibox_${id.replace('.', '_')}_v1")
    )

    @Test
    fun `required dependency missing excludes module`() {
        val a = manifest(
            "a.sd",
            deps = listOf(ModuleDependencyDto("a.backend", required = true))
        )
        val plan = ModuleGraphResolver.resolve(listOf(a))
        assertTrue(plan.excluded.any { it.moduleId == "a.sd" && it.reason.contains("缺少依赖") })
        assertTrue(plan.groups.none { "a.sd" in it.moduleIds })
    }

    @Test
    fun `optional dependency missing does not exclude module`() {
        val a = manifest(
            "a.sd",
            deps = listOf(
                ModuleDependencyDto(
                    moduleId = "a.npu",
                    required = false,
                    promptAt = "firstUse",
                    fallback = "cpu",
                    triggerCapabilities = listOf("npu.prefill")
                )
            )
        )
        val plan = ModuleGraphResolver.resolve(listOf(a))
        assertFalse(plan.excluded.any { it.moduleId == "a.sd" })
        assertTrue(plan.groups.any { "a.sd" in it.moduleIds })
        val missing = plan.optionalMissing.single()
        assertEquals("a.sd", missing.moduleId)
        assertEquals("a.npu", missing.dependencyId)
        assertEquals("cpu", missing.fallback)
        assertEquals(listOf("npu.prefill"), missing.triggerCapabilities)
    }

    @Test
    fun `optional edges do not create cyclic group`() {
        val a = manifest(
            "a.one",
            deps = listOf(ModuleDependencyDto("a.two", required = false, fallback = "disable_feature"))
        )
        val b = manifest(
            "a.two",
            deps = listOf(ModuleDependencyDto("a.one", required = false, fallback = "disable_feature"))
        )
        val plan = ModuleGraphResolver.resolve(listOf(a, b))
        assertTrue(plan.excluded.isEmpty())
        assertEquals(2, plan.groups.size)
        assertTrue(plan.groups.none { it.cyclic })
    }
}
