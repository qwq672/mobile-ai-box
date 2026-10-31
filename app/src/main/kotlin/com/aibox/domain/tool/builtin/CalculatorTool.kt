package com.aibox.domain.tool.builtin

import com.aibox.domain.tool.ParamSpec
import com.aibox.domain.tool.ParamType
import com.aibox.domain.tool.Tool
import com.aibox.domain.tool.ToolCategory
import com.aibox.domain.tool.ToolContext
import com.aibox.domain.tool.ToolParameterSchema
import com.aibox.domain.tool.ToolResult
import com.aibox.util.log.LogSystem
import com.aibox.util.outcome.Outcome
import kotlin.math.abs

/**
 * CalculatorTool（B2 基础工具）。
 *
 * 计算数学表达式，零第三方依赖（用 [ExpressionParser] 自实现递归下降解析器）。
 *
 * - 工具 ID：`calculator`（默认启用）
 * - 分类：[ToolCategory.BASIC]，自动执行，无副作用
 *
 * 支持的语法：
 * - 运算符：`+ - * / % ^`（^ 右结合，2^3^2 = 2^(3^2) = 512）
 * - 括号：`( )`
 * - 常量：`pi`（圆周率）`e`（自然底数）
 * - 函数（弧度）：`sqrt(x)` `sin(x)` `cos(x)` `tan(x)` `log(x)`（log10）`ln(x)` `abs(x)` `exp(x)` `pow(x,y)`
 *
 * 输出策略：
 * - 整数结果去掉 `.0` 后缀（避免 LLM 误以为浮点数；2 + 3 = 5 而非 5.0）
 * - 浮点结果保留最多 10 位有效数字（避免 IEEE 754 误差的长尾，如 0.1 + 0.2 = 0.30000000000000004 → 0.3）
 *
 * 失败场景：
 * - 表达式为空 → [Outcome.Failure] ("Missing expression")
 * - 语法错误（未知字符、未闭合括号、未知函数）→ [Outcome.Failure]
 * - 数学错误（除零、负数开方、log 非正数）→ [Outcome.Failure]
 * - 溢出（pow 大数）→ [Outcome.Failure]
 */
class CalculatorTool : Tool {
    override val id: String = "calculator"
    override val displayName: String = "计算器"
    override val description: String =
        "计算数学表达式。支持 + - * / % ^ 括号 函数（sqrt/sin/cos/tan/log/ln/abs/exp/pow）常量（pi/e）。"
    override val category: ToolCategory = ToolCategory.BASIC
    override val icon: String = "🧮"

    override fun getParameterSchema(): ToolParameterSchema = ToolParameterSchema(
        required = listOf(
            ParamSpec(
                name = "expression",
                type = ParamType.STRING,
                description = "数学表达式,如 2 + 3 * 4 或 sqrt(16) 或 pow(2, 10)"
            )
        ),
        optional = emptyList()
    )

    override suspend fun execute(
        params: Map<String, Any>,
        context: ToolContext
    ): Outcome<ToolResult> {
        return try {
            val expression = (params["expression"] as? String)?.takeIf { it.isNotBlank() }
                ?: return Outcome.Failure("Missing expression")

            val calculator = ExpressionParser()
            val raw = calculator.evaluate(expression)
            val display = formatResult(raw)

            val output = "$expression = $display"
            LogSystem.debug(TAG, "Calculator: '$expression' → $display (raw=$raw)")

            Outcome.Success(
                ToolResult(
                    success = true,
                    output = output,
                    structuredData = mapOf(
                        "expression" to expression,
                        "result" to display,
                        "raw" to raw
                    ),
                    executionTimeMs = 0
                )
            )
        } catch (e: Throwable) {
            LogSystem.error(TAG, "Calculator error: ${e.message}", e)
            Outcome.Failure("CalculatorTool error: ${e.message}", e)
        }
    }

    /**
     * 把 Double 结果格式化为对 LLM 友好的字符串。
     *
     * - 整数值（如 5.0）→ "5"
     * - NaN / Infinity → "NaN" / "Infinity"（让 LLM 知道边界，而非崩溃）
     * - 浮点数 → 截断到 10 位有效数字，去除尾 0
     */
    private fun formatResult(value: Double): String {
        val absValue = abs(value)
        // 整数值去 .0 后缀
        if (!value.isNaN() && !value.isInfinite() && absValue < 1e15 && value == value.toLong().toDouble()) {
            return value.toLong().toString()
        }
        // NaN / Infinity 原样输出
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
        // 10 位有效数字，去尾 0
        val formatted = String.format("%.10f", value).trimEnd('0').trimEnd('.')
        // 防止 "-0" 出现
        return if (formatted == "-0") "0" else formatted
    }

    companion object {
        private const val TAG = "aibox.tool.calculator"
    }
}
