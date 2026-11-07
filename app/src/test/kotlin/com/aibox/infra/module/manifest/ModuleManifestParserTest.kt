package com.aibox.infra.module.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ModuleManifestParser 单测。
 */
class ModuleManifestParserTest {

    private val parser = ModuleManifestParser(strict = true)

    private val validChatManifest = """
    {
      "schema_version": "1.0.0",
      "module_id": "chat.basic",
      "version": "0.1.0",
      "module_type": "chat",
      "min_host_api": 1,
      "abi_filters": ["arm64-v8a"],
      "depends_on_libs": [
        {"soname": "libllama.so", "shared": true, "min_version": "0.4.1"},
        {"soname": "libggml-vulkan.so", "shared": true}
      ],
      "depends_on_modules": [],
      "exports": ["aibox_chat_v1"],
      "interface_aidl": "com.aibox.chat.IChatModule",
      "capabilities": ["chat_completion", "token_stream"]
    }
    """.trimIndent()

    @Test
    fun `valid chat manifest parses OK`() {
        val r = parser.parse(validChatManifest)
        assertTrue("expected Ok, got $r", r is ParseResult.Ok)
        val m = (r as ParseResult.Ok).manifest
        assertEquals("chat.basic", m.moduleId)
        assertEquals("0.1.0", m.version)
        assertEquals(ModuleTypeDto.CHAT, m.moduleType)
        assertEquals(2, m.dependsOnLibs.size)
        assertEquals("libllama.so", m.dependsOnLibs[0].soname)
        assertTrue(m.dependsOnLibs[0].shared)
        assertEquals(listOf("aibox_chat_v1"), m.exports)
    }

    @Test
    fun `invalid schema version rejected`() {
        val text = validChatManifest.replace("\"1.0.0\"", "\"2.0.0\"")
        val r = parser.parse(text)
        assertTrue(r is ParseResult.Err)
        assertTrue((r as ParseResult.Err).reason.contains("schema_version"))
    }

    @Test
    fun `invalid module_id format rejected`() {
        // 缺少点的 module_id
        val text = validChatManifest.replace("\"chat.basic\"", "\"chatbasic\"")
        val r = parser.parse(text)
        assertTrue(r is ParseResult.Err)
        assertTrue((r as ParseResult.Err).reason.contains("module_id"))
    }

    @Test
    fun `invalid version format rejected`() {
        val text = validChatManifest.replace("\"0.1.0\"", "\"v0.1.0\"")
        val r = parser.parse(text)
        assertTrue(r is ParseResult.Err)
        assertTrue((r as ParseResult.Err).reason.contains("version"))
    }

    @Test
    fun `empty abi_filters rejected`() {
        val text = validChatManifest.replace("\"abi_filters\": [\"arm64-v8a\"]", "\"abi_filters\": []")
        val r = parser.parse(text)
        assertTrue(r is ParseResult.Err)
        assertTrue((r as ParseResult.Err).reason.contains("abi_filters"))
    }

    @Test
    fun `invalid exports name rejected`() {
        // exports 不以 aibox_ 开头
        val text = validChatManifest.replace("\"aibox_chat_v1\"", "\"chat_v1\"")
        val r = parser.parse(text)
        assertTrue(r is ParseResult.Err)
        assertTrue((r as ParseResult.Err).reason.contains("exports"))
    }

    @Test
    fun `duplicate soname rejected`() {
        val text = """
        {
          "schema_version": "1.0.0",
          "module_id": "test.dup",
          "version": "0.1.0",
          "module_type": "chat",
          "min_host_api": 1,
          "abi_filters": ["arm64-v8a"],
          "depends_on_libs": [
            {"soname": "libfoo.so", "shared": true},
            {"soname": "libfoo.so", "shared": true}
          ],
          "exports": ["aibox_test_v1"]
        }
        """.trimIndent()
        val r = parser.parse(text)
        assertTrue(r is ParseResult.Err)
        assertTrue((r as ParseResult.Err).reason.contains("重复"))
    }

    @Test
    fun `safe_context static mode requires default_safe`() {
        // 静态模式但没给 default_safe
        val text = """
        {
          "schema_version": "1.0.0",
          "module_id": "litert.test",
          "version": "0.1.0",
          "module_type": "backend_consumer",
          "min_host_api": 1,
          "abi_filters": ["arm64-v8a"],
          "exports": ["aibox_test_v1"],
          "safe_context": {"auto_detect": false}
        }
        """.trimIndent()
        val r = parser.parse(text)
        assertTrue(r is ParseResult.Err)
        assertTrue((r as ParseResult.Err).reason.contains("default_safe"))
    }

    @Test
    fun `safe_context auto_detect requires benchmark_corpus`() {
        val text = """
        {
          "schema_version": "1.0.0",
          "module_id": "litert.test",
          "version": "0.1.0",
          "module_type": "backend_consumer",
          "min_host_api": 1,
          "abi_filters": ["arm64-v8a"],
          "exports": ["aibox_test_v1"],
          "safe_context": {"auto_detect": true}
        }
        """.trimIndent()
        val r = parser.parse(text)
        assertTrue(r is ParseResult.Err)
        assertTrue((r as ParseResult.Err).reason.contains("benchmark_corpus"))
    }

    @Test
    fun `safe_context only allowed on LiteRT-like module types`() {
        // backend_runtime 不在 LiteRT 类集合中,声明 safe_context 应拒绝
        val text = """
        {
          "schema_version": "1.0.0",
          "module_id": "runtime.test",
          "version": "0.1.0",
          "module_type": "backend_runtime",
          "min_host_api": 1,
          "abi_filters": ["arm64-v8a"],
          "exports": ["aibox_test_v1"],
          "safe_context": {"auto_detect": false, "default_safe": 1024}
        }
        """.trimIndent()
        val r = parser.parse(text)
        assertTrue(r is ParseResult.Err)
        assertTrue((r as ParseResult.Err).reason.contains("LiteRT"))
    }

    @Test
    fun `gemma4 e4b manifest parses OK`() {
        // E4B 反直觉:default_safe=1500 比 E2B 的 2048 还低
        val text = """
        {
          "schema_version": "1.0.0",
          "module_id": "litert.gemma4-e4b",
          "version": "0.1.0",
          "module_type": "backend_consumer",
          "min_host_api": 1,
          "abi_filters": ["arm64-v8a"],
          "depends_on_libs": [
            {"soname": "liblitert.so", "shared": true, "min_version": "1.0.0"}
          ],
          "exports": ["aibox_litert_gemma4_e4b_v1"],
          "capabilities": ["chat_completion", "safe_context_auto_detect"],
          "safe_context": {
            "auto_detect": false,
            "default_safe": 1500,
            "default_warn": 2000,
            "default_block": 2500,
            "metric": "perplexity_ratio",
            "threshold": 0.7,
            "cache_key": "litert.gemma4-e4b:0.1.0",
            "model_id": "gemma-4-e4b"
          }
        }
        """.trimIndent()
        val r = parser.parse(text)
        assertTrue(r is ParseResult.Ok)
        val m = (r as ParseResult.Ok).manifest
        assertNotNull(m.safeContext)
        assertEquals(1500, m.safeContext!!.defaultSafe)
        assertEquals(2500, m.safeContext!!.defaultBlock)
    }

    @Test
    fun `depends_on_modules cannot self-reference`() {
        val text = """
        {
          "schema_version": "1.0.0",
          "module_id": "test.self",
          "version": "0.1.0",
          "module_type": "chat",
          "min_host_api": 1,
          "abi_filters": ["arm64-v8a"],
          "depends_on_modules": [
            {"module_id": "test.self", "min_version": "0.1.0"}
          ],
          "exports": ["aibox_test_v1"]
        }
        """.trimIndent()
        val r = parser.parse(text)
        assertTrue(r is ParseResult.Err)
        assertTrue((r as ParseResult.Err).reason.contains("自引用"))
    }

    @Test
    fun `malformed JSON rejected`() {
        val r = parser.parse("{not valid json")
        assertTrue(r is ParseResult.Err)
        assertTrue((r as ParseResult.Err).reason.contains("JSON"))
    }
}
