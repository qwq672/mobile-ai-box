package com.aibox.infra.module.loader

import com.aibox.infra.module.manifest.AbiDto
import com.aibox.infra.module.manifest.LibDependencyDto
import com.aibox.infra.module.manifest.ModuleDependencyDto
import com.aibox.infra.module.manifest.ModuleManifestDto
import com.aibox.infra.module.manifest.ModuleTypeDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DependencyResolver 单测:拓扑排序 + 环检测 + 版本检查。
 */
class DependencyResolverTest {

    private fun manifest(
        id: String,
        version: String = "0.1.0",
        deps: List<ModuleDependencyDto> = emptyList()
    ) = ModuleManifestDto(
        schemaVersion = "1.0.0",
        moduleId = id,
        version = version,
        moduleType = ModuleTypeDto.CHAT,
        minHostApi = 1,
        abiFilters = listOf(AbiDto.ARM64_V8A),
        dependsOnModules = deps,
        exports = listOf("aibox_${id.replace('.', '_')}_v1")
    )

    @Test
    fun `empty input returns empty ordering`() {
        val r = DependencyResolver.resolve(emptyList())
        assertTrue(r is ResolveResult.Ok)
        assertEquals(0, (r as ResolveResult.Ok).ordered.size)
    }

    @Test
    fun `single module returns single-element ordering`() {
        val m = manifest("chat.basic")
        val r = DependencyResolver.resolve(listOf(m))
        assertTrue(r is ResolveResult.Ok)
        assertEquals(listOf("chat.basic"), (r as ResolveResult.Ok).ordered.map { it.moduleId })
    }

    @Test
    fun `linear dependency order is reversed`() {
        // c -> b -> a,加载顺序应为 [a, b, c]
        val a = manifest("a")
        val b = manifest("b", deps = listOf(ModuleDependencyDto("a", "0.1.0")))
        val c = manifest("c", deps = listOf(ModuleDependencyDto("b", "0.1.0")))
        val r = DependencyResolver.resolve(listOf(c, b, a))
        assertTrue(r is ResolveResult.Ok)
        val ids = (r as ResolveResult.Ok).ordered.map { it.moduleId }
        assertEquals(listOf("a", "b", "c"), ids)
    }

    @Test
    fun `diamond dependency resolves with both roots before leaf`() {
        // a, b depends on a, c depends on a, d depends on b and c
        // 加载顺序: a 必先, b 和 c 在 a 之后,d 在最后
        val a = manifest("a")
        val b = manifest("b", deps = listOf(ModuleDependencyDto("a", "0.1.0")))
        val c = manifest("c", deps = listOf(ModuleDependencyDto("a", "0.1.0")))
        val d = manifest("d", deps = listOf(
            ModuleDependencyDto("b", "0.1.0"),
            ModuleDependencyDto("c", "0.1.0")
        ))
        val r = DependencyResolver.resolve(listOf(d, c, b, a))
        assertTrue(r is ResolveResult.Ok)
        val ids = (r as ResolveResult.Ok).ordered.map { it.moduleId }
        assertEquals("a", ids.first())
        assertEquals("d", ids.last())
        assertTrue("b must come before d", ids.indexOf("b") < ids.indexOf("d"))
        assertTrue("c must come before d", ids.indexOf("c") < ids.indexOf("d"))
        assertTrue("a must come before b and c",
            ids.indexOf("a") < ids.indexOf("b") && ids.indexOf("a") < ids.indexOf("c"))
    }

    @Test
    fun `cycle detected returns CycleDetected`() {
        // a -> b -> a
        val a = manifest("a", deps = listOf(ModuleDependencyDto("b", "0.1.0")))
        val b = manifest("b", deps = listOf(ModuleDependencyDto("a", "0.1.0")))
        val r = DependencyResolver.resolve(listOf(a, b))
        assertTrue(r is ResolveResult.CycleDetected)
        val inCycle = (r as ResolveResult.CycleDetected).inCycle
        assertEquals(2, inCycle.size)
        assertTrue("a" in inCycle)
        assertTrue("b" in inCycle)
    }

    @Test
    fun `larger cycle detected`() {
        // a -> b -> c -> a
        val a = manifest("a", deps = listOf(ModuleDependencyDto("b", "0.1.0")))
        val b = manifest("b", deps = listOf(ModuleDependencyDto("c", "0.1.0")))
        val c = manifest("c", deps = listOf(ModuleDependencyDto("a", "0.1.0")))
        val r = DependencyResolver.resolve(listOf(a, b, c))
        assertTrue(r is ResolveResult.CycleDetected)
        assertEquals(3, (r as ResolveResult.CycleDetected).inCycle.size)
    }

    @Test
    fun `missing dependency detected`() {
        // a depends on b, but b is not in input set
        val a = manifest("a", deps = listOf(ModuleDependencyDto("b", "0.1.0")))
        val r = DependencyResolver.resolve(listOf(a))
        assertTrue(r is ResolveResult.MissingDependencies)
        val missing = (r as ResolveResult.MissingDependencies).missing
        assertEquals(1, missing.size)
        assertEquals("a", missing[0].dependent)
        assertEquals("b", missing[0].missingModuleId)
    }

    @Test
    fun `version mismatch detected`() {
        // a depends on b@0.2.0, but b is 0.1.0
        val a = manifest("a", deps = listOf(ModuleDependencyDto("b", "0.2.0")))
        val b = manifest("b", version = "0.1.0")
        val r = DependencyResolver.resolve(listOf(a, b))
        assertTrue(r is ResolveResult.MissingDependencies)
        val missing = (r as ResolveResult.MissingDependencies).missing
        assertEquals(1, missing.size)
        assertTrue(missing[0].reason.contains("version"))
    }

    @Test
    fun `duplicate module_id rejected`() {
        val a1 = manifest("a")
        val a2 = manifest("a")
        val r = DependencyResolver.resolve(listOf(a1, a2))
        assertTrue(r is ResolveResult.Err)
        assertTrue((r as ResolveResult.Err).reason.contains("重复"))
    }

    @Test
    fun `versionSatisfies basic cases`() {
        assertTrue(DependencyResolver.versionSatisfies("1.16.0", "1.16.0"))
        assertTrue(DependencyResolver.versionSatisfies("1.16.1", "1.16.0"))
        assertTrue(DependencyResolver.versionSatisfies("1.17.0", "1.16.0"))
        assertTrue(DependencyResolver.versionSatisfies("2.0.0", "1.16.0"))
        assertTrue(DependencyResolver.versionSatisfies("1.16.0-rc1", "1.16.0"))
        assertTrue(DependencyResolver.versionSatisfies("1.16.0", "1.0.0"))

        assertTrue(!DependencyResolver.versionSatisfies("1.15.0", "1.16.0"))
        assertTrue(!DependencyResolver.versionSatisfies("0.16.0", "1.16.0"))
        assertTrue(!DependencyResolver.versionSatisfies("1.16", "1.16.0"))
        assertTrue(!DependencyResolver.versionSatisfies("1.16.0", "1.16"))
    }

    @Test
    fun `gemma4 scenario: chat basic + agent + roleplay with both depending on chat`() {
        // 真实场景:chat.basic + agent.react(依赖 chat) + chat.roleplay(依赖 chat)
        val chat = manifest("chat.basic", version = "0.1.0")
        val agent = ModuleManifestDto(
            schemaVersion = "1.0.0",
            moduleId = "agent.react",
            version = "0.1.0",
            moduleType = ModuleTypeDto.AGENT,
            minHostApi = 1,
            abiFilters = listOf(AbiDto.ARM64_V8A),
            dependsOnModules = listOf(ModuleDependencyDto("chat.basic", "0.1.0")),
            exports = listOf("aibox_agent_v1")
        )
        val roleplay = ModuleManifestDto(
            schemaVersion = "1.0.0",
            moduleId = "chat.roleplay",
            version = "0.1.0",
            moduleType = ModuleTypeDto.ROLEPLAY,
            minHostApi = 1,
            abiFilters = listOf(AbiDto.ARM64_V8A),
            dependsOnModules = listOf(ModuleDependencyDto("chat.basic", "0.1.0")),
            exports = listOf("aibox_roleplay_v1")
        )
        val r = DependencyResolver.resolve(listOf(roleplay, agent, chat))
        assertTrue(r is ResolveResult.Ok)
        val ids = (r as ResolveResult.Ok).ordered.map { it.moduleId }
        assertEquals("chat.basic 必须先加载", "chat.basic", ids.first())
        assertEquals(3, ids.size)
        assertTrue("agent 必须在 chat.basic 之后", ids.indexOf("agent.react") > ids.indexOf("chat.basic"))
        assertTrue("roleplay 必须在 chat.basic 之后", ids.indexOf("chat.roleplay") > ids.indexOf("chat.basic"))
    }
}
