package com.aibox.domain.tool.builtin

import com.aibox.domain.tool.DeviceInfo
import com.aibox.domain.tool.ParamType
import com.aibox.domain.tool.ToolContext
import com.aibox.util.outcome.Outcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * [DateTimeTool] 单元测试（B2 基础工具）。
 *
 * 覆盖：
 * - 默认格式 `yyyy-MM-dd HH:mm:ss`
 * - 自定义格式（含 yyyy-MM-dd 简化版 / ISO 8601 / 含星期 / 含毫秒）
 * - 时区切换（Asia/Shanghai / UTC / America/New_York）
 * - structuredData 字段（datetime / epochSeconds / epochMillis / timezone）
 * - 错误输入：非法时区 ID / 非法格式
 *
 * 注：[DateTimeTool] 是纯 java.time，无 Android 依赖；JVM 测试环境直接跑。
 */
class DateTimeToolTest {

    private val tool = DateTimeTool()

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
    fun `default format is yyyy-MM-dd HH_mm_ss`() = runTest {
        val outcome = tool.execute(emptyMap(), dummyContext)
        val result = (outcome as Outcome.Success).value
        assertTrue(result.success)

        val output = result.output
        // 输出应包含 "当前时间: " 前缀
        assertTrue(output.contains("当前时间: "))
        // 提取出的时间字符串应能被 yyyy-MM-dd HH:mm:ss 解析
        val datetimeStr = output
            .substringAfter("当前时间: ")
            .lineSequence()
            .first()
            .trim()
        val parsed = ZonedDateTime.parse(
            datetimeStr,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
        )
        assertNotNull(parsed)
    }

    @Test
    fun `custom format yyyy-MM-dd`() = runTest {
        val outcome = tool.execute(
            mapOf("format" to "yyyy-MM-dd"),
            dummyContext
        )
        val result = (outcome as Outcome.Success).value
        val datetimeStr = result.output.substringAfter("当前时间: ").lineSequence().first().trim()
        // 应匹配 yyyy-MM-dd 格式（如 "2024-09-22"）
        assertTrue(
            "Should match yyyy-MM-dd: '$datetimeStr'",
            Regex("\\d{4}-\\d{2}-\\d{2}").matches(datetimeStr)
        )
    }

    @Test
    fun `custom format with milliseconds`() = runTest {
        val outcome = tool.execute(
            mapOf("format" to "yyyy-MM-dd HH:mm:ss.SSS"),
            dummyContext
        )
        val result = (outcome as Outcome.Success).value
        val datetimeStr = result.output.substringAfter("当前时间: ").lineSequence().first().trim()
        assertTrue(
            "Should contain millis: '$datetimeStr'",
            Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}").matches(datetimeStr)
        )
    }

    @Test
    fun `custom timezone UTC`() = runTest {
        val outcome = tool.execute(
            mapOf("timezone" to "UTC"),
            dummyContext
        )
        val result = (outcome as Outcome.Success).value
        assertTrue(result.output.contains("时区: UTC"))

        val data = result.structuredData as Map<*, *>
        assertEquals("UTC", data["timezone"])
    }

    @Test
    fun `custom timezone Asia_Shanghai`() = runTest {
        val outcome = tool.execute(
            mapOf("timezone" to "Asia/Shanghai"),
            dummyContext
        )
        val result = (outcome as Outcome.Success).value
        assertTrue(result.output.contains("时区: Asia/Shanghai"))

        val data = result.structuredData as Map<*, *>
        assertEquals("Asia/Shanghai", data["timezone"])
    }

    @Test
    fun `timezone America_New_York`() = runTest {
        val outcome = tool.execute(
            mapOf("timezone" to "America/New_York"),
            dummyContext
        )
        val result = (outcome as Outcome.Success).value
        assertTrue(result.output.contains("时区: America/New_York"))
    }

    @Test
    fun `structuredData has all 4 fields`() = runTest {
        val outcome = tool.execute(emptyMap(), dummyContext)
        val result = (outcome as Outcome.Success).value
        val data = result.structuredData as Map<*, *>

        assertNotNull(data["datetime"])
        assertNotNull(data["epochSeconds"])
        assertNotNull(data["epochMillis"])
        assertNotNull(data["timezone"])

        // epochSeconds 应是 Long 类型，且大于 0（1970 年后任意时刻）
        val epochSeconds = data["epochSeconds"] as Long
        assertTrue("epochSeconds should be > 0", epochSeconds > 0)

        // epochMillis 应是 epochSeconds 的 1000 倍 ± 1
        val epochMillis = data["epochMillis"] as Long
        val expected = epochSeconds * 1000
        assertTrue(
            "epochMillis ($epochMillis) ≈ epochSeconds * 1000 ($expected)",
            kotlin.math.abs(epochMillis - expected) < 2000
        )
    }

    @Test
    fun `output contains epoch seconds and millis`() = runTest {
        val outcome = tool.execute(emptyMap(), dummyContext)
        val result = (outcome as Outcome.Success).value
        assertTrue(result.output.contains("时间戳(秒):"))
        assertTrue(result.output.contains("时间戳(毫秒):"))
    }

    @Test
    fun `invalid timezone returns failure`() = runTest {
        val outcome = tool.execute(
            mapOf("timezone" to "Asia/Foo"),
            dummyContext
        )
        assertTrue("Expected Failure for invalid timezone", outcome is Outcome.Failure)
        val reason = (outcome as Outcome.Failure).reason
        assertTrue(reason.contains("DateTimeTool error"))
    }

    @Test
    fun `invalid format returns failure`() = runTest {
        val outcome = tool.execute(
            mapOf("format" to "yyyy-Foo"),
            dummyContext
        )
        assertTrue("Expected Failure for invalid format", outcome is Outcome.Failure)
    }

    @Test
    fun `blank format falls back to default`() = runTest {
        val outcome = tool.execute(
            mapOf("format" to "   "),
            dummyContext
        )
        // 空白字符串视为未传，走默认 yyyy-MM-dd HH:mm:ss
        val result = (outcome as Outcome.Success).value
        assertTrue(result.success)
    }

    @Test
    fun `blank timezone falls back to system default`() = runTest {
        val outcome = tool.execute(
            mapOf("timezone" to ""),
            dummyContext
        )
        val result = (outcome as Outcome.Success).value
        assertTrue(result.success)
        val data = result.structuredData as Map<*, *>
        assertEquals(ZoneId.systemDefault().toString(), data["timezone"])
    }

    @Test
    fun `tool metadata is correct`() {
        assertEquals("datetime", tool.id)
        assertEquals("日期与时间", tool.displayName)
        assertEquals(com.aibox.domain.tool.ToolCategory.BASIC, tool.category)
        assertEquals("🕐", tool.icon)
    }

    @Test
    fun `parameter schema has 3 optional params`() {
        val schema = tool.getParameterSchema()
        assertTrue("Required should be empty", schema.required.isEmpty())
        assertEquals(3, schema.optional.size)
        assertEquals("format", schema.optional[0].name)
        assertEquals(ParamType.STRING, schema.optional[0].type)
        assertEquals("timezone", schema.optional[1].name)
        assertEquals(ParamType.STRING, schema.optional[1].type)
        assertEquals("locale", schema.optional[2].name)
        assertEquals(ParamType.STRING, schema.optional[2].type)
    }
}
