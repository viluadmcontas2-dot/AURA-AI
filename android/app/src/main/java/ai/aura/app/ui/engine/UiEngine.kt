package ai.aura.app.ui.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/*
 * Fronteira UI <-> engine (docs/ENGINE_CONTRACT.md). A UI só pinta com status, risk, result,
 * error.code e events; nunca interpreta saída de terminal. A implementação real (HTTP loopback
 * no Termux) é do time do engine e implementa EngineGateway; a UI usa só esta interface.
 */

enum class JobStatus(val wire: String) {
    Queued("queued"), AwaitingApproval("awaiting_approval"), Running("running"),
    Completed("completed"), Failed("failed"), Cancelled("cancelled"), Interrupted("interrupted");

    val isTerminal: Boolean get() = this == Completed || this == Failed || this == Cancelled || this == Interrupted

    companion object {
        fun fromWire(value: String): JobStatus? = entries.firstOrNull { it.wire == value }
    }
}

enum class JobRisk(val wire: String) {
    Read("read"), Write("write"), Spend("spend"), Critical("critical");

    /** Tudo que não é leitura exige aprovação explícita do usuário. */
    val needsApproval: Boolean get() = this != Read

    companion object {
        fun fromWire(value: String): JobRisk? = entries.firstOrNull { it.wire == value }
    }
}

/** kind do contrato: system, shell, adb, files, github, claude_cli, gemini_cli, agy_cli, claude_api, gemini_api, skills. */
data class JobRequest(val kind: String, val prompt: String, val workspace: String = "default")

data class Job(
    val id: String,
    val requestId: String,
    /** Frase curta em português para a tela (nunca comando/log). */
    val title: String,
    val kind: String,
    val status: JobStatus,
    val risk: JobRisk,
    val createdAtMillis: Long,
    val result: String? = null,
    val errorCode: String? = null,
)

data class EngineEvent(
    val id: Long,
    val type: String,
    val jobId: String?,
    val payload: Map<String, String> = emptyMap(),
    val timestampMillis: Long,
)

/** Resultado de POST /v1/route: nada foi iniciado ainda. */
data class RouteResult(
    val request: JobRequest,
    val reason: String,
    val confidence: Double,
    val requiresModel: Boolean,
    val risk: JobRisk,
)

/** Ferramenta/serviço real do aparelho. available=false deve ser mostrado como indisponível. */
data class Capability(val id: String, val label: String, val available: Boolean, val detail: String? = null)

interface EngineGateway {
    suspend fun jobs(): List<Job>
    suspend fun approve(id: String): Job
    suspend fun cancel(id: String): Job
    suspend fun route(text: String): RouteResult
    suspend fun submit(requestId: String, request: JobRequest): Job
    suspend fun capabilities(): List<Capability>

    /** Eventos duráveis e ordenados (GET /v1/events?since=N); a UI retoma por id. */
    val events: Flow<EngineEvent>
}

/** Dados de exemplo para @Preview e telas em construção. Não fala com nada real. */
class FakeEngineGateway(
    seed: List<Job> = sampleJobs(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : EngineGateway {
    private val store = LinkedHashMap<String, Job>().also { m -> seed.forEach { m[it.id] = it } }
    private val bus = MutableSharedFlow<EngineEvent>(replay = 16, extraBufferCapacity = 64)
    private var nextEvent = 1L
    private var nextJob = 100

    override val events: Flow<EngineEvent> = bus.asSharedFlow()

    override suspend fun jobs(): List<Job> = store.values.sortedByDescending { it.createdAtMillis }

    override suspend fun approve(id: String): Job {
        val job = store[id] ?: error("job_not_found")
        if (job.status != JobStatus.AwaitingApproval) return job
        return update(job.copy(status = JobStatus.Running), "job.approved")
    }

    override suspend fun cancel(id: String): Job {
        val job = store[id] ?: error("job_not_found")
        if (job.status.isTerminal) return job
        return update(job.copy(status = JobStatus.Cancelled), "job.cancelled")
    }

    override suspend fun route(text: String): RouteResult {
        val t = text.lowercase()
        val risk = when {
            listOf("apagar", "delete", "excluir").any { it in t } -> JobRisk.Critical
            listOf("comprar", "pagar", "api").any { it in t } -> JobRisk.Spend
            listOf("instalar", "criar", "corrigir", "commit", "push").any { it in t } -> JobRisk.Write
            else -> JobRisk.Read
        }
        return RouteResult(JobRequest("claude_cli", text), "Exemplo (fake): classificado por palavra-chave", 0.5, true, risk)
    }

    override suspend fun submit(requestId: String, request: JobRequest): Job {
        store.values.firstOrNull { it.requestId == requestId }?.let { return it }
        val risk = route(request.prompt).risk
        val job = Job(
            id = "job_${nextJob++}", requestId = requestId, title = request.prompt.take(60),
            kind = request.kind,
            status = if (risk.needsApproval) JobStatus.AwaitingApproval else JobStatus.Queued,
            risk = risk, createdAtMillis = clock(),
        )
        return update(job, "job.created")
    }

    override suspend fun capabilities(): List<Capability> = listOf(
        Capability("termux", "Termux", true),
        Capability("github", "GitHub (gh)", true, "Conectado"),
        Capability("adb", "Controle de Android (adb)", false, "Não instalado neste aparelho"),
    )

    private suspend fun update(job: Job, type: String): Job {
        store[job.id] = job
        bus.emit(EngineEvent(nextEvent++, type, job.id, mapOf("status" to job.status.wire), clock()))
        return job
    }

    companion object {
        fun sampleJobs(now: Long = 1_700_000_000_000L): List<Job> = listOf(
            Job("job_1", "req_1", "Corrigir tela de login", "claude_cli", JobStatus.Running, JobRisk.Write, now),
            Job("job_2", "req_2", "Instalar versão 0.3.2 no aparelho", "adb", JobStatus.AwaitingApproval, JobRisk.Write, now - 60_000),
            Job("job_3", "req_3", "Listar projetos do GitHub", "github", JobStatus.Completed, JobRisk.Read, now - 3_600_000,
                result = "5 projetos encontrados"),
            Job("job_4", "req_4", "Enviar alterações ao GitHub", "github", JobStatus.Failed, JobRisk.Write, now - 7_200_000,
                errorCode = "auth_required"),
        )
    }
}
