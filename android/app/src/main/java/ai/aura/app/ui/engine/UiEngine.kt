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
    Queued("queued"),
    /** Só existe no celular: o pedido ficou guardado porque o motor não respondeu e vai sozinho quando ele voltar. */
    WaitingEngine("waiting_engine"),
    AwaitingApproval("awaiting_approval"), Running("running"),
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

/* ---- Política e modelos (docs/design/propostas/politica-modelos-e-design.md, seções 2, 2b, 3) ----
 * Espelham policy.json / models.json. Configuração é dado: o app lê e grava, nunca decide. */

/** "auto" ou "ask" no JSON. */
enum class Gate(val wire: String) {
    Auto("auto"), Ask("ask");

    companion object {
        fun fromWire(value: String): Gate? = entries.firstOrNull { it.wire == value }
    }
}

/** pergunta | equilibrado | autonomo | total. */
enum class PolicyMode(val wire: String) {
    Pergunta("pergunta"), Equilibrado("equilibrado"), Autonomo("autonomo"), Total("total");

    companion object {
        fun fromWire(value: String): PolicyMode? = entries.firstOrNull { it.wire == value }
    }
}

/** Chaves de by_risk e by_kind na ordem de exibição. */
val POLICY_RISK_KEYS: List<String> = listOf("read", "write", "spend", "critical")
val POLICY_KIND_KEYS: List<String> = listOf("shell", "adb", "files", "github", "root")

data class RootPolicy(val enabled: Boolean = true, val via: List<String> = listOf("su", "tsu"))

data class PolicyScope(val path: String, val write: Gate)

/** null = sem limite. onReach: "avisar" | "pausar". alertPercent é extensão da UI (alerta em X%). */
data class SpendCap(
    val perJob: Double? = null,
    val perDay: Double? = null,
    val perMonth: Double? = null,
    val onReach: String = "avisar",
    val alertPercent: Int? = null,
)

data class PolicyDoc(
    val version: Int,
    val mode: PolicyMode = PolicyMode.Total,
    val byRisk: Map<String, Gate> = POLICY_RISK_KEYS.associateWith { Gate.Auto },
    val byKind: Map<String, Gate> = POLICY_KIND_KEYS.associateWith { Gate.Auto },
    val root: RootPolicy = RootPolicy(),
    val scopes: List<PolicyScope> = listOf(PolicyScope("*", Gate.Auto)),
    val spendCap: SpendCap = SpendCap(),
    val autoCountdownSeconds: Int = 0,
    val notify: String = "so_se_precisar",
)

/** Linha do histórico. snapshot permite "Voltar para esta versão" (savePolicy do documento antigo). */
data class PolicyVersion(
    val version: Int,
    val phrase: String?,
    val atMillis: Long,
    val by: String?,
    val snapshot: PolicyDoc? = null,
)

sealed interface PolicyResult {
    /** doc é a versão que o motor aplicou de fato. */
    data class Ok(val doc: PolicyDoc, val history: List<PolicyVersion> = emptyList()) : PolicyResult
    data class Rejected(val code: String, val detail: String? = null) : PolicyResult
    data class Unavailable(val reason: String? = null) : PolicyResult
}

data class ModelRoute(val task: String, val use: List<String>)

data class ModelsDoc(
    val version: Int,
    val deny: List<String> = listOf("fable*"),
    val ceiling: Map<String, String> = mapOf("claude" to "sonnet:medium"),
    val routes: List<ModelRoute> = emptyList(),
    val preferSubscription: Boolean = true,
    /** "never" | "allow". */
    val apiFallback: String = "never",
)

sealed interface ModelsResult {
    data class Ok(val doc: ModelsDoc) : ModelsResult
    data class Rejected(val code: String, val detail: String? = null) : ModelsResult
    data class Unavailable(val reason: String? = null) : ModelsResult
}

/** Valores em dólar; null = o motor/provedor não informou (a UI mostra "não informado"). */
data class SpendLine(val provider: String, val model: String?, val todayUsd: Double?, val monthUsd: Double?)

data class SpendSummary(val lines: List<SpendLine>)

sealed interface SpendResult {
    data class Ok(val summary: SpendSummary) : SpendResult
    data class Unavailable(val reason: String? = null) : SpendResult
}

/* ---- Login fácil (docs/design/propostas/login-facil.md) ----
 * Contas das assinaturas do dono. A UI nunca vê token: só estado, link e código de uso único. */

/** Ids estáveis do contrato (GET /v1/auth/status, POST /v1/auth/start). */
val AUTH_PROVIDER_IDS: List<String> = listOf("claude", "gemini", "agy", "codex", "github", "gdrive")

/** conectado | desconectado | expirado | indisponivel. */
enum class AuthState(val wire: String) {
    Connected("conectado"), Disconnected("desconectado"), Expired("expirado"), Unavailable("indisponivel");

    companion object {
        fun fromWire(value: String): AuthState? = entries.firstOrNull { it.wire == value }
    }
}

/** installable=true: o CLI não está no aparelho e o motor sabe instalar (botão Instalar). */
data class AuthStatus(val provider: String, val state: AuthState, val installable: Boolean = false, val detail: String? = null)

sealed interface AuthStartResult {
    /** O link/código chegam pelo evento auth.prompt; o fim, por auth.done ou auth.failed. */
    data object Started : AuthStartResult
    /** reason: o que houve; fix: o que o dono deve fazer (português, sem log). */
    data class Failed(val reason: String, val fix: String) : AuthStartResult
}

interface EngineGateway {
    suspend fun jobs(): List<Job>
    suspend fun approve(id: String): Job
    suspend fun cancel(id: String): Job
    suspend fun route(text: String): RouteResult
    suspend fun submit(requestId: String, request: JobRequest): Job
    suspend fun capabilities(): List<Capability>

    /** Eventos duráveis e ordenados (GET /v1/events?since=N); a UI retoma por id. */
    val events: Flow<EngineEvent>

    // Política, modelos e gastos (GET/PUT /v1/policy, /v1/models). Padrão = indisponível, para
    // não quebrar adaptadores que ainda não implementam.
    suspend fun policy(): PolicyResult = PolicyResult.Unavailable()
    suspend fun savePolicy(p: PolicyDoc): PolicyResult = PolicyResult.Unavailable()
    suspend fun models(): ModelsResult = ModelsResult.Unavailable()
    suspend fun saveModels(m: ModelsDoc): ModelsResult = ModelsResult.Unavailable()
    suspend fun spendSummary(): SpendResult = SpendResult.Unavailable()

    // Login fácil (GET /v1/auth/status, POST /v1/auth/start). Padrão = tudo indisponível.
    suspend fun authStatus(): List<AuthStatus> = AUTH_PROVIDER_IDS.map { AuthStatus(it, AuthState.Unavailable) }
    suspend fun startAuth(provider: String): AuthStartResult =
        AuthStartResult.Failed("O motor ainda não sabe conectar contas.", "Atualize o AURA no Termux e tente de novo.")
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
