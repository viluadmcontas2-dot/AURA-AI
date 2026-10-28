package ai.aura.app.ui.screens.chat

import ai.aura.app.offline.LocalStore
import ai.aura.app.ui.engine.EngineEvent
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobRequest
import ai.aura.app.ui.engine.JobStatus
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Máquina de estados do chat: route -> (usuário confirma) -> submit -> acompanhar eventos/jobs
 * -> approve/cancel. Tudo que aparece como status vem do [EngineGateway]; a UI nunca presume
 * sucesso. Roda no [scope] da composição (cancela ao sair da tela).
 */
@Stable
class ChatController(
    private val gateway: EngineGateway,
    private val scope: CoroutineScope,
    initial: ChatEntry,
    private val onEntryChange: (ChatEntry) -> Unit = {},
    private val newId: () -> String = { "req_" + UUID.randomUUID().toString().replace("-", "").take(16) },
    private val pollMillis: Long = 2_000L,
    /** Onde o histórico fica no disco (null = não grava). */
    private val store: LocalStore? = null,
) {
    var phase by mutableStateOf<ChatPhase>(
        if (initial.jobId.isNotEmpty() || initial.prompt.isNotEmpty()) ChatPhase.Routing else ChatPhase.Idle,
    ); private set
    var messages by mutableStateOf<List<ChatMessage>>(emptyList()); private set
    var error by mutableStateOf<ChatError?>(null); private set
    /** true quando o motor não responde durante o acompanhamento (continua tentando). */
    var connectionLost by mutableStateOf(false); private set
    /** true enquanto aprovar/cancelar está em voo (evita toque duplo). */
    var busy by mutableStateOf(false); private set
    var events by mutableStateOf<List<EngineEvent>>(emptyList()); private set

    private var entry = initial
    private var requestId = initial.requestId
    private var lastRoute: ChatPhase.Understood? = null
    private var lastRequest: JobRequest? = null
    private var nextMessageId = 0
    private var trackJob: kotlinx.coroutines.Job? = null

    init {
        // Adiado: persist() escreve estado da Home e não pode rodar durante a composição.
        scope.launch {
            // Reabrindo a conversa (pedido já salvo), o histórico volta do disco, fora da thread principal.
            if (store != null && initial.prompt.isNotEmpty() && (initial.requestId.isNotEmpty() || initial.jobId.isNotEmpty())) {
                val saved = withContext(Dispatchers.IO) { store.loadChat() }
                if (saved.isNotEmpty() && messages.isEmpty()) {
                    messages = saved
                    nextMessageId = saved.maxOf { it.id } + 1
                }
            }
            when {
                initial.jobId.isNotEmpty() -> restoreJob(initial.jobId, initial.prompt)
                initial.prompt.isNotEmpty() -> ask(initial.prompt, initial.prompt, keepRequestId = true)
            }
        }
    }

    // ---- entrada do usuário -------------------------------------------------------------

    /** Retorna true se o texto foi aceito (o composer só limpa nesse caso). */
    fun send(raw: String): Boolean {
        val text = raw.trim()
        if (text.isEmpty()) return false
        val current = (phase as? ChatPhase.Tracking)?.job
        if (current != null && !current.status.isTerminal) {
            addMessage(false, "Já estou trabalhando em \"${current.title}\". Pare a tarefa para começar outra.")
            return false
        }
        if (phase is ChatPhase.Routing || phase is ChatPhase.Submitting) return false
        val command = ChatCommands.find(text)
        if (command != null) {
            val args = text.substringAfter(' ', "").trim()
            if (command.name == ChatCommands.CONTINUE) {
                addMessage(true, text)
                continueLast()
            } else {
                ask(command.expand(args), text)
            }
        } else {
            ask(text, text)
        }
        return true
    }

    /** "Começar": envia o pedido já entendido. Nada roda antes deste toque. */
    fun start() {
        val understood = phase as? ChatPhase.Understood ?: return
        if (understood.blocked != null) return
        submit(understood.route.request)
    }

    /** "Ajustar pedido": devolve o texto para o composer e descarta o plano. */
    fun adjust(): String {
        val text = messages.lastOrNull { it.fromUser }?.text.orEmpty()
        if (messages.lastOrNull()?.fromUser == true) { messages = messages.dropLast(1); saveChat() }
        phase = ChatPhase.Idle
        error = null
        lastRoute = null
        persist(prompt = "", jobId = "")
        return text
    }

    fun approve() {
        val job = (phase as? ChatPhase.Tracking)?.job ?: return
        if (job.status != JobStatus.AwaitingApproval || busy) return
        act { setJob(gateway.approve(job.id), restart = false) }
    }

    fun cancel() {
        val job = (phase as? ChatPhase.Tracking)?.job ?: return
        if (job.status.isTerminal || busy) return
        act { setJob(gateway.cancel(job.id), restart = false) }
    }

    /** Falhou/cancelou: nova tentativa com novo requestId (o antigo já tem desfecho). */
    fun retryJob() {
        val job = (phase as? ChatPhase.Tracking)?.job ?: return
        requestId = newId()
        submit(lastRequest ?: JobRequest(job.kind, job.title))
    }

    /** Volta ao chat em branco (a tarefa anterior continua no histórico do engine). */
    fun newRequest() {
        trackJob?.cancel()
        phase = ChatPhase.Idle
        messages = emptyList()
        saveChat()
        error = null
        events = emptyList()
        connectionLost = false
        lastRoute = null
        lastRequest = null
        requestId = ""
        persist(prompt = "", jobId = "", requestId = "")
    }

    // ---- fluxo --------------------------------------------------------------------------

    private fun ask(routeText: String, display: String, keepRequestId: Boolean = false) {
        requestId = if (keepRequestId && requestId.isNotEmpty()) requestId else newId()
        if (!(keepRequestId && messages.isNotEmpty())) addMessage(true, display)
        error = null
        events = emptyList()
        persist(prompt = routeText, jobId = "")
        runRoute(routeText)
    }

    private fun runRoute(text: String) {
        scope.launch {
            phase = ChatPhase.Routing
            error = null
            try {
                val route = gateway.route(text)
                val blocked = try {
                    gateway.capabilities().firstOrNull { it.id == route.request.kind && !it.available }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                lastRequest = route.request
                ChatPhase.Understood(route, blocked).also { lastRoute = it; phase = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                phase = ChatPhase.Idle
                error = failure(e) { runRoute(text) }
            }
        }
    }

    private fun submit(request: JobRequest) {
        phase = ChatPhase.Submitting // síncrono: bloqueia "Começar" duplicado
        scope.launch {
            error = null
            try {
                setJob(gateway.submit(requestId, request), restart = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                phase = lastRoute ?: ChatPhase.Idle
                error = failure(e) { submit(request) }
            }
        }
    }

    private fun continueLast() {
        scope.launch {
            phase = ChatPhase.Routing
            try {
                val running = gateway.jobs().firstOrNull { !it.status.isTerminal }
                if (running == null) {
                    phase = ChatPhase.Idle
                    addMessage(false, "Não há nada em andamento para continuar. Diga o que você quer fazer.")
                } else {
                    setJob(running, restart = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                phase = ChatPhase.Idle
                error = failure(e) { continueLast() }
            }
        }
    }

    private fun restoreJob(id: String, prompt: String) {
        if (prompt.isNotEmpty() && messages.isEmpty()) addMessage(true, prompt) // já veio do disco = não repete
        scope.launch {
            phase = ChatPhase.Routing
            try {
                val job = gateway.jobs().firstOrNull { it.id == id }
                if (job == null) {
                    phase = ChatPhase.Idle
                    persist(jobId = "")
                    addMessage(false, "Não encontrei mais essa tarefa. Faça um novo pedido.")
                } else {
                    setJob(job, restart = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                phase = ChatPhase.Idle
                error = failure(e) { restoreJob(id, "") }
            }
        }
    }

    private fun act(block: suspend () -> Unit) {
        busy = true // síncrono: um segundo toque antes do launch rodar já encontra busy
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = failure(e) { error = null }
            } finally {
                busy = false
            }
        }
    }

    private fun setJob(job: Job, restart: Boolean) {
        phase = ChatPhase.Tracking(job)
        error = null
        persist(jobId = job.id)
        if (restart || trackJob?.isActive != true) {
            trackJob?.cancel()
            trackJob = scope.launch { track(job.id) }
        }
    }

    /** Eventos acordam a tela; o polling cobre eventos perdidos. Para ao chegar em estado final. */
    private suspend fun track(id: String) = coroutineScope {
        val collector = launch {
            try {
                gateway.events.collect {
                    if (it.jobId == id) {
                        events = (events + it).distinctBy { e -> e.id }
                        refresh(id)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // O polling abaixo continua acompanhando.
            }
        }
        while (true) {
            val job = (phase as? ChatPhase.Tracking)?.job
            if (job == null || job.id != id || job.status.isTerminal) break
            delay(pollMillis)
            refresh(id)
        }
        collector.cancel()
    }

    private suspend fun refresh(id: String) {
        try {
            val job = gateway.jobs().firstOrNull { it.id == id }
            connectionLost = false
            val current = (phase as? ChatPhase.Tracking)?.job
            if (job != null && current?.id == id) phase = ChatPhase.Tracking(job)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            connectionLost = true
        }
    }

    // ---- util ---------------------------------------------------------------------------

    private fun addMessage(fromUser: Boolean, text: String) {
        messages = messages + ChatMessage(nextMessageId++, fromUser, text)
        saveChat()
    }

    // shortcut: gravações seguidas rodam em paralelo e podem terminar fora de ordem; o histórico é pequeno, serialize se virar problema.
    private fun saveChat() {
        val s = store ?: return
        val snapshot = messages
        scope.launch(Dispatchers.IO + NonCancellable) { s.saveChat(snapshot) }
    }

    private fun persist(prompt: String? = null, jobId: String? = null, requestId: String? = null) {
        entry = entry.copy(
            open = true,
            prompt = prompt ?: entry.prompt,
            jobId = jobId ?: entry.jobId,
            requestId = requestId ?: this.requestId,
            draft = "",
        )
        onEntryChange(entry)
    }

    private fun failure(e: Exception, retry: () -> Unit): ChatError =
        if (e is IOException) {
            ChatError(
                "Sem conexão com o Termux",
                "Abra o Termux, confirme que o AURA está ativo e toque em Tentar de novo. O que você escreveu foi mantido.",
                retry,
            )
        } else {
            ChatError(
                "Não deu certo",
                "O AURA não conseguiu processar. Toque em Tentar de novo; se repetir, ajuste o pedido.",
                retry,
            )
        }

    /** Limpa o aviso de erro atual (botão de fechar/ao digitar). */
    fun dismissError() { error = null }
}
