package com.yuhan8954.engine.parser

/** Parses a safe mathematical expression into one finite real number. */
interface MathExpressionParser {
    fun parse(expression: String): ParseResult
}

/** Result from safe expression parsing. */
sealed interface ParseResult {
    data class Success(
        val originalExpression: String,
        val normalizedExpression: String,
        val value: Double,
    ) : ParseResult

    data class Failure(
        val code: ParseErrorCode,
        val message: String,
    ) : ParseResult
}

/** Machine-readable parser error code. */
enum class ParseErrorCode {
    EMPTY_EXPRESSION,
    INVALID_SYNTAX,
    UNKNOWN_SYMBOL,
    UNSUPPORTED_FUNCTION,
    NON_REAL_RESULT,
    NON_FINITE_RESULT,
    EXPRESSION_TOO_COMPLEX,
    EXPRESSION_TOO_LONG,
}
