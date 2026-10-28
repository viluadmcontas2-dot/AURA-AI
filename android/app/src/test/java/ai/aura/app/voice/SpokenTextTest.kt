package ai.aura.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpokenTextTest {
    @Test fun `usa a primeira hipotese sem espacos nas pontas`() =
        assertEquals("criar um app", SpokenText.from(arrayListOf("  criar um app ", "outra")))

    @Test fun `lista nula ou vazia nao vira texto`() {
        assertNull(SpokenText.from(null))
        assertNull(SpokenText.from(arrayListOf()))
    }

    @Test fun `hipotese em branco nao vira texto`() = assertNull(SpokenText.from(arrayListOf("   ")))
}
