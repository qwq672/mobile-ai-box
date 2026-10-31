package com.aibox.domain.tool.builtin

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.E
import kotlin.math.exp
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 递归下降表达式解析器（B2 CalculatorTool 内部用，无第三方依赖）。
 *
 * 支持的语法：
 * ```
 * expression := term (('+' | '-') term)*
 * term        := factor (('*' | '/' | '%') factor)*
 * factor      := unary ('^' factor)?                // ^ 右结合：2^3^2 = 2^(3^2) = 512
 * unary       := ('-' | '+') unary | primary         // -3^2 = -(3^2) = -9
 * primary     := number
 *              | constant
 *              | function '(' arglist ')'
 *              | '(' expression ')'
 * arglist     := expression (',' expression)*
 * ```
 *
 * 支持的运算符：+ - * / % ^
 *
 * 支持的常量：
 * - `pi`：圆周率（3.14159…）
 * - `e`：自然底数（2.71828…）
 *
 * 支持的函数：
 * - 单参：`sqrt(x)` `sin(x)` `cos(x)` `tan(x)` `log(x)`（log10）`ln(x)` `abs(x)`
 * - 双参：`pow(x, y)`（x 的 y 次方）
 *
 * 单位说明：所有三角函数参数为**弧度**（rad），与 kotlin.math 对齐。
 *
 * 错误处理：
 * - 解析失败抛 [IllegalArgumentException]，含行列信息（虽然单行输入但保留扩展点）
 * - 除零、负数开方等数学错误抛 [ArithmeticException]
 * - 调用方（[CalculatorTool]）应 try-catch 兜底转 [com.aibox.util.outcome.Outcome.Failure]
 *
 * 设计原则：
 * - 不依赖任何第三方库；纯 Kotlin，便于单测
 * - 解析与求值分离（[parse] 生成 AST，[evaluate] 走 AST 求值），便于未来扩展（如符号微分、AST 优化）
 * - 不引入 BigDecimal，直接用 Double（与 LLM 输入精度匹配；高精度场景由未来工具扩展）
 */
class ExpressionParser {

    /**
     * 解析并求值。
     *
     * 一行式 API，等价于 `parse(expr).evaluate()`。
     * 失败抛 [IllegalArgumentException]（语法错误）或 [ArithmeticException]（数学错误）。
     */
    fun evaluate(expression: String): Double {
        val ast = parse(expression)
        return ast.evaluate()
    }

    /**
     * 解析表达式为 AST。
     *
     * @throws IllegalArgumentException 语法错误（未知字符、未闭合括号、空表达式等）
     */
    fun parse(expression: String): AstNode {
        val tokens = Tokenizer(expression).tokenize()
        if (tokens.isEmpty()) {
            throw IllegalArgumentException("Empty expression")
        }
        val parser = Parser(tokens)
        val node = parser.parseExpression()
        // 检查是否消费完所有 token（避免 "1+2 abc" 这种被静默丢弃）
        if (parser.peek().type != TokenType.EOF) {
            throw IllegalArgumentException("Unexpected token at position ${parser.peek().position}: ${parser.peek()}")
        }
        return node
    }
}

// ===================================================
// AST 节点
// ===================================================

/** AST 节点基类。所有节点实现 [evaluate] 在给定上下文下求值。 */
sealed class AstNode {
    /** 求值。可能抛 [ArithmeticException]（运行时数学错误）。 */
    abstract fun evaluate(): Double
}

/** 数字字面量。 */
private class NumberNode(val value: Double) : AstNode() {
    override fun evaluate(): Double = value
    override fun toString(): String = value.toString()
}

/** 二元运算。 */
private class BinaryNode(
    val op: Char,
    val left: AstNode,
    val right: AstNode
) : AstNode() {
    override fun evaluate(): Double {
        val l = left.evaluate()
        val r = right.evaluate()
        return when (op) {
            '+' -> l + r
            '-' -> l - r
            '*' -> l * r
            '/' -> {
                if (r == 0.0) throw ArithmeticException("Division by zero")
                l / r
            }
            '%' -> {
                if (r == 0.0) throw ArithmeticException("Modulo by zero")
                l % r
            }
            '^' -> l.pow(r)
            else -> throw IllegalArgumentException("Unknown binary operator: $op")
        }
    }
    override fun toString(): String = "($left $op $right)"
}

/** 一元运算（仅 - / +）。 */
private class UnaryNode(val op: Char, val operand: AstNode) : AstNode() {
    override fun evaluate(): Double {
        val v = operand.evaluate()
        return if (op == '-') -v else v
    }
    override fun toString(): String = "$op$operand"
}

/** 函数调用。 */
private class FunctionNode(
    val name: String,
    val args: List<AstNode>
) : AstNode() {
    override fun evaluate(): Double {
        val evaluated = args.map { it.evaluate() }
        return when (name.lowercase()) {
            // 单参
            "sqrt" -> {
                requireArgCount(name, evaluated, 1)
                if (evaluated[0] < 0) throw ArithmeticException("sqrt of negative number")
                sqrt(evaluated[0])
            }
            "sin" -> { requireArgCount(name, evaluated, 1); sin(evaluated[0]) }
            "cos" -> { requireArgCount(name, evaluated, 1); cos(evaluated[0]) }
            "tan" -> { requireArgCount(name, evaluated, 1); tan(evaluated[0]) }
            "log" -> {
                requireArgCount(name, evaluated, 1)
                if (evaluated[0] <= 0) throw ArithmeticException("log of non-positive number")
                kotlin.math.log10(evaluated[0])
            }
            "ln" -> {
                requireArgCount(name, evaluated, 1)
                if (evaluated[0] <= 0) throw ArithmeticException("ln of non-positive number")
                kotlin.math.ln(evaluated[0])
            }
            "abs" -> { requireArgCount(name, evaluated, 1); abs(evaluated[0]) }
            "exp" -> { requireArgCount(name, evaluated, 1); exp(evaluated[0]) }
            // 双参
            "pow" -> {
                requireArgCount(name, evaluated, 2)
                runCatching { evaluated[0].pow(evaluated[1]) }
                    .getOrElse { throw ArithmeticException("pow overflow or invalid: ${evaluated[0]} ^ ${evaluated[1]}") }
            }
            else -> throw IllegalArgumentException("Unknown function: $name")
        }
    }

    private fun requireArgCount(name: String, args: List<Double>, expected: Int) {
        if (args.size != expected) {
            throw IllegalArgumentException("Function $name expects $expected arg(s), got ${args.size}")
        }
    }

    override fun toString(): String = "$name(${args.joinToString(", ")})"
}

/** 常量（pi / e）。 */
private class ConstantNode(val name: String) : AstNode() {
    override fun evaluate(): Double = when (name.lowercase()) {
        "pi" -> PI
        "e" -> E
        else -> throw IllegalArgumentException("Unknown constant: $name")
    }
    override fun toString(): String = name
}

// ===================================================
// Tokenizer
// ===================================================

private enum class TokenType {
    NUMBER, IDENT, PLUS, MINUS, STAR, SLASH, PERCENT, CARET,
    LPAREN, RPAREN, COMMA, EOF
}

private data class Token(
    val type: TokenType,
    val value: String,
    val position: Int
)

/**
 * 简单字符级 tokenizer。
 *
 * - 跳过空白
 * - 识别数字（含小数点；不支持科学计数法以保持简单，未来可扩展）
 * - 识别标识符（字母开头，含字母数字下划线）
 * - 单字符运算符直接映射
 *
 * @throws IllegalArgumentException 未知字符
 */
private class Tokenizer(private val src: String) {
    private var pos = 0

    fun tokenize(): List<Token> {
        val tokens = mutableListOf<Token>()
        while (pos < src.length) {
            val c = src[pos]
            when {
                c.isWhitespace() -> pos++
                c.isDigit() || c == '.' -> tokens.add(readNumber())
                c.isLetter() || c == '_' -> tokens.add(readIdentifier())
                c == '+' -> { tokens.add(Token(TokenType.PLUS, "+", pos)); pos++ }
                c == '-' -> { tokens.add(Token(TokenType.MINUS, "-", pos)); pos++ }
                c == '*' -> { tokens.add(Token(TokenType.STAR, "*", pos)); pos++ }
                c == '/' -> { tokens.add(Token(TokenType.SLASH, "/", pos)); pos++ }
                c == '%' -> { tokens.add(Token(TokenType.PERCENT, "%", pos)); pos++ }
                c == '^' -> { tokens.add(Token(TokenType.CARET, "^", pos)); pos++ }
                c == '(' -> { tokens.add(Token(TokenType.LPAREN, "(", pos)); pos++ }
                c == ')' -> { tokens.add(Token(TokenType.RPAREN, ")", pos)); pos++ }
                c == ',' -> { tokens.add(Token(TokenType.COMMA, ",", pos)); pos++ }
                else -> throw IllegalArgumentException("Unexpected character '$c' at position $pos")
            }
        }
        tokens.add(Token(TokenType.EOF, "", pos))
        return tokens
    }

    private fun readNumber(): Token {
        val start = pos
        val sb = StringBuilder()
        var hasDot = false
        while (pos < src.length) {
            val c = src[pos]
            if (c.isDigit()) {
                sb.append(c); pos++
            } else if (c == '.' && !hasDot) {
                sb.append(c); hasDot = true; pos++
            } else break
        }
        // 至少有一位数字（避免单独的 "." 被当成数字）；若 sb 只有 "." 抛错
        val raw = sb.toString()
        if (raw == "." || raw.isEmpty()) {
            throw IllegalArgumentException("Invalid number at position $start: '$raw'")
        }
        return Token(TokenType.NUMBER, raw, start)
    }

    private fun readIdentifier(): Token {
        val start = pos
        val sb = StringBuilder()
        while (pos < src.length) {
            val c = src[pos]
            if (c.isLetterOrDigit() || c == '_') {
                sb.append(c); pos++
            } else break
        }
        return Token(TokenType.IDENT, sb.toString(), start)
    }
}

// ===================================================
// Parser（递归下降）
// ===================================================

private class Parser(private val tokens: List<Token>) {
    private var index = 0

    fun peek(): Token = tokens[index]

    private fun consume(): Token {
        val t = tokens[index]
        if (t.type != TokenType.EOF) index++
        return t
    }

    private fun expect(type: TokenType, what: String): Token {
        val t = peek()
        if (t.type != type) {
            throw IllegalArgumentException("Expected $what at position ${t.position}, got '${t.value}'")
        }
        return consume()
    }

    /** expression := term (('+' | '-') term)* */
    fun parseExpression(): AstNode {
        var left = parseTerm()
        while (peek().type == TokenType.PLUS || peek().type == TokenType.MINUS) {
            val op = if (peek().type == TokenType.PLUS) '+' else '-'
            consume()
            val right = parseTerm()
            left = BinaryNode(op, left, right)
        }
        return left
    }

    /** term := factor (('*' | '/' | '%') factor)* */
    private fun parseTerm(): AstNode {
        var left = parseFactor()
        while (peek().type == TokenType.STAR
            || peek().type == TokenType.SLASH
            || peek().type == TokenType.PERCENT
        ) {
            val op = when (peek().type) {
                TokenType.STAR -> '*'
                TokenType.SLASH -> '/'
                TokenType.PERCENT -> '%'
                else -> error("unreachable")
            }
            consume()
            val right = parseFactor()
            left = BinaryNode(op, left, right)
        }
        return left
    }

    /** factor := unary ('^' factor)? — 右结合 */
    private fun parseFactor(): AstNode {
        val base = parseUnary()
        if (peek().type == TokenType.CARET) {
            consume()
            val exp = parseFactor() // 右结合：递归调本函数
            return BinaryNode('^', base, exp)
        }
        return base
    }

    /** unary := ('-' | '+') unary | primary */
    private fun parseUnary(): AstNode {
        if (peek().type == TokenType.MINUS || peek().type == TokenType.PLUS) {
            val op = if (peek().type == TokenType.MINUS) '-' else '+'
            consume()
            val operand = parseUnary()
            return UnaryNode(op, operand)
        }
        return parsePrimary()
    }

    /** primary := number | constant | function '(' arglist ')' | '(' expression ')' */
    private fun parsePrimary(): AstNode {
        val t = peek()
        return when (t.type) {
            TokenType.NUMBER -> {
                consume()
                val v = t.value.toDoubleOrNull()
                    ?: throw IllegalArgumentException("Invalid number literal: '${t.value}'")
                NumberNode(v)
            }
            TokenType.IDENT -> {
                consume()
                // 若紧跟 '(' 则视为函数调用
                if (peek().type == TokenType.LPAREN) {
                    consume() // '('
                    val args = parseArgList()
                    expect(TokenType.RPAREN, "')'")
                    FunctionNode(t.value, args)
                } else {
                    // 常量
                    ConstantNode(t.value)
                }
            }
            TokenType.LPAREN -> {
                consume() // '('
                val inner = parseExpression()
                expect(TokenType.RPAREN, "')'")
                inner
            }
            else -> {
                throw IllegalArgumentException(
                    "Unexpected token at position ${t.position}: '${t.value}' (type=${t.type})"
                )
            }
        }
    }

    /** arglist := expression (',' expression)* — 空参数列表返回 emptyList */
    private fun parseArgList(): List<AstNode> {
        if (peek().type == TokenType.RPAREN) return emptyList()
        val args = mutableListOf(parseExpression())
        while (peek().type == TokenType.COMMA) {
            consume()
            args.add(parseExpression())
        }
        return args
    }
}
