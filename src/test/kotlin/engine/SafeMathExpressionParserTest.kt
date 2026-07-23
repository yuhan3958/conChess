package engine

import com.yuhan8954.engine.parser.ParseErrorCode
import com.yuhan8954.engine.parser.ParseResult
import com.yuhan8954.engine.parser.SafeMathExpressionParser
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SafeMathExpressionParserTest {
    private val parser = SafeMathExpressionParser()

    @Test
    fun `parses allowed arithmetic and constants`() {
        assertValue("3", 3.0)
        assertValue("1/2", 0.5)
        assertValue("sqrt(2)", sqrt(2.0))
        assertValue("sqrt(5)/2", sqrt(5.0) / 2.0)
        assertValue("pi/2", PI / 2.0)
        assertValue("(2 + sqrt(3))/4", (2.0 + sqrt(3.0)) / 4.0)
        assertValue("2^3", 8.0)
    }

    @Test
    fun `rejects unsafe and invalid input`() {
        assertFailure("", ParseErrorCode.EMPTY_EXPRESSION)
        assertFailure("abc", ParseErrorCode.UNKNOWN_SYMBOL)
        assertFailure("sin(1)", ParseErrorCode.UNSUPPORTED_FUNCTION)
        assertFailure("sqrt(-1)", ParseErrorCode.NON_REAL_RESULT)
        assertFailure("1/0", ParseErrorCode.NON_FINITE_RESULT)
        assertFailure("java.lang.Runtime.getRuntime()", ParseErrorCode.UNKNOWN_SYMBOL)
        assertFailure("(".repeat(40) + "1" + ")".repeat(40), ParseErrorCode.EXPRESSION_TOO_COMPLEX)
        assertFailure("1".repeat(129), ParseErrorCode.EXPRESSION_TOO_LONG)
    }

    private fun assertValue(expression: String, expected: Double) {
        val result = assertIs<ParseResult.Success>(parser.parse(expression))
        assertTrue(kotlin.math.abs(result.value - expected) < 1e-7)
    }

    private fun assertFailure(expression: String, code: ParseErrorCode) {
        val result = assertIs<ParseResult.Failure>(parser.parse(expression))
        assertEquals(code, result.code)
    }
}
