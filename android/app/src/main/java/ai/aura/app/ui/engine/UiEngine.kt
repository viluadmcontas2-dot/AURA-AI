package ai.aura.app.ui.engine

import kotlinx.coroutines.flow.Flow

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
    /** "never" | "ask" (contrato do motor). */
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
    /** O motor já devolveu link e código na resposta (login que já estava em andamento). */
    data class Prompted(val url: String, val code: String?) : AuthStartResult
    /** reason: o que houve; fix: o que o dono deve fazer (português, sem log). */
    data class Failed(val reason: String, val fix: String) : AuthStartResult
}

/** Resultado de instalar uma CLI ou enviar código/token de login: [message] pronta para a tela, nunca com segredo. */
sealed interface ToolActionResult {
    data class Ok(val message: String) : ToolActionResult
    data class Failed(val reason: String, val fix: String) : ToolActionResult
}

/** Resultado das leituras e ações de memória, skills, saúde e uso: nunca lança por falta de motor. */
sealed interface EngineResult<out T> {
    data class Ok<T>(val value: T) : EngineResult<T>

    /** O motor entendeu e recusou; [message] é a mensagem dele, pronta para mostrar. */
    data class Rejected(val code: String?, val message: String) : EngineResult<Nothing>
    data class Unavailable(val reason: String) : EngineResult<Nothing>
}

/** [kind]: preference, project, device ou rule. [createdAtMillis] já em milissegundos (o motor manda segundos). */
data class MemoryEntry(val id: String, val kind: String, val text: String, val source: String?, val createdAtMillis: Long)

data class SkillInfo(val id: String, val name: String, val description: String, val bundled: Boolean)

data class EngineHealth(val service: String, val version: Int)

data class EngineUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val reportedJobs: Int,
    val unreportedJobs: Int,
)

private const val NOT_EXPOSED = "Este AURA Engine ainda não expõe isso."

interface EngineGateway {
    suspend fun jobs(): List<Job>
    suspend fun approve(id: String): Job
    suspend fun cancel(id: String): Job
    suspend fun route(text: String): RouteResult

    /** Como [route], num workspace. O padrão ignora o workspace. */
    suspend fun route(text: String, workspace: String): RouteResult = route(text)
    suspend fun submit(requestId: String, request: JobRequest): Job
    suspend fun capabilities(): List<Capability>

    // Memória, skills, saúde e uso (GET /v1/memory, /v1/skills, /v1/health, /v1/usage). Padrão = indisponível.
    suspend fun memory(): EngineResult<List<MemoryEntry>> = EngineResult.Unavailable(NOT_EXPOSED)
    suspend fun forgetMemory(id: String): EngineResult<Unit> = EngineResult.Unavailable(NOT_EXPOSED)
    suspend fun forgetAllMemory(): EngineResult<Int> = EngineResult.Unavailable(NOT_EXPOSED)
    suspend fun skills(): EngineResult<List<SkillInfo>> = EngineResult.Unavailable(NOT_EXPOSED)
    suspend fun health(): EngineResult<EngineHealth> = EngineResult.Unavailable(NOT_EXPOSED)
    suspend fun usage(): EngineResult<EngineUsage> = EngineResult.Unavailable(NOT_EXPOSED)

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

    // POST /v1/tools/install, /v1/auth/code e /v1/auth/token. Padrão = o motor ainda não sabe.
    suspend fun installTool(provider: String): ToolActionResult =
        ToolActionResult.Failed("O motor ainda não sabe instalar programas.", "Atualize o AURA no Termux e tente de novo.")
    suspend fun submitAuthCode(provider: String, code: String): ToolActionResult =
        ToolActionResult.Failed("O motor ainda não recebe o código de login.", "Atualize o AURA no Termux e tente de novo.")
    suspend fun submitAuthToken(provider: String, token: String): ToolActionResult =
        ToolActionResult.Failed("O motor ainda não recebe token.", "Atualize o AURA no Termux e tente de novo.")
}
