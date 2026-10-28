package ai.aura.app.ui.screens.sessions

import ai.aura.app.offline.LocalStore
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.EngineResult
import ai.aura.app.ui.engine.ProcessInfo
import ai.aura.app.ui.engine.SessionInfo
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class SessionsNotice(val ok: Boolean, val text: String)

/** O painel aberto embaixo de uma sessão: as ações, o campo de novo nome ou a confirmação de esconder. */
internal sealed interface SessionPanel {
    val id: String

    data class Actions(override val id: String) : SessionPanel
    data class Rename(override val id: String) : SessionPanel
    data class ConfirmHide(override val id: String) : SessionPanel
}

private const val ENGINE_DOWN_FIX = "O motor está desligado. Inicie o motor e tente de novo."

/**
 * Estado da tela Sessões. Lê as sessões e os programas do motor; quando ele não responde, mostra as sessões
 * que já tinha visto (LocalStore) e diz isso. Nada aqui lança por falta de motor, e nenhuma leitura fica
 * esperando para sempre (limite de [timeoutMillis]). Roda no [scope] da composição.
 */
@Stable
internal class SessionsController(
    private val gateway: EngineGateway,
    private val scope: CoroutineScope,
    private val store: LocalStore? = null,
    private val timeoutMillis: Long = 8_000L,
) {
    var loading by mutableStateOf(true); private set
    var sessions by mutableStateOf<List<SessionInfo>>(emptyList()); private set
    var processes by mutableStateOf<List<ProcessInfo>>(emptyList()); private set

    /** A última leitura das sessões falhou: o que está na tela é o que ficou guardado. */
    var offline by mutableStateOf(false); private set

    /** Motivo curto vindo do motor (ou do app) para a última falha de leitura. */
    var problem by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<SessionsNotice?>(null); private set
    var panel by mutableStateOf<SessionPanel?>(null); private set
    var busy by mutableStateOf(false); private set

    fun reload() {
        scope.launch { load() }
    }

    suspend fun load() {
        val read = result { gateway.sessions() }
        val running = result { gateway.processes() }
        when (read) {
            is EngineResult.Ok -> {
                sessions = read.value
                offline = false
                problem = null
                store?.let { s -> withContext(Dispatchers.IO) { runCatching { s.saveSessions(read.value) } } }
            }
            else -> {
                offline = true
                problem = when (read) {
                    is EngineResult.Unavailable -> read.reason
                    is EngineResult.Rejected -> read.message
                    else -> null
                }
                if (sessions.isEmpty()) {
                    sessions = store?.let { s -> withContext(Dispatchers.IO) { runCatching { s.loadSessions() }.getOrDefault(emptyList()) } }.orEmpty()
                }
            }
        }
        processes = (running as? EngineResult.Ok)?.value.orEmpty()
        loading = false
    }

    private suspend fun <T> result(call: suspend () -> EngineResult<T>): EngineResult<T> = try {
        withTimeoutOrNull(timeoutMillis) { call() } ?: EngineResult.Unavailable("O motor demorou a responder.")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        EngineResult.Unavailable(e.message ?: "O motor não respondeu.")
    }

    // ---- painel de uma sessão ----

    fun toggleActions(id: String) {
        notice = null
        panel = if (panel is SessionPanel.Actions && panel?.id == id) null else SessionPanel.Actions(id)
    }

    fun startRename(id: String) { panel = SessionPanel.Rename(id) }

    fun askHide(id: String) { panel = SessionPanel.ConfirmHide(id) }

    fun closePanel() { panel = null }

    fun dismissNotice() { notice = null }

    // ---- ações ----

    fun rename(id: String, title: String) {
        val name = title.trim()
        if (name.isEmpty()) {
            notice = SessionsNotice(false, "Escreva um nome para a conversa e toque em Salvar nome.")
            return
        }
        act("Nome salvo.", "Não consegui salvar o nome.") { gateway.renameSession(id, name) }
    }

    fun hide(session: SessionInfo) = act(
        if (session.fromTerminal) "Conversa escondida no AURA. No terminal ela continua." else "Conversa escondida.",
        "Não consegui esconder a conversa.",
    ) { gateway.hideSession(session.id) }

    fun stop(id: String) = act("Parei a resposta.", "Não consegui parar a resposta.") { gateway.stopSession(id) }

    fun stopProcess(pid: Long, label: String) =
        act("Parei $label.", "Não consegui parar $label.") { gateway.stopProcess(pid) }

    private fun act(done: String, failed: String, call: suspend () -> EngineResult<Unit>) {
        if (busy) return
        busy = true
        notice = null
        scope.launch {
            val r = result(call)
            busy = false
            when (r) {
                is EngineResult.Ok -> {
                    panel = null
                    notice = SessionsNotice(true, done)
                    load()
                }
                is EngineResult.Rejected -> notice = SessionsNotice(false, r.message)
                is EngineResult.Unavailable -> notice = SessionsNotice(false, "$failed $ENGINE_DOWN_FIX")
            }
        }
    }
}
