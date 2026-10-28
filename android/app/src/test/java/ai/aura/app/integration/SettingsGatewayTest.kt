package ai.aura.app.integration

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsGatewayTest {
    private class Call(val path: String, val method: String, val body: JSONObject?)

    private fun gateway(calls: MutableList<Call>, reply: (String, JSONObject?) -> JSONObject) =
        RealSettingsGateway { path, method, body -> calls += Call(path, method, body); reply(path, body) }

    @Test fun `le a autonomia atual do motor`() = runTest {
        val calls = mutableListOf<Call>()
        val g = gateway(calls) { _, _ -> JSONObject().put("autonomy", "ask_destructive") }
        assertEquals(Autonomy.AskDestructive, g.autonomy())
        assertEquals("/v1/settings/autonomy", calls.single().path)
        assertEquals("GET", calls.single().method)
    }

    @Test fun `grava a autonomia com POST e devolve o valor do motor`() = runTest {
        val calls = mutableListOf<Call>()
        val g = gateway(calls) { _, b -> JSONObject().put("autonomy", b!!.getString("autonomy")) }
        assertEquals(Autonomy.AskAll, g.setAutonomy(Autonomy.AskAll))
        assertEquals("POST", calls.single().method)
        assertEquals("ask_all", calls.single().body!!.getString("autonomy"))
    }

    @Test fun `valor desconhecido cai em Total, o padrao`() = runTest {
        val g = gateway(mutableListOf()) { _, _ -> JSONObject().put("autonomy", "???") }
        assertEquals(Autonomy.Total, g.autonomy())
    }

    @Test fun `trava de custo le e grava`() = runTest {
        val calls = mutableListOf<Call>()
        val g = gateway(calls) { _, b -> JSONObject().put("cost_lock", b?.optBoolean("cost_lock", true) ?: true) }
        assertTrue(g.costLock())
        assertEquals(false, g.setCostLock(false))
        assertEquals("/v1/settings/cost_lock", calls.last().path)
        assertEquals(false, calls.last().body!!.getBoolean("cost_lock"))
    }

    @Test fun `o falso guarda os valores e pode falhar`() = runTest {
        val f = FakeSettingsGateway()
        assertEquals(Autonomy.Total, f.autonomy())
        assertTrue(f.costLock())
        f.setAutonomy(Autonomy.AskAll)
        assertEquals(Autonomy.AskAll, f.autonomy())
        f.down = true
        runCatching { f.autonomy() }.let { assertTrue(it.isFailure) }
    }
}
