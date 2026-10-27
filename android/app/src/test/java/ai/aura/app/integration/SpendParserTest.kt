package ai.aura.app.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpendParserTest {
    @Test fun mapsKnownFields() {
        val summary = SpendParser.parse(
            listOf(mapOf("provider" to "claude", "model" to "sonnet", "today_usd" to 0.42, "month_usd" to 3))
        )
        val line = summary.lines.single()
        assertEquals("claude", line.provider)
        assertEquals("sonnet", line.model)
        assertEquals(0.42, line.todayUsd!!, 0.0001)
        assertEquals(3.0, line.monthUsd!!, 0.0001)
    }

    @Test fun missingOrInvalidValuesAreNotInformedNeverZero() {
        val summary = SpendParser.parse(
            listOf(
                mapOf("provider" to "codex"),
                mapOf("provider" to "api", "today_usd" to -1.0, "month_usd" to "abc"),
                mapOf("provider" to "x", "today_usd" to Double.NaN, "month_usd" to null),
            )
        )
        assertEquals(3, summary.lines.size)
        summary.lines.forEach {
            assertNull(it.todayUsd)
            assertNull(it.monthUsd)
        }
        assertNull(summary.lines[0].model)
    }

    @Test fun acceptsStringNumbersAndAlternateKeys() {
        val line = SpendParser.parse(
            listOf(mapOf("name" to "gemini", "today" to "1.5", "month" to 10.0))
        ).lines.single()
        assertEquals("gemini", line.provider)
        assertEquals(1.5, line.todayUsd!!, 0.0001)
        assertEquals(10.0, line.monthUsd!!, 0.0001)
    }

    @Test fun skipsRowsWithoutProviderAndHandlesEmpty() {
        assertEquals(0, SpendParser.parse(emptyList()).lines.size)
        assertEquals(0, SpendParser.parse(listOf(mapOf("model" to "haiku"), mapOf("provider" to "  "))).lines.size)
    }
}
