package ai.aura.app.integration

import ai.aura.app.ui.screens.voice.TriageChoice
import ai.aura.app.ui.screens.voice.VoiceOutcome
import ai.aura.app.ui.screens.voice.planVoiceRequest
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueGatewayTest {
    private val snapshot = QueueSnapshot("Corrigir login", listOf("Instalar na TV"), listOf("Build antigo"))

    @Test fun `le a fila do motor`() = runTest {
        val g = RealQueueGateway { path, _, _ ->
            assertEquals("/v1/queue", path)
            JSONObject()
                .put("running", JSONObject().put("jobId", "j1").put("title", "Corrigir login"))
                .put("queued", JSONArray().put(JSONObject().put("taskId", "t1").put("text", "Instalar na TV")))
                .put("saved", JSONArray().put(JSONObject().put("taskId", "t2").put("title", "Build antigo")))
        }
        assertEquals(snapshot, g.queue())
    }

    @Test fun `fila sem trabalho rodando tem running nulo`() = runTest {
        val g = RealQueueGateway { _, _, _ ->
            JSONObject().put("running", JSONObject.NULL).put("queued", JSONArray()).put("saved", JSONArray())
        }
        assertEquals(null, g.queue().runningTitle)
    }

    @Test fun `envia a entrada por voz e aplica a escolha`() = runTest {
        val calls = mutableListOf<Triple<String, String, JSONObject?>>()
        val g = RealQueueGateway { p, m, b ->
            calls += Triple(p, m, b)
            if (p == "/v1/inputs") JSONObject().put("id", "abc").put("kind", "new_task").put("clean", "Instala na TV.")
            else JSONObject()
        }
        val r = g.receive("instala na tv", "voice")
        assertEquals(VoiceInput("abc", "new_task", "Instala na TV."), r)
        assertEquals("voice", calls[0].third!!.getString("source"))
        g.apply("abc", "now", confirm = true)
        assertEquals("/v1/inputs/abc/apply", calls[1].first)
        assertEquals("now", calls[1].third!!.getString("mode"))
        assertTrue(calls[1].third!!.getBoolean("confirm"))
    }

    @Test fun `sem trabalho rodando o pedido vai direto para o chat`() = runTest {
        val q = FakeQueueGateway(QueueSnapshot(null, emptyList(), emptyList()))
        assertEquals(VoiceOutcome.Chat("faz um app"), planVoiceRequest(q, "faz um app"))
        assertTrue(q.received.isEmpty())
    }

    @Test fun `com trabalho rodando um pedido novo vai para a triagem com dados reais`() = runTest {
        val q = FakeQueueGateway(snapshot, VoiceInput("t9", "new_task", "Instala na TV."))
        val out = planVoiceRequest(q, "instala na tv") as VoiceOutcome.Triage
        assertEquals("t9", out.taskId)
        assertEquals("Instala na TV.", out.state.heard)
        assertEquals("Corrigir login", out.state.currentTitle)
        assertEquals(listOf("Instalar na TV"), out.state.queued)
        assertEquals(listOf("Build antigo"), out.state.saved)
    }

    @Test fun `pergunta com trabalho rodando segue para o chat`() = runTest {
        val q = FakeQueueGateway(snapshot, VoiceInput("t9", "question", "Como está?"))
        assertEquals(VoiceOutcome.Chat("como está"), planVoiceRequest(q, "como está"))
    }

    @Test fun `parar tudo por voz nao cria pedido`() = runTest {
        val q = FakeQueueGateway(snapshot, VoiceInput("t9", "stop", "Pare."))
        assertEquals(VoiceOutcome.Stopped, planVoiceRequest(q, "pare"))
    }

    @Test fun `motor fora do ar cai no chat`() = runTest {
        val q = FakeQueueGateway(snapshot).apply { down = true }
        assertEquals(VoiceOutcome.Chat("oi"), planVoiceRequest(q, "oi"))
    }

    @Test fun `escolha da triagem vira modo do motor`() {
        assertEquals("later", TriageChoice.AfterCurrent.mode)
        assertEquals("merge", TriageChoice.MergeIntoCurrent.mode)
        assertEquals("now", TriageChoice.StopAndDoNow.mode)
    }
}
