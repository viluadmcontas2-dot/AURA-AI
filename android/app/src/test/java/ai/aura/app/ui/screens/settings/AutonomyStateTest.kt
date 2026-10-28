package ai.aura.app.ui.screens.settings

import ai.aura.app.integration.Autonomy
import ai.aura.app.integration.FakeSettingsGateway
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomyStateTest {
    @Test fun `abre com o valor do motor e grava no cache local`() = runTest {
        val gw = FakeSettingsGateway(autonomy = Autonomy.AskAll, costLock = false)
        val cache = MemorySettingsCache()
        val s = AutonomyState(gw, cache, this)
        s.reload(); advanceUntilIdle()
        assertEquals(Autonomy.AskAll, s.autonomy)
        assertFalse(s.costLock)
        assertFalse(s.offline)
        assertEquals("ask_all", cache.get("autonomy"))
    }

    @Test fun `sem motor mostra o ultimo valor local`() = runTest {
        val cache = MemorySettingsCache().apply { put("autonomy", "ask_destructive"); put("cost_lock", "false") }
        val gw = FakeSettingsGateway().apply { down = true }
        val s = AutonomyState(gw, cache, this)
        s.reload(); advanceUntilIdle()
        assertEquals(Autonomy.AskDestructive, s.autonomy)
        assertFalse(s.costLock)
        assertTrue(s.offline)
    }

    @Test fun `sem motor e sem cache usa os padroes`() = runTest {
        val s = AutonomyState(FakeSettingsGateway().apply { down = true }, MemorySettingsCache(), this)
        s.reload(); advanceUntilIdle()
        assertEquals(Autonomy.Total, s.autonomy)
        assertTrue(s.costLock)
    }

    @Test fun `trocar a autonomia funciona offline e sincroniza quando o motor volta`() = runTest {
        val gw = FakeSettingsGateway().apply { down = true }
        val cache = MemorySettingsCache()
        val s = AutonomyState(gw, cache, this)
        s.reload(); advanceUntilIdle()
        s.chooseAutonomy(Autonomy.AskAll); advanceUntilIdle()
        assertEquals(Autonomy.AskAll, s.autonomy)
        assertEquals("ask_all", cache.get("autonomy"))
        assertTrue(s.pendingSync)
        gw.down = false
        s.reload(); advanceUntilIdle()
        assertEquals(Autonomy.AskAll, gw.autonomy())
        assertFalse(s.pendingSync)
        assertFalse(s.offline)
    }

    @Test fun `interruptor de custo chama o motor`() = runTest {
        val gw = FakeSettingsGateway()
        val s = AutonomyState(gw, MemorySettingsCache(), this)
        s.reload(); advanceUntilIdle()
        s.toggleCostLock(false); advanceUntilIdle()
        assertFalse(gw.costLock())
        assertFalse(s.costLock)
    }
}
