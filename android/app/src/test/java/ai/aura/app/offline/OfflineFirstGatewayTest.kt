package ai.aura.app.offline

import ai.aura.app.integration.AuraEngineException
import ai.aura.app.ui.engine.Capability
import ai.aura.app.ui.engine.EngineEvent
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobRequest
import ai.aura.app.ui.engine.JobRisk
import ai.aura.app.ui.engine.JobStatus
import ai.aura.app.ui.engine.RouteResult
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

private fun job(id: String, requestId: String = "req_$id", status: JobStatus = JobStatus.Completed) =
    Job(id, requestId, "Tarefa $id", "claude_cli", status, JobRisk.Write, 1_000L)

/** Motor de mentira com interruptor: desligado = IOException em tudo, como o cliente HTTP real. */
private class FakeRemote : EngineGateway {
    var up = true
    /** Depois de N envios aceitos o "motor" cai (queda no meio do envio da fila). */
    var dropAfterSubmits: Int? = null
    var jobsList: List<Job> = emptyList()
    var jobsError: Exception? = null
    var capsList: List<Capability> = emptyList()
    /** Motor no ar, mas recusando route/submit. */
    var refuse: Exception? = null
    val submitted = mutableListOf<Pair<String, JobRequest>>()
    val bus = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16)
    override val events: Flow<EngineEvent> = bus

    private fun requireUp() { if (!up) throw IOException("Termux não responde") }

    override suspend fun jobs(): List<Job> {
        requireUp()
        jobsError?.let { throw it }
        return jobsList
    }
    override suspend fun approve(id: String): Job { requireUp(); return job(id, status = JobStatus.Running) }
    override suspend fun cancel(id: String): Job { requireUp(); return job(id, status = JobStatus.Cancelled) }
    override suspend fun route(text: String): RouteResult {
        requireUp()
        refuse?.let { throw it }
        return RouteResult(JobRequest("system", text), "ok", 1.0, false, JobRisk.Read)
    }
    override suspend fun submit(requestId: String, request: JobRequest): Job {
        requireUp()
        refuse?.let { throw it }
        delay(10) // deixa duas descargas da fila se atropelarem, se faltar exclusão mútua
        submitted += requestId to request
        if (dropAfterSubmits == submitted.size) up = false
        return job("job_${submitted.size}", requestId, JobStatus.Queued)
    }
    override suspend fun capabilities(): List<Capability> { requireUp(); return capsList }
}

private object TudoInstalado : TermuxProbe {
    override fun installed() = true
    override fun hasPermission() = true
}

class OfflineFirstGatewayTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var remote: FakeRemote
    private lateinit var link: EngineLink

    @Before fun setUp() {
        remote = FakeRemote()
        link = EngineLink(TudoInstalado)
    }

    /** Cada chamada cria uma instância nova sobre a mesma pasta: é o app sendo reaberto. */
    private fun gateway(dir: File = tmp.root) =
        OfflineFirstGateway(remote, LocalStore(dir), link) { 5_000L }

    private val pedido = JobRequest("auto", "listar dispositivos")

    // ---- leitura ----

    @Test fun `com o motor no ar, jobs devolve os do motor e marca online`() = runTest {
        remote.jobsList = listOf(job("job_1"))

        val jobs = gateway().jobs()

        assertEquals(listOf("job_1"), jobs.map { it.id })
        assertEquals(LinkState.Online, link.state.value)
    }

    @Test fun `com o motor desligado, jobs devolve a ultima lista conhecida sem lancar`() = runTest {
        remote.jobsList = listOf(job("job_1"), job("job_2"))
        gateway().jobs()
        remote.up = false

        val jobs = gateway().jobs()

        assertEquals(listOf("job_1", "job_2"), jobs.map { it.id })
        assertEquals(LinkState.Offline(OfflineReason.EngineStopped), link.state.value)
    }

    @Test fun `sem motor e sem cache, jobs devolve lista vazia`() = runTest {
        remote.up = false

        assertTrue(gateway().jobs().isEmpty())
    }

    @Test fun `token recusado pelo motor marca NoToken e ainda devolve o cache`() = runTest {
        remote.jobsList = listOf(job("job_1"))
        gateway().jobs()
        remote.jobsError = AuraEngineException(401, "token inválido", "unauthorized")

        val jobs = gateway().jobs()

        assertEquals(listOf("job_1"), jobs.map { it.id })
        assertEquals(LinkState.Offline(OfflineReason.NoToken), link.state.value)
    }

    @Test fun `resposta ilegivel do motor devolve o cache e nao acusa motor desligado`() = runTest {
        remote.jobsList = listOf(job("job_1"))
        gateway().jobs()
        remote.jobsError = IllegalStateException("Estado desconhecido retornado pelo AURA Engine")

        val jobs = gateway().jobs()

        assertEquals(listOf("job_1"), jobs.map { it.id })
        assertEquals(LinkState.Online, link.state.value)
    }

    @Test fun `capacidades offline mostram a ultima leitura como indisponivel, sem afirmar o que nao da para saber`() = runTest {
        remote.capsList = listOf(
            Capability("adb", "Android ADB", true, "Instalado"),
            Capability("git", "Git", false, "Instalação pendente"),
        )
        gateway().capabilities()
        remote.up = false

        val caps = gateway().capabilities()

        assertEquals(listOf("adb", "git"), caps.map { it.id })
        assertTrue(caps.none { it.available })
        assertTrue(caps.first { it.id == "adb" }.detail!!.contains("Motor desligado"))
    }

    // ---- pedidos sem motor ----

    @Test fun `route com o motor desligado devolve interpretacao provisoria que pede aprovacao`() = runTest {
        remote.up = false

        val r = gateway().route("apagar a pasta de testes")

        assertEquals("auto", r.request.kind)
        assertEquals("apagar a pasta de testes", r.request.prompt)
        assertTrue(r.risk.needsApproval)
        assertTrue(r.requiresModel)
        assertTrue(r.reason.contains("fila"))
        assertEquals(LinkState.Offline(OfflineReason.EngineStopped), link.state.value)
    }

    @Test fun `submit com o motor desligado guarda o pedido e devolve um job aguardando o motor`() = runTest {
        remote.up = false

        val job = gateway().submit("req-1", pedido)

        assertEquals(JobStatus.WaitingEngine, job.status)
        assertEquals("req-1", job.requestId)
        assertEquals("listar dispositivos", job.title)
        assertEquals(0, remote.submitted.size)
    }

    @Test fun `o pedido guardado continua em jobs depois de reabrir o app`() = runTest {
        remote.up = false
        gateway().submit("req-1", pedido)

        val jobs = gateway().jobs()

        assertEquals(listOf(JobStatus.WaitingEngine), jobs.map { it.status })
        assertEquals("listar dispositivos", jobs.single().title)
    }

    @Test fun `submit repetido com o mesmo requestId offline nao duplica o pedido`() = runTest {
        remote.up = false
        val gw = gateway()

        val primeiro = gw.submit("req-1", pedido)
        val segundo = gw.submit("req-1", pedido)

        assertEquals(primeiro.id, segundo.id)
        assertEquals(1, gw.jobs().count { it.status == JobStatus.WaitingEngine })
    }

    @Test fun `com o motor no ar, pedido recusado lanca e nao vai para a fila`() = runTest {
        remote.refuse = AuraEngineException(400, "pedido inválido", "bad_request")
        val gw = gateway()

        val erro = runCatching { gw.submit("req-1", JobRequest("system", "x")) }.exceptionOrNull()

        assertTrue(erro is AuraEngineException)
        remote.refuse = null
        assertTrue(gw.jobs().none { it.status == JobStatus.WaitingEngine })
    }

    @Test fun `cancelar um pedido aguardando o motor tira da fila sem falar com o motor`() = runTest {
        remote.up = false
        val gw = gateway()
        val local = gw.submit("req-1", pedido)

        val cancelado = gw.cancel(local.id)

        assertEquals(JobStatus.Cancelled, cancelado.status)
        assertTrue(gw.jobs().none { it.requestId == "req-1" })
        remote.up = true
        gw.jobs()
        assertEquals(0, remote.submitted.size)
    }

    @Test fun `aprovar com o motor desligado lanca IOException e marca offline`() = runTest {
        remote.up = false

        val erro = runCatching { gateway().approve("job_1") }.exceptionOrNull()

        assertTrue(erro is IOException)
        assertEquals(LinkState.Offline(OfflineReason.EngineStopped), link.state.value)
    }

    // ---- a volta do motor ----

    @Test fun `quando o motor volta, jobs envia o pedido guardado uma unica vez com o mesmo requestId`() = runTest {
        remote.up = false
        val gw = gateway()
        gw.submit("req-1", pedido)
        remote.up = true

        gw.jobs()
        gw.jobs()

        assertEquals(listOf("req-1"), remote.submitted.map { it.first })
        // O motor reinterpreta o pedido na volta: o tipo vem da resposta dele, nao do "auto" provisorio.
        assertEquals("system", remote.submitted.single().second.kind)
        assertEquals("listar dispositivos", remote.submitted.single().second.prompt)
        assertTrue(gw.jobs().none { it.status == JobStatus.WaitingEngine })
    }

    @Test fun `dois jobs ao mesmo tempo, na volta do motor, nao enviam o pedido duas vezes`() = runTest {
        remote.up = false
        val gw = gateway()
        gw.submit("req-1", pedido)
        remote.up = true

        val a = async { gw.jobs() }
        val b = async { gw.jobs() }
        a.await()
        b.await()

        assertEquals(1, remote.submitted.size)
    }

    @Test fun `se o motor cai no meio do envio, o resto fica na fila na mesma ordem`() = runTest {
        remote.up = false
        val gw = gateway()
        gw.submit("req-1", JobRequest("auto", "um"))
        gw.submit("req-2", JobRequest("auto", "dois"))
        gw.submit("req-3", JobRequest("auto", "tres"))
        remote.up = true
        remote.dropAfterSubmits = 1

        val jobs = gw.jobs()

        assertEquals(listOf("req-1"), remote.submitted.map { it.first })
        assertEquals(setOf("dois", "tres"), jobs.filter { it.status == JobStatus.WaitingEngine }.map { it.title }.toSet())
        assertEquals(LinkState.Offline(OfflineReason.EngineStopped), link.state.value)

        remote.up = true
        gw.jobs()
        assertEquals(listOf("req-1", "req-2", "req-3"), remote.submitted.map { it.first })
    }

    @Test fun `se o motor recusa o pedido guardado, ele vira falha no historico e sai da fila`() = runTest {
        remote.up = false
        val gw = gateway()
        gw.submit("req-1", pedido)
        remote.up = true
        remote.refuse = AuraEngineException(409, "pedido em conflito", "idempotency_conflict")

        val falha = gw.jobs().single { it.requestId == "req-1" }

        assertEquals(JobStatus.Failed, falha.status)
        assertEquals("idempotency_conflict", falha.errorCode)
        assertEquals("pedido em conflito", falha.result)
        // Na leitura seguinte continua no historico e nao e reenviado.
        remote.refuse = null
        assertEquals(JobStatus.Failed, gw.jobs().single { it.requestId == "req-1" }.status)
        assertEquals(0, remote.submitted.size)
    }

    // ---- eventos ----

    @Test fun `evento connection restored marca online e envia a fila`() = runTest {
        remote.up = false
        val gw = gateway()
        gw.submit("req-1", pedido)
        remote.up = true
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { gw.events.collect { } }

        remote.bus.emit(EngineEvent(-1, "connection.restored", null, timestampMillis = 0))
        // O coletor está no backgroundScope e o envio da fila passa pelo delay(10) do FakeRemote:
        // advanceUntilIdle() não anda o relógio por trabalho de fundo, só advanceTimeBy().
        advanceTimeBy(100)

        assertEquals(LinkState.Online, link.state.value)
        assertEquals(listOf("req-1"), remote.submitted.map { it.first })
    }

    @Test fun `evento connection lost marca offline`() = runTest {
        val gw = gateway()
        gw.jobs()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { gw.events.collect { } }

        remote.bus.emit(EngineEvent(-2, "connection.lost", null, timestampMillis = 0))
        advanceUntilIdle()

        assertEquals(LinkState.Offline(OfflineReason.EngineStopped), link.state.value)
    }

    @Test fun `eventos comuns passam adiante sem mexer no estado da conexao`() = runTest {
        val gw = gateway()
        gw.jobs()
        val recebidos = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { gw.events.collect { recebidos += it.type } }

        remote.bus.emit(EngineEvent(1, "job.created", "job_1", timestampMillis = 0))
        advanceUntilIdle()

        assertEquals(listOf("job.created"), recebidos)
        assertEquals(LinkState.Online, link.state.value)
    }
}
