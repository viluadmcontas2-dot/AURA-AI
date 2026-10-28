package ai.aura.app.integration

import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Transporte de mentira: guarda cada chamada e responde com o JSON combinado (ou lança o erro do motor). */
private class FakeTransport(val reply: (String, String, JSONObject?) -> JSONObject) : ReformTransport {
    data class Call(val path: String, val method: String, val body: JSONObject?, val timeout: Int)
    val calls = mutableListOf<Call>()
    override suspend fun request(path: String, method: String, body: JSONObject?, readTimeoutMs: Int): JSONObject {
        calls += Call(path, method, body, readTimeoutMs)
        return reply(path, method, body)
    }
}

class ReformGatewayTest {
    private fun gateway(reply: (String, String, JSONObject?) -> JSONObject) =
        FakeTransport(reply).let { it to RealReformGateway(it) }

    @Test
    fun `create manda o caminho do arquivo, o modo e a meta`() = runTest {
        val (http, gw) = gateway { _, _, _ ->
            JSONObject().put("id", "a1b2c3d4e5f6").put("scope", JSONObject().put("apk_path", "/p/x.apk").put("mode", "visual_flows").put("seq", 1))
        }
        val created = gw.create("/p/x.apk", "visual_flows", 90, carMode = true)
        assertEquals("a1b2c3d4e5f6", created.id)
        assertEquals("/p/x.apk", created.apkPath)
        val call = http.calls.single()
        assertEquals("/v1/reform", call.path)
        assertEquals("POST", call.method)
        assertEquals("/p/x.apk", call.body!!.getString("apk_path"))
        assertEquals("visual_flows", call.body!!.getString("mode"))
        assertEquals(90, call.body!!.getInt("goal"))
    }

    @Test
    fun `passo tools em dados moveis vira espera de Wi-Fi sem erro e traz o aviso de download`() = runTest {
        val (http, gw) = gateway { _, _, _ ->
            JSONObject().put("id", "a1b2c3d4e5f6").put("step", "tools").put("already_done", false).put("seq", JSONObject.NULL)
                .put("result", JSONObject().put("state", "waiting_wifi").put("waiting_step", "jdk")
                    .put("steps", JSONArray(listOf("node")))
                    .put("download_notices", JSONArray().put(JSONObject().put("step", "jdk").put("mb", 180)))
                    .put("message", "Conecte ao Wi-Fi"))
        }
        val r = gw.runStep("a1b2c3d4e5f6", ReformStep.Tools)
        assertTrue(r.waitingWifi)
        assertEquals("jdk", r.waitingStep)
        assertEquals(listOf("node"), r.doneSteps)
        assertEquals(listOf(ReformDownloadNotice("jdk", 180)), r.notices)
        assertNull(r.seq)
        val call = http.calls.single()
        assertEquals("/v1/reform/a1b2c3d4e5f6/steps/tools", call.path)
        assertTrue("instalar leva minutos: leitura longa", call.timeout >= 600_000)
    }

    @Test
    fun `passo repetido (already_done) nao traz resultado e nao quebra`() = runTest {
        val (_, gw) = gateway { _, _, _ ->
            JSONObject().put("id", "a1b2c3d4e5f6").put("step", "tools").put("already_done", true).put("seq", 4)
        }
        val r = gw.runStep("a1b2c3d4e5f6", ReformStep.Tools)
        assertTrue(r.alreadyDone)
        assertFalse(r.waitingWifi)
        assertEquals(4L, r.seq)
    }

    @Test
    fun `resumo traz os medidores do motor e fidelidade nula continua nula`() = runTest {
        val (http, gw) = gateway { _, _, _ ->
            JSONObject().put("id", "a1b2c3d4e5f6").put("chain_ok", true)
                .put("scope", JSONObject().put("apk_path", "/p/x.apk").put("mode", "visual_flows").put("goal", 90).put("seq", 1))
                .put("reversal", JSONObject().put("pct", 68.4).put("seq", 9))
                .put("rebuild", JSONObject().put("pct", 35).put("seq", 12))
                .put("fidelity", JSONObject.NULL)
                .put("model_calls", JSONArray()).put("steps_done", JSONArray(listOf("tools", "inventory")))
        }
        val s = gw.summary("a1b2c3d4e5f6")
        assertEquals(68, s.reversal?.pct)
        assertEquals(9L, s.reversal?.seq)
        assertEquals(35, s.rebuild?.pct)
        assertNull("sem teste real, fidelidade e nula", s.fidelity)
        assertEquals(90, s.goalPercent)
        assertEquals(listOf("tools", "inventory"), s.stepsDone)
        assertEquals("GET", http.calls.single().method)
    }

    @Test
    fun `medidor sem avaliacao (pct nulo) fica nulo e nao zero`() = runTest {
        val (_, gw) = gateway { _, _, _ ->
            JSONObject().put("id", "a1b2c3d4e5f6").put("chain_ok", true)
                .put("reversal", JSONObject().put("pct", JSONObject.NULL).put("seq", 2))
        }
        assertNull(gw.summary("a1b2c3d4e5f6").reversal?.pct)
    }

    @Test
    fun `ledger pede desde o seq e le as entradas`() = runTest {
        val (http, gw) = gateway { _, _, _ ->
            JSONObject().put("last_seq", 3).put("entries", JSONArray().put(
                JSONObject().put("seq", 3).put("kind", "step_done").put("payload", JSONObject().put("step", "tools"))))
        }
        val page = gw.ledger("a1b2c3d4e5f6", since = 2)
        assertEquals("/v1/reform/a1b2c3d4e5f6/ledger?since=2", http.calls.single().path)
        assertEquals(3L, page.lastSeq)
        assertEquals("step_done", page.entries.single().kind)
    }

    @Test
    fun `verify manda o teste real e devolve a fidelidade`() = runTest {
        val (http, gw) = gateway { _, _, _ ->
            JSONObject().put("fidelity", JSONObject().put("pct", 80.0).put("seq", 15))
        }
        assertEquals(80, gw.verify("a1b2c3d4e5f6", "run-1", 4, 5)?.pct)
        val body = http.calls.single().body!!
        assertEquals("run-1", body.getString("test_run_id"))
        assertEquals(4, body.getInt("passed"))
        assertEquals(5, body.getInt("total"))
    }

    @Test
    fun `rede vai para v1 network com metered e transport`() = runTest {
        val (http, gw) = gateway { _, _, _ -> JSONObject() }
        gw.reportNetwork(true, "cellular")
        val call = http.calls.single()
        assertEquals("/v1/network", call.path)
        assertEquals("POST", call.method)
        assertTrue(call.body!!.getBoolean("metered"))
        assertEquals("cellular", call.body!!.getString("transport"))
    }

    @Test
    fun `id fora do padrao nunca chega a URL`() = runTest {
        val (http, gw) = gateway { _, _, _ -> JSONObject() }
        val failed = runCatching { gw.summary("../v1/jobs") }
        assertTrue(failed.exceptionOrNull() is IllegalArgumentException)
        assertTrue(http.calls.isEmpty())
    }
}
