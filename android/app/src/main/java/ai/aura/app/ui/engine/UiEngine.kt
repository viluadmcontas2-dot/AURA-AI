package ai.aura.app.ui.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

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

/** [build]: engine_build do /v1/health (sha256 curto do código do motor); null = motor velho, que não informa. */
data class EngineHealth(val service: String, val version: Int, val build: String? = null)

data class EngineUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val reportedJobs: Int,
    val unreportedJobs: Int,
)

private const val NOT_EXPOSED = "Este AURA Engine ainda não expõe isso."

/** Motivo de [EngineResult.Unavailable] quando o motor é de uma versão que ainda não tem a rota (quem chama cai no modo antigo). */
const val ENGINE_NOT_EXPOSED: String = NOT_EXPOSED

/* ---- Conversa (POST /v1/chat, GET /v1/chat/{id}, /v1/chats, POST /v1/intent) ----
 * O chat não cria job: manda a mensagem, recebe o texto aos poucos e termina. A UI só vê texto em
 * português; nada de rota, aprovação ou JSON do motor. */

/** Resposta de POST /v1/chat. [sessionId] é o que o motor usa para continuar a conversa (--resume). */
data class ChatStarted(val chatId: String, val sessionId: String?, val requestId: String? = null, val state: String? = null)

/** O que o dono pode fazer para resolver um erro da conversa. Os rótulos são sempre os mesmos. */
enum class ChatAction { Retry, OpenConnections, StartEngine }

/** Erro de conversa já em português simples (message_pt do motor ou texto do app). */
data class ChatFailure(val code: String, val message: String, val action: ChatAction)

/**
 * Andamento de uma resposta (evento chat.status ou GET /v1/chat/{id}). [state]: queued, running, awaiting_consent,
 * stopping, done, error ou interrupted. [phase]: starting, waiting_claude, reading_files, editing_files, running_tool,
 * responding ou unknown. Tempos em ms; datas em ms desde 1970 (null = o motor não informou). [seq] só cresce por execução.
 */
data class ChatStatus(
    val chatId: String,
    val sessionId: String?,
    val seq: Long,
    val state: String,
    val phase: String,
    val elapsedMs: Long,
    val lastOutputAtMillis: Long?,
    val heartbeatAtMillis: Long?,
    val canStop: Boolean,
) {
    val isTerminal: Boolean get() = state == "done" || state == "error" || state == "interrupted"
}

/** Retrato completo de uma execução (GET /v1/chat/{id} e /v1/chat/requests/{request_id}). [failure] só nos estados de erro. */
data class ChatSnapshot(val status: ChatStatus, val requestId: String?, val text: String, val failure: ChatFailure?)

/** Eventos de uma resposta. [Text] traz o texto acumulado inteiro até agora (nunca só o pedaço). */
sealed interface ChatEvent {
    /** Batimento do motor (chat.status): a fase atual e há quanto tempo a execução corre. */
    data class Status(val status: ChatStatus) : ChatEvent
    data class Text(val text: String) : ChatEvent
    data class Tool(val summary: String) : ChatEvent
    data class Done(val text: String, val sessionId: String?) : ChatEvent
    data class Failed(val failure: ChatFailure) : ChatEvent
}

data class ChatSummary(val id: String, val title: String, val updatedAtMillis: Long)

/** Uma mensagem de uma conversa guardada no motor. */
data class ChatLine(val fromUser: Boolean, val text: String)

/** Uma das duas respostas possíveis quando o app não tem certeza do que o dono quis. */
data class IntentOption(val label: String, val kind: String)

/** O que o motor entendeu de um texto (POST /v1/intent). Qualquer falha vale como [Chat]. */
sealed interface IntentResult {
    data object Chat : IntentResult
    data class Command(val name: String) : IntentResult
    data class Reform(val idea: String) : IntentResult
    data class Project(val idea: String) : IntentResult
    /** Trabalho longo pedido de forma explícita; no chat vira conversa normal. */
    data class Task(val text: String) : IntentResult
    /** Ambiguidade real: UMA pergunta curta e duas opções. */
    data class Ask(val question: String, val options: List<IntentOption>) : IntentResult
}

/** O motor é de uma versão que ainda não conversa (404/501 em /v1/chat). */
class ChatUnsupportedException(message: String = "Este motor ainda não conversa.") : Exception(message)

/* ---- Sessões do Claude Code (GET /v1/sessions, /v1/processes) ----
 * Conversas que o Claude Code guarda no Termux (as do AURA e as do terminal). A tela fala de
 * "conversa" e "sessão"; nunca de job, rota ou session_id. */

/** Uma conversa do Claude Code. [fromTerminal]: nasceu no terminal do Termux; senão foi iniciada pelo AURA. */
data class SessionInfo(
    val id: String,
    val title: String,
    /** Últimas duas pastas do projeto ("meu/projeto"); vazio se o motor não soube. */
    val project: String,
    val updatedAtMillis: Long,
    val messages: Int,
    val fromTerminal: Boolean,
    /** Há uma resposta sendo escrita agora. */
    val running: Boolean,
)

enum class SessionLineRole { User, Assistant, Tool }

/** Uma linha do histórico de uma sessão. [Tool] é uma frase curta ("Lendo arquivos…"), nunca argumento. */
data class SessionLine(val role: SessionLineRole, val text: String)

/** A resposta mais recente de uma conversa, como o motor a conhece. */
data class LastChat(val chatId: String, val state: String, val requestId: String?)

/**
 * Estado barato de uma sessão (GET /v1/sessions/{id}/state). [version] muda quando o histórico muda; [running] é
 * "o motor está respondendo agora"; [externalRunning] é "alguém escreve nesta conversa pelo terminal".
 */
data class SessionState(
    val sessionId: String,
    val version: String,
    val revision: String,
    val messages: Int,
    val total: Int,
    val updatedAtMillis: Long,
    val running: Boolean,
    val activeChatId: String?,
    val externalRunning: Boolean,
    val historyComplete: Boolean,
    val fromTerminal: Boolean,
    val active: ChatStatus?,
    val lastChat: LastChat?,
)

/** Linha de histórico com identidade estável (o mesmo id em qualquer recorte). */
data class HistoryLine(val id: String, val role: SessionLineRole, val text: String)

/**
 * Uma página do histórico (GET /v1/sessions/{id}/messages). [firstIndex] é a posição da primeira linha no histórico
 * inteiro; [previousCursor] busca a página anterior; [historyComplete] false = o comeco da fonte nao pode ser lido.
 */
data class HistoryPage(
    val lines: List<HistoryLine>,
    val previousCursor: String?,
    val hasMore: Boolean,
    val historyComplete: Boolean,
    val total: Int,
    val firstIndex: Int,
    val revision: String,
    val activeChatId: String?,
)

/** Um programa que o motor iniciou no celular. [kind]: claude, agy, gemini, codex ou engine. */
data class ProcessInfo(val pid: Long, val kind: String, val sessionId: String?, val startedAtMillis: Long?)

/** Resultado de POST /v1/prompt/improve. */
sealed interface ImproveResult {
    data class Improved(val text: String) : ImproveResult

    /** [action] nulo = nada a fazer pelo botão (ex.: o motor ainda não sabe melhorar pedidos). */
    data class Failed(val code: String, val message: String, val action: ChatAction?) : ImproveResult
}

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

    // Conversa (POST /v1/chat, GET /v1/chat/{id}, POST /v1/chat/{id}/stop, GET /v1/chats, POST /v1/intent).
    // Padrão = o motor ainda não conversa; quem falha por falta de motor lança IOException.
    suspend fun startChat(text: String, sessionId: String?, workspace: String = "default"): ChatStarted =
        throw ChatUnsupportedException()

    /**
     * Como acima, com a chave de idempotência do pedido ([requestId]: repetir a mesma chave nunca executa duas vezes) e o
     * assistente pedido ([executor]; null = o padrão do motor, hoje o Claude). O padrão ignora os dois e usa a forma antiga.
     */
    suspend fun startChat(text: String, sessionId: String?, workspace: String, requestId: String?, executor: String?): ChatStarted =
        startChat(text, sessionId, workspace)

    /** Retrato de uma execução (GET /v1/chat/{id}). [EngineResult.Unavailable] quando não dá para perguntar. */
    suspend fun chatSnapshot(chatId: String): EngineResult<ChatSnapshot> = EngineResult.Unavailable(NOT_EXPOSED)

    /** A execução que aceitou este pedido (GET /v1/chat/requests/{request_id}); [EngineResult.Rejected] com `request_not_found` se nunca foi aceito. */
    suspend fun chatByRequest(requestId: String): EngineResult<ChatSnapshot> = EngineResult.Unavailable(NOT_EXPOSED)
    /** Eventos de uma resposta, na ordem; o fluxo termina depois de [ChatEvent.Done] ou [ChatEvent.Failed]. */
    fun chatStream(chatId: String): Flow<ChatEvent> = emptyFlow()
    suspend fun stopChat(chatId: String) {}
    suspend fun chats(): List<ChatSummary> = emptyList()
    suspend fun chatMessages(id: String): List<ChatLine> = emptyList()
    suspend fun intent(text: String): IntentResult = IntentResult.Chat

    // Sessões do Claude Code e processos do motor (GET/POST/DELETE /v1/sessions, GET/POST /v1/processes).
    // Padrão = o motor ainda não expõe; falta de motor chega como EngineResult.Unavailable, nunca como exceção.
    suspend fun sessions(): EngineResult<List<SessionInfo>> = EngineResult.Unavailable(NOT_EXPOSED)
    suspend fun sessionMessages(id: String): EngineResult<List<SessionLine>> = EngineResult.Unavailable(NOT_EXPOSED)
    /** Estado barato da sessão e a próxima página do histórico (GET /v1/sessions/{id}/state e /messages). Padrão = motor antigo. */
    suspend fun sessionState(id: String): EngineResult<SessionState> = EngineResult.Unavailable(NOT_EXPOSED)
    suspend fun sessionPage(id: String, limit: Int = 100, before: String? = null, after: Int? = null): EngineResult<HistoryPage> =
        EngineResult.Unavailable(NOT_EXPOSED)
    /** Avisa o motor que esta sessão está aberta (POST /v1/sessions/{id}/watch): ele passa a emitir session.updated. */
    suspend fun watchSession(id: String): EngineResult<Unit> = EngineResult.Unavailable(NOT_EXPOSED)
    suspend fun renameSession(id: String, title: String): EngineResult<Unit> = EngineResult.Unavailable(NOT_EXPOSED)
    /** Esconde só no AURA: o histórico do Claude Code no terminal nunca é apagado. */
    suspend fun hideSession(id: String): EngineResult<Unit> = EngineResult.Unavailable(NOT_EXPOSED)
    suspend fun stopSession(id: String): EngineResult<Unit> = EngineResult.Unavailable(NOT_EXPOSED)
    suspend fun processes(): EngineResult<List<ProcessInfo>> = EngineResult.Unavailable(NOT_EXPOSED)
    suspend fun stopProcess(pid: Long): EngineResult<Unit> = EngineResult.Unavailable(NOT_EXPOSED)

    // Melhorar pedido e a chave do Gemini (POST /v1/prompt/improve, /v1/settings/gemini_key). A chave só
    // viaja do campo para o motor: o app nunca a guarda, mostra nem registra.
    suspend fun improvePrompt(text: String): ImproveResult =
        ImproveResult.Failed("unsupported", "Este motor ainda não melhora pedidos. Atualize o AURA no Termux.", null)
    suspend fun geminiKeyConfigured(): EngineResult<Boolean> = EngineResult.Unavailable(NOT_EXPOSED)
    suspend fun saveGeminiKey(key: String): EngineResult<Unit> = EngineResult.Unavailable(NOT_EXPOSED)
    suspend fun removeGeminiKey(): EngineResult<Unit> = EngineResult.Unavailable(NOT_EXPOSED)

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
