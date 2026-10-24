package com.aibox.domain.tool.builtin

import com.aibox.domain.tool.DeviceInfo
import com.aibox.domain.tool.ParamType
import com.aibox.domain.tool.ToolContext
import com.aibox.util.outcome.Outcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UrlReaderTool] 元数据测试（B4 基础工具）。
 *
 * **不**做实际网络抓取测试（环境无网络且 Jsoup.connect 需真实 HTTP）。
 * 只覆盖：
 * - 工具元数据（id / displayName / category / icon）
 * - 参数 schema 结构
 * - 缺失 url 时的失败路径
 *
 * 实际抓取行为靠人工 review + 集成测试（运行时 adb 调起真机）。
 */
class UrlReaderToolTest {

    private val tool = UrlReaderTool()

    private val dummyContext = ToolContext(
        sessionId = "test-session",
        userId = "test",
        deviceInfo = DeviceInfo(
            deviceModel = "TestDevice",
            androidVersion = "14",
            sdkVersion = 34,
            abis = listOf("arm64-v8a"),
            totalRamMb = 8192L,
            availableRamMb = 4096L,
            batteryLevel = 80,
            isCharging = true
        )
    )

    @Test
    fun `tool metadata is correct`() {
        assertEquals("url_reader", tool.id)
        assertEquals("URL 阅读器", tool.displayName)
        assertEquals(com.aibox.domain.tool.ToolCategory.BASIC, tool.category)
        assertEquals("🌐", tool.icon)
    }

    @Test
    fun `parameter schema has required url and 2 optional`() {
        val schema = tool.getParameterSchema()
        assertEquals(1, schema.required.size)
        assertEquals("url", schema.required[0].name)
        assertEquals(ParamType.STRING, schema.required[0].type)

        assertEquals(2, schema.optional.size)
        assertEquals("max_length", schema.optional[0].name)
        assertEquals(ParamType.INTEGER, schema.optional[0].type)
        assertEquals(5000, schema.optional[0].default)

        assertEquals("include_links", schema.optional[1].name)
        assertEquals(ParamType.BOOLEAN, schema.optional[1].type)
        assertEquals(true, schema.optional[1].default)
    }

    @Test
    fun `missing url returns failure`() = runTest {
        val outcome = tool.execute(emptyMap(), dummyContext)
        assertTrue(outcome is Outcome.Failure)
        assertEquals("Missing url", (outcome as Outcome.Failure).reason)
    }

    @Test
    fun `blank url returns failure`() = runTest {
        val outcome = tool.execute(mapOf("url" to "   "), dummyContext)
        assertTrue(outcome is Outcome.Failure)
        assertEquals("Missing url", (outcome as Outcome.Failure).reason)
    }
}
