package ai.aura.app.offline

import ai.aura.app.integration.AuraEngineException
import ai.aura.app.ui.engine.Capability
import ai.aura.app.ui.engine.EngineEvent
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobRequest
import ai.aura.app.ui.engine.JobRisk
import ai.aura.app.ui.engine.JobStatus
import ai.aura.app.ui.engine.PolicyResult
import ai.aura.app.ui.engine.RouteResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Casos que o `OfflineFirstGatewayTest` não cobre: o que o gateway decidiu além do contrato mínimo. */
private class ScriptedEngine : EngineGateway {
    var up = true
    var unauthorized = false
    var submitError: Exception? = null
    var policyReply: PolicyResult = PolicyResult.Unavailable()

    /** Idempotente por requestId, como o motor de verdade. */
    val known = linkedMapOf<String, Job>()
    val order = mutableListOf<String>()
    override val events: Flow<EngineEvent> = emptyFlow()

    private fun gate() {
        if (!up) throw IOException("Termux não responde")
        if (unauthorized) throw AuraEngineException(401, "token inválido", "unauthorized")
    }

    override suspend fun jobs(): List<Job> { gate(); return known.values.toList() }
    override suspend fun approve(id: String): Job = error("não usado")
    override suspend fun cancel(id: String): Job = error("não usado")
    override suspend fun route(text: String): RouteResult {
        gate()
        return RouteResult(JobRequest("claude_cli", text), "ok", 1.0, false, JobRisk.Read)
    }
    override suspend fun submit(requestId: String, request: JobRequest): Job {
        gate()
        submitError?.let { throw it }
        return known.getOrPut(requestId) {
            order += requestId
            Job("job_${order.size}", requestId, request.prompt, request.kind, JobStatus.Queued, JobRisk.Read, 1_000L + order.size)
        }
    }
    override suspend fun capabilities(): List<Capability> { gate(); return emptyList() }
    override suspend fun policy(): PolicyResult = policyReply
}

private object Instalado : TermuxProbe {
    override fun installed() = true
    override fun hasPermission() = true
}

class OfflineFirstGatewayExtrasTest {
    @get:Rule val tmp = TemporaryFolder()

    private val engine = ScriptedEngine()
    private val link = EngineLink(Instalado)

    private fun gateway() = OfflineFirstGateway(engine, LocalStore(tmp.root), link) { 5_000L }

    private fun pedido(texto: String) = JobRequest("auto", texto)

    @Test fun `o que o gateway nao trata passa direto para o motor`() = runTest {
        engine.policyReply = PolicyResult.Rejected("policy_conflict")

        assertEquals(PolicyResult.Rejected("policy_conflict"), gateway().policy())
    }

    @Test fun `pedido auto com o motor no ar e interpretado antes de ser enviado`() = runTest {
        val job = gateway().submit("req-1", pedido("listar projetos"))

        assertEquals("claude_cli", job.kind)
        assertEquals(JobStatus.Queued, job.status)
        assertEquals(listOf("req-1"), engine.order)
    }

    @Test fun `pedido novo nao passa na frente do que ja esperava`() = runTest {
        engine.up = false
        val gw = gateway()
        gw.submit("req-1", pedido("um"))
        gw.submit("req-2", pedido("dois"))
        engine.up = true

        gw.submit("req-3", pedido("tres"))

        assertEquals(listOf("req-1", "req-2", "req-3"), engine.order)
        assertTrue(gw.jobs().none { it.status == JobStatus.WaitingEngine })
    }

    @Test fun `credencial recusada na volta do motor deixa a fila intacta e avisa NoToken`() = runTest {
        engine.up = false
        val gw = gateway()
        gw.submit("req-1", pedido("um"))
        engine.up = true
        engine.unauthorized = true

        val jobs = gw.jobs()

        assertEquals(listOf(JobStatus.WaitingEngine), jobs.map { it.status })
        assertEquals(LinkState.Offline(OfflineReason.NoToken), link.state.value)
        engine.unauthorized = false
        gw.jobs()
        assertEquals(listOf("req-1"), engine.order)
    }

    @Test fun `erro passageiro do motor nao tira o pedido da fila`() = runTest {
        engine.up = false
        val gw = gateway()
        gw.submit("req-1", pedido("um"))
        engine.up = true

        for (status in listOf(408, 429, 503)) {
            engine.submitError = AuraEngineException(status, "tente depois", "busy")
            assertEquals("HTTP $status", listOf(JobStatus.WaitingEngine), gw.jobs().map { it.status })
        }
        engine.submitError = null
        assertTrue(gw.jobs().none { it.status == JobStatus.WaitingEngine })
        assertEquals(listOf("req-1"), engine.order)
    }

    @Test fun `se o motor ja conhece o pedido e o reenvio falha, ele aparece uma vez so`() = runTest {
        engine.up = false
        val gw = gateway()
        gw.submit("req-1", pedido("um"))
        engine.up = true
        engine.submit("req-1", JobRequest("claude_cli", "um"))
        engine.submitError = AuraEngineException(503, "ocupado", "busy")

        val jobs = gw.jobs()

        assertEquals(1, jobs.count { it.requestId == "req-1" })
        assertEquals(JobStatus.Queued, jobs.single { it.requestId == "req-1" }.status)
    }

    @Test fun `fila corrompida no disco nao derruba a leitura e o proximo pedido ainda e guardado`() = runTest {
        File(tmp.root, "offline-queue.json").writeText("{ isto nao e json")
        engine.up = false
        val gw = gateway()

        assertTrue(gw.jobs().isEmpty())
        val job = gw.submit("req-1", pedido("um"))

        assertEquals(JobStatus.WaitingEngine, job.status)
        assertEquals(listOf("req-1"), gateway().jobs().map { it.requestId })
    }

    @Test fun `pedido guardado aparece antes dos jobs antigos do motor`() = runTest {
        engine.submit("antigo", JobRequest("claude_cli", "antigo"))
        gateway().jobs()
        engine.up = false
        val gw = gateway()

        gw.submit("novo", pedido("novo"))

        assertEquals(listOf("novo", "antigo"), gw.jobs().map { it.requestId })
    }
}
