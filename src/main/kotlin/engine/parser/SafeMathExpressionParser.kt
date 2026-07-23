package com.yuhan8954.engine.parser

import com.yuhan8954.engine.model.normalizeExpressionText
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sqrt

/** A recursive-descent parser for a small, safe real-valued math grammar. */
class SafeMathExpressionParser(
    private val maxLength: Int = 128,
    private val maxNodes: Int = 160,
    private val maxDepth: Int = 32,
) : MathExpressionParser {
    override fun parse(expression: String): ParseResult {
        val original = expression
        val normalized = normalizeExpressionText(expression)
        if (normalized.isEmpty()) {
            return ParseResult.Failure(ParseErrorCode.EMPTY_EXPRESSION, "Expression is empty.")
        }
        if (normalized.length > maxLength) {
            return ParseResult.Failure(ParseErrorCode.EXPRESSION_TOO_LONG, "Expression is too long.")
        }
        return try {
            val parser = Parser(normalized, maxNodes, maxDepth)
            val value = parser.parseExpression()
            if (!parser.isAtEnd()) {
                return ParseResult.Failure(ParseErrorCode.INVALID_SYNTAX, "Unexpected token at ${parser.position}.")
            }
            if (!value.isFinite()) {
                return ParseResult.Failure(ParseErrorCode.NON_FINITE_RESULT, "Expression did not produce a finite number.")
            }
            ParseResult.Success(original, normalized, value)
        } catch (failure: ParseFailure) {
            ParseResult.Failure(failure.code, failure.message ?: "Invalid expression.")
        }
    }

    private class Parser(
        private val input: String,
        private val maxNodes: Int,
        private val maxDepth: Int,
    ) {
        var position: Int = 0
            private set
        private var nodes: Int = 0

        fun isAtEnd(): Boolean = position >= input.length

        fun parseExpression(depth: Int = 0): Double {
            guard(depth)
            var value = parseTerm(depth + 1)
            while (!isAtEnd()) {
                value = when {
                    consume('+') -> value + parseTerm(depth + 1)
                    consume('-') -> value - parseTerm(depth + 1)
                    else -> return value
                }
                ensureFinite(value)
            }
            return value
        }

        private fun parseTerm(depth: Int): Double {
            guard(depth)
            var value = parsePower(depth + 1)
            while (!isAtEnd()) {
                value = when {
                    consume('*') -> value * parsePower(depth + 1)
                    consume('/') -> value / parsePower(depth + 1)
                    else -> return value
                }
                ensureFinite(value)
            }
            return value
        }

        private fun parsePower(depth: Int): Double {
            guard(depth)
            val base = parseUnary(depth + 1)
            if (!consume('^')) return base
            val exponent = parsePower(depth + 1)
            val value = base.pow(exponent)
            if (value.isNaN()) throw ParseFailure(ParseErrorCode.NON_REAL_RESULT, "Expression produced a non-real result.")
            ensureFinite(value)
            return value
        }

        private fun parseUnary(depth: Int): Double {
            guard(depth)
            return when {
                consume('+') -> parseUnary(depth + 1)
                consume('-') -> -parseUnary(depth + 1)
                else -> parsePrimary(depth + 1)
            }
        }

        private fun parsePrimary(depth: Int): Double {
            guard(depth)
            if (consume('(')) {
                val value = parseExpression(depth + 1)
                if (!consume(')')) throw ParseFailure(ParseErrorCode.INVALID_SYNTAX, "Missing closing parenthesis.")
                return value
            }
            if (peek()?.isDigit() == true || peek() == '.') return parseNumber()
            if (peek()?.isLetter() == true) return parseSymbolOrFunction(depth + 1)
            throw ParseFailure(ParseErrorCode.INVALID_SYNTAX, "Expected a number, symbol, function, or parenthesis.")
        }

        private fun parseNumber(): Double {
            countNode()
            val start = position
            while (peek()?.isDigit() == true) position++
            if (peek() == '.') {
                position++
                while (peek()?.isDigit() == true) position++
            }
            if (peek() == 'e' || peek() == 'E') {
                position++
                if (peek() == '+' || peek() == '-') position++
                if (peek()?.isDigit() != true) throw ParseFailure(ParseErrorCode.INVALID_SYNTAX, "Invalid exponent.")
                while (peek()?.isDigit() == true) position++
            }
            val value = input.substring(start, position).toDoubleOrNull()
                ?: throw ParseFailure(ParseErrorCode.INVALID_SYNTAX, "Invalid number.")
            ensureFinite(value)
            return value
        }

        private fun parseSymbolOrFunction(depth: Int): Double {
            countNode()
            val start = position
            while (peek()?.isLetter() == true) position++
            val symbol = input.substring(start, position).lowercase()
            if (consume('(')) {
                if (symbol != "sqrt") {
                    throw ParseFailure(ParseErrorCode.UNSUPPORTED_FUNCTION, "Unsupported function: $symbol.")
                }
                val argument = parseExpression(depth + 1)
                if (!consume(')')) throw ParseFailure(ParseErrorCode.INVALID_SYNTAX, "Missing function closing parenthesis.")
                if (argument < 0.0) throw ParseFailure(ParseErrorCode.NON_REAL_RESULT, "sqrt argument must be non-negative.")
                return sqrt(argument)
            }
            return when (symbol) {
                "pi" -> PI
                "e" -> E
                "nan", "infinity" -> throw ParseFailure(ParseErrorCode.NON_FINITE_RESULT, "Non-finite constants are not allowed.")
                else -> throw ParseFailure(ParseErrorCode.UNKNOWN_SYMBOL, "Unknown symbol: $symbol.")
            }
        }

        private fun guard(depth: Int) {
            if (depth > maxDepth) throw ParseFailure(ParseErrorCode.EXPRESSION_TOO_COMPLEX, "Expression nesting is too deep.")
        }

        private fun countNode() {
            nodes++
            if (nodes > maxNodes) throw ParseFailure(ParseErrorCode.EXPRESSION_TOO_COMPLEX, "Expression is too complex.")
        }

        private fun consume(char: Char): Boolean {
            if (peek() != char) return false
            position++
            return true
        }

        private fun peek(): Char? = input.getOrNull(position)

        private fun ensureFinite(value: Double) {
            if (!value.isFinite()) throw ParseFailure(ParseErrorCode.NON_FINITE_RESULT, "Expression did not produce a finite number.")
        }
    }
}

private class ParseFailure(val code: ParseErrorCode, override val message: String) : RuntimeException(message)
