package com.aibox.domain.tool.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.E
import kotlin.math.abs

/**
 * [ExpressionParser] 单元测试（B2 CalculatorTool 内部解析器）。
 *
 * 覆盖：
 * - 基础运算符（+ - * / % ^）
 * - 优先级与括号
 * - 一元运算符（负号 / 正号）
 * - 右结合 ^（2^3^2 = 2^(3^2) = 512）
 * - 函数（sqrt / sin / cos / tan / log / ln / abs / exp / pow）
 * - 常量（pi / e）
 * - 边界：空表达式 / 未知字符 / 未闭合括号 / 未知函数 / 参数个数不匹配
 * - 数学错误：除零 / 负数开方 / log 非正数
 *
 * 纯 Kotlin 逻辑，无 Android 依赖；环境无 Android SDK 也能跑。
 */
class ExpressionParserTest {

    private val parser = ExpressionParser()

    // ===================================================
    // 基础运算符
    // ===================================================

    @Test
    fun `simple addition`() {
        assertEquals(5.0, parser.evaluate("2 + 3"), 1e-9)
    }

    @Test
    fun `simple subtraction`() {
        assertEquals(-1.0, parser.evaluate("2 - 3"), 1e-9)
    }

    @Test
    fun `simple multiplication`() {
        assertEquals(12.0, parser.evaluate("3 * 4"), 1e-9)
    }

    @Test
    fun `simple division`() {
        assertEquals(2.5, parser.evaluate("5 / 2"), 1e-9)
    }

    @Test
    fun `modulo`() {
        assertEquals(1.0, parser.evaluate("7 % 3"), 1e-9)
    }

    @Test
    fun `power`() {
        assertEquals(8.0, parser.evaluate("2 ^ 3"), 1e-9)
    }

    // ===================================================
    // 优先级
    // ===================================================

    @Test
    fun `multiplication before addition`() {
        assertEquals(14.0, parser.evaluate("2 + 3 * 4"), 1e-9)
    }

    @Test
    fun `parentheses override precedence`() {
        assertEquals(20.0, parser.evaluate("(2 + 3) * 4"), 1e-9)
    }

    @Test
    fun `nested parentheses`() {
        assertEquals(4.0, parser.evaluate("((2 + 2))"), 1e-9)
        assertEquals(8.0, parser.evaluate("((2 + 2) * 2)"), 1e-9)
        assertEquals(16.0, parser.evaluate("(2 + (3 * (4 - 2)))"), 1e-9)
    }

    @Test
    fun `power is right-associative`() {
        // 2^3^2 = 2^(3^2) = 2^9 = 512
        assertEquals(512.0, parser.evaluate("2 ^ 3 ^ 2"), 1e-6)
    }

    @Test
    fun `power binds tighter than multiplication`() {
        // 3 * 2 ^ 2 = 3 * (2^2) = 3 * 4 = 12
        assertEquals(12.0, parser.evaluate("3 * 2 ^ 2"), 1e-9)
    }

    // ===================================================
    // 一元运算符
    // ===================================================

    @Test
    fun `unary minus`() {
        assertEquals(-5.0, parser.evaluate("-5"), 1e-9)
        assertEquals(-7.0, parser.evaluate("3 + -10"), 1e-9)
    }

    @Test
    fun `unary plus`() {
        assertEquals(5.0, parser.evaluate("+5"), 1e-9)
    }

    @Test
    fun `multiple unary minus`() {
        assertEquals(5.0, parser.evaluate("--5"), 1e-9)
        assertEquals(-5.0, parser.evaluate("---5"), 1e-9)
    }

    @Test
    fun `unary minus binds looser than power`() {
        // -3^2 = -(3^2) = -9（数学惯例）
        assertEquals(-9.0, parser.evaluate("-3 ^ 2"), 1e-9)
    }

    // ===================================================
    // 浮点数
    // ===================================================

    @Test
    fun `decimal number`() {
        assertEquals(3.14, parser.evaluate("3.14"), 1e-9)
    }

    @Test
    fun `leading dot decimal`() {
        assertEquals(0.5, parser.evaluate(".5"), 1e-9)
    }

    @Test
    fun `trailing dot decimal`() {
        // 5. = 5.0 — 行为允许；解析器把 5. 当成 5
        assertEquals(5.0, parser.evaluate("5."), 1e-9)
    }

    @Test
    fun `float arithmetic`() {
        assertEquals(0.5, parser.evaluate("1.5 - 1.0"), 1e-9)
        assertEquals(2.25, parser.evaluate("1.5 * 1.5"), 1e-9)
    }

    // ===================================================
    // 函数
    // ===================================================

    @Test
    fun `sqrt function`() {
        assertEquals(4.0, parser.evaluate("sqrt(16)"), 1e-9)
        assertEquals(2.0, parser.evaluate("sqrt(4)"), 1e-9)
    }

    @Test
    fun `sin function with pi`() {
        // sin(pi) ≈ 0（浮点误差内）
        assertTrue(abs(parser.evaluate("sin(pi)") - 0.0) < 1e-9)
    }

    @Test
    fun `cos function with pi`() {
        // cos(pi) = -1
        assertEquals(-1.0, parser.evaluate("cos(pi)"), 1e-9)
    }

    @Test
    fun `tan function`() {
        // tan(0) = 0
        assertEquals(0.0, parser.evaluate("tan(0)"), 1e-9)
    }

    @Test
    fun `log function is log10`() {
        // log(100) = 2
        assertEquals(2.0, parser.evaluate("log(100)"), 1e-9)
    }

    @Test
    fun `ln function is natural log`() {
        // ln(e) = 1
        assertEquals(1.0, parser.evaluate("ln(e)"), 1e-9)
    }

    @Test
    fun `abs function`() {
        assertEquals(5.0, parser.evaluate("abs(-5)"), 1e-9)
        assertEquals(5.0, parser.evaluate("abs(5)"), 1e-9)
    }

    @Test
    fun `exp function`() {
        // exp(0) = 1
        assertEquals(1.0, parser.evaluate("exp(0)"), 1e-9)
        // exp(1) = e
        assertEquals(E, parser.evaluate("exp(1)"), 1e-9)
    }

    @Test
    fun `pow function with 2 args`() {
        assertEquals(1024.0, parser.evaluate("pow(2, 10)"), 1e-9)
        assertEquals(1000.0, parser.evaluate("pow(10, 3)"), 1e-9)
    }

    @Test
    fun `function case insensitive`() {
        assertEquals(4.0, parser.evaluate("SQRT(16)"), 1e-9)
        assertEquals(4.0, parser.evaluate("Sqrt(16)"), 1e-9)
    }

    @Test
    fun `nested function calls`() {
        // sqrt(pow(3, 2) + pow(4, 2)) = sqrt(9 + 16) = sqrt(25) = 5
        assertEquals(5.0, parser.evaluate("sqrt(pow(3, 2) + pow(4, 2))"), 1e-9)
    }

    // ===================================================
    // 常量
    // ===================================================

    @Test
    fun `constant pi`() {
        assertEquals(PI, parser.evaluate("pi"), 1e-9)
    }

    @Test
    fun `constant e`() {
        assertEquals(E, parser.evaluate("e"), 1e-9)
    }

    @Test
    fun `constant in expression`() {
        assertEquals(2 * PI, parser.evaluate("2 * pi"), 1e-9)
        assertEquals(PI * PI, parser.evaluate("pi ^ 2"), 1e-9)
    }

    @Test
    fun `constant case insensitive`() {
        assertEquals(PI, parser.evaluate("PI"), 1e-9)
    }

    // ===================================================
    // 复杂表达式
    // ===================================================

    @Test
    fun `complex expression 1`() {
        // 2 + 3 * (4 - 1) = 2 + 9 = 11
        assertEquals(11.0, parser.evaluate("2 + 3 * (4 - 1)"), 1e-9)
    }

    @Test
    fun `complex expression 2`() {
        // sqrt(2^2 + 3^2) = sqrt(4 + 9) = sqrt(13)
        assertEquals(kotlin.math.sqrt(13.0), parser.evaluate("sqrt(2 ^ 2 + 3 ^ 2)"), 1e-9)
    }

    @Test
    fun `complex expression 3`() {
        // (1 + 2) * (3 + 4) = 3 * 7 = 21
        assertEquals(21.0, parser.evaluate("(1 + 2) * (3 + 4)"), 1e-9)
    }

    @Test
    fun `whitespace is ignored`() {
        assertEquals(5.0, parser.evaluate("  2  +  3  "), 1e-9)
        assertEquals(5.0, parser.evaluate("\t2\t+\t3\t"), 1e-9)
    }

    // ===================================================
    // 边界：错误输入
    // ===================================================

    @Test(expected = IllegalArgumentException::class)
    fun `empty expression throws`() {
        parser.evaluate("")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `whitespace-only expression throws`() {
        parser.evaluate("   ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown character throws`() {
        parser.evaluate("2 @ 3")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unclosed parenthesis throws`() {
        parser.evaluate("(2 + 3")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unmatched closing parenthesis throws`() {
        parser.evaluate("2 + 3)")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `trailing garbage throws`() {
        // 2 + 3 abc —— abc 是未知标识符，应在主入口检测到 EOF 时拒绝
        parser.evaluate("2 + 3 abc")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown function throws`() {
        parser.evaluate("foobar(1)")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown constant throws`() {
        parser.evaluate("xyzzy")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `wrong arg count throws`() {
        // sqrt 要求 1 个参数，传 2 个
        parser.evaluate("sqrt(1, 2)")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `pow with 1 arg throws`() {
        parser.evaluate("pow(2)")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `lone dot throws`() {
        parser.evaluate(".")
    }

    // ===================================================
    // 边界：数学错误
    // ===================================================

    @Test(expected = ArithmeticException::class)
    fun `division by zero throws`() {
        parser.evaluate("1 / 0")
    }

    @Test(expected = ArithmeticException::class)
    fun `modulo by zero throws`() {
        parser.evaluate("5 % 0")
    }

    @Test(expected = ArithmeticException::class)
    fun `sqrt of negative throws`() {
        parser.evaluate("sqrt(-1)")
    }

    @Test(expected = ArithmeticException::class)
    fun `log of zero throws`() {
        parser.evaluate("log(0)")
    }

    @Test(expected = ArithmeticException::class)
    fun `log of negative throws`() {
        parser.evaluate("log(-1)")
    }

    @Test(expected = ArithmeticException::class)
    fun `ln of negative throws`() {
        parser.evaluate("ln(-1)")
    }

    // ===================================================
    // parse() vs evaluate() 分离
    // ===================================================

    @Test
    fun `parse returns AST and evaluate walks it`() {
        val ast = parser.parse("1 + 2")
        val result = ast.evaluate()
        assertEquals(3.0, result, 1e-9)
    }

    @Test
    fun `ast can be evaluated multiple times`() {
        val ast = parser.parse("3 * 4")
        assertEquals(12.0, ast.evaluate(), 1e-9)
        assertEquals(12.0, ast.evaluate(), 1e-9)   // 再次求值，无副作用
    }

    @Test
    fun `ast toString is non-empty`() {
        val ast = parser.parse("2 + 3")
        assertTrue(ast.toString().isNotEmpty())
        assertFalse(ast.toString().isBlank())
    }
}
