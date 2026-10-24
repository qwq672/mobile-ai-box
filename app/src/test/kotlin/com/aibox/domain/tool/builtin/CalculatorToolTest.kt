package com.aibox.domain.tool.builtin

import com.aibox.domain.tool.DeviceInfo
import com.aibox.domain.tool.ToolContext
import com.aibox.util.outcome.Outcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CalculatorTool] 单元测试（B2 基础工具）。
 *
 * 覆盖：
 * - 整数结果去 .0 后缀（2 + 3 = 5 而非 5.0）
 * - 浮点结果保留 10 位有效数字（0.1 + 0.2 = 0.3 而非 0.30000000000000004）
 * - 错误输入：空表达式 / 缺失参数 / 语法错误 / 数学错误
 * - ToolResult.structuredData 字段（expression / result / raw）
 *
 * 通过 [Tool.execute] 公共 API 测试，间接覆盖 [ExpressionParser] +
 * [CalculatorTool.formatResult] 私有方法。
 *
 * 注：[CalculatorTool] 是纯 Kotlin，无 Android 依赖；[ToolContext] /
 * [DeviceInfo] 也是纯 data class，可在 JVM 测试环境直接构造。
 */
class CalculatorToolTest {

    private val tool = CalculatorTool()

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
    fun `integer result has no decimal suffix`() = runTest {
        val outcome = tool.execute(mapOf("expression" to "2 + 3"), dummyContext)
        val result = (outcome as Outcome.Success).value
        assertTrue(result.success)
        // 输出格式：表达式 = 结果
        assertEquals("2 + 3 = 5", result.output)
        // structuredData
        val data = result.structuredData as Map<*, *>
        assertEquals("2 + 3", data["expression"])
        assertEquals("5", data["result"])
        assertEquals(5.0, data["raw"] as Double, 1e-9)
    }

    @Test
    fun `float result is rounded to 10 significant digits`() = runTest {
        // 经典浮点误差：0.1 + 0.2 = 0.30000000000000004
        val outcome = tool.execute(mapOf("expression" to "0.1 + 0.2"), dummyContext)
        val result = (outcome as Outcome.Success).value
        // 期望 0.3 而非 0.30000000000000004
        assertEquals("0.3", result.output.substringAfter("= "))
    }

    @Test
    fun `negative integer result`() = runTest {
        val outcome = tool.execute(mapOf("expression" to "3 - 10"), dummyContext)
        val result = (outcome as Outcome.Success).value
        assertEquals("3 - 10 = -7", result.output)
    }

    @Test
    fun `complex expression with parentheses`() = runTest {
        val outcome = tool.execute(mapOf("expression" to "(2 + 3) * 4"), dummyContext)
        val result = (outcome as Outcome.Success).value
        assertEquals("(2 + 3) * 4 = 20", result.output)
    }

    @Test
    fun `sqrt function result`() = runTest {
        val outcome = tool.execute(mapOf("expression" to "sqrt(16)"), dummyContext)
        val result = (outcome as Outcome.Success).value
        assertEquals("sqrt(16) = 4", result.output)
    }

    @Test
    fun `pi constant`() = runTest {
        val outcome = tool.execute(mapOf("expression" to "pi"), dummyContext)
        val result = (outcome as Outcome.Success).value
        // pi 是无理数，输出应保留小数位
        assertTrue(result.output.startsWith("pi = 3.14"))
    }

    @Test
    fun `empty expression returns failure`() = runTest {
        val outcome = tool.execute(mapOf("expression" to ""), dummyContext)
        assertTrue(outcome is Outcome.Failure)
        assertEquals("Missing expression", (outcome as Outcome.Failure).reason)
    }

    @Test
    fun `missing expression param returns failure`() = runTest {
        val outcome = tool.execute(emptyMap(), dummyContext)
        assertTrue(outcome is Outcome.Failure)
        assertEquals("Missing expression", (outcome as Outcome.Failure).reason)
    }

    @Test
    fun `blank expression returns failure`() = runTest {
        val outcome = tool.execute(mapOf("expression" to "   "), dummyContext)
        assertTrue(outcome is Outcome.Failure)
        assertEquals("Missing expression", (outcome as Outcome.Failure).reason)
    }

    @Test
    fun `syntax error returns failure`() = runTest {
        val outcome = tool.execute(mapOf("expression" to "2 + "), dummyContext)
        assertTrue(outcome is Outcome.Failure)
        val reason = (outcome as Outcome.Failure).reason
        assertTrue("Should contain 'CalculatorTool error': $reason", reason.contains("CalculatorTool error"))
    }

    @Test
    fun `division by zero returns failure`() = runTest {
        val outcome = tool.execute(mapOf("expression" to "1 / 0"), dummyContext)
        assertTrue(outcome is Outcome.Failure)
    }

    @Test
    fun `unknown function returns failure`() = runTest {
        val outcome = tool.execute(mapOf("expression" to "foobar(1)"), dummyContext)
        assertTrue(outcome is Outcome.Failure)
    }

    @Test
    fun `tool metadata is correct`() {
        assertEquals("calculator", tool.id)
        assertEquals("计算器", tool.displayName)
        assertEquals(com.aibox.domain.tool.ToolCategory.BASIC, tool.category)
        assertEquals("🧮", tool.icon)
    }

    @Test
    fun `parameter schema has required expression`() {
        val schema = tool.getParameterSchema()
        assertEquals(1, schema.required.size)
        assertEquals("expression", schema.required[0].name)
        assertEquals(com.aibox.domain.tool.ParamType.STRING, schema.required[0].type)
        // 无可选参数
        assertTrue(schema.optional.isEmpty())
    }
}
