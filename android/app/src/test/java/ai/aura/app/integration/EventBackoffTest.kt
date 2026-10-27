package ai.aura.app.integration

import org.junit.Assert.assertEquals
import org.junit.Test

class EventBackoffTest {
    @Test fun growsAndCaps() {
        assertEquals(1000L, EventBackoff.delayMillis(0))
        assertEquals(1000L, EventBackoff.delayMillis(1))
        assertEquals(2000L, EventBackoff.delayMillis(2))
        assertEquals(4000L, EventBackoff.delayMillis(3))
        assertEquals(8000L, EventBackoff.delayMillis(4))
        assertEquals(15000L, EventBackoff.delayMillis(5))
        assertEquals(15000L, EventBackoff.delayMillis(500))
    }
}
