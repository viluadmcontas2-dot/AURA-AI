package ai.aura.app.ui.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

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

    // Política/modelos em memória (só para @Preview e telas em construção).
    private var policyDoc = PolicyDoc(version = 1)
    private var modelsDoc = ModelsDoc(
        version = 1,
        routes = listOf(
            ModelRoute("triagem|classificar|resumir|status", listOf("haiku:medium")),
            ModelRoute("codigo_mecanico|testes|refatoracao_simples", listOf("codex_cli", "agy_cli", "haiku:high")),
            ModelRoute("ui_compose|planejar|revisar", listOf("sonnet:medium")),
            ModelRoute("pesquisa_ampla", listOf("subagente:haiku:medium")),
        ),
    )
    private val policyHistory = mutableListOf(
        PolicyVersion(1, null, clock(), "dono", policyDoc),
    )

    override suspend fun policy(): PolicyResult = PolicyResult.Ok(policyDoc, policyHistory.asReversed().toList())

    override suspend fun savePolicy(p: PolicyDoc): PolicyResult {
        val applied = p.copy(version = policyDoc.version + 1)
        policyDoc = applied
        policyHistory += PolicyVersion(applied.version, null, clock(), "dono", applied)
        bus.emit(EngineEvent(nextEvent++, "policy.changed", null, mapOf("version" to applied.version.toString()), clock()))
        return PolicyResult.Ok(applied, policyHistory.asReversed().toList())
    }

    override suspend fun models(): ModelsResult = ModelsResult.Ok(modelsDoc)

    override suspend fun saveModels(m: ModelsDoc): ModelsResult {
        modelsDoc = m.copy(version = modelsDoc.version + 1)
        bus.emit(EngineEvent(nextEvent++, "models.changed", null, mapOf("version" to modelsDoc.version.toString()), clock()))
        return ModelsResult.Ok(modelsDoc)
    }

    override suspend fun spendSummary(): SpendResult = SpendResult.Ok(
        SpendSummary(
            listOf(
                SpendLine("Claude (assinatura)", "Sonnet", todayUsd = null, monthUsd = null),
                SpendLine("Codex (assinatura)", null, todayUsd = null, monthUsd = null),
                SpendLine("API paga", "Haiku", todayUsd = 0.42, monthUsd = 3.10),
            ),
        ),
    )

    // Login simulado: conecta 4 s depois do auth.prompt (só @Preview/telas em construção).
    private val auth = linkedMapOf(
        "claude" to AuthState.Connected, "gemini" to AuthState.Disconnected, "agy" to AuthState.Expired,
        "codex" to AuthState.Disconnected, "github" to AuthState.Connected, "gdrive" to AuthState.Unavailable,
    )
    private val authPending = HashMap<String, Long>()

    override suspend fun authStatus(): List<AuthStatus> {
        val now = clock()
        authPending.entries.filter { now - it.value >= 4_000 }.map { it.key }.forEach { p ->
            authPending.remove(p)
            auth[p] = AuthState.Connected
            bus.emit(EngineEvent(nextEvent++, "auth.done", null, mapOf("provider" to p), now))
        }
        return auth.map { (p, st) -> AuthStatus(p, st, installable = p == "gdrive") }
    }

    override suspend fun startAuth(provider: String): AuthStartResult {
        if (auth[provider] == null) return AuthStartResult.Failed("Conta desconhecida.", "Escolha outra conta.")
        authPending[provider] = clock()
        bus.emit(
            EngineEvent(
                nextEvent++, "auth.prompt", null,
                mapOf("provider" to provider, "url" to "https://example.com/device", "code" to "ABCD-1234"), clock(),
            ),
        )
        return AuthStartResult.Started
    }

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
