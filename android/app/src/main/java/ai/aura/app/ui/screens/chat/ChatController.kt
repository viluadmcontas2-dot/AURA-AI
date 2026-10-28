package ai.aura.app.ui.screens.chat

import ai.aura.app.integration.AuraEngineException
import ai.aura.app.offline.EngineUnreachableException
import ai.aura.app.offline.LocalStore
import ai.aura.app.offline.OfflineReason
import ai.aura.app.ui.engine.ChatAction
import ai.aura.app.ui.engine.ChatEvent
import ai.aura.app.ui.engine.ChatUnsupportedException
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.JobStatus
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Conversa de verdade: a mensagem vai direto ao motor (POST /v1/chat) e a resposta chega em texto,
 * aos poucos. Não há plano, rota, aprovação nem tarefa na tela. Comandos "/" são do app: abrem uma
 * tela e nunca vão à IA. Sem motor, a mensagem fica salva e sai sozinha quando ele volta.
 * Roda no [scope] da composição (cancela ao sair da tela).
 */
@Stable
class ChatController(
    private val gateway: EngineGateway,
    private val scope: CoroutineScope,
    initial: ChatEntry,
    private val onEntryChange: (ChatEntry) -> Unit = {},
    /** Onde histórico, conversa e rascunho ficam no disco (null = não grava). */
    private val store: LocalStore? = null,
    /** Abre uma tela do app; o texto é o que veio depois do comando (pode ser vazio). */
    private val onNavigate: (AppTarget, String) -> Unit = { _, _ -> },
    /** "Iniciar o motor": mesma ação do aviso global, conforme o motivo. */
    private val onStartEngine: (OfflineReason) -> Unit = {},
    private val retryMillis: Long = 3_000L,
) {
    var messages by mutableStateOf<List<ChatMessage>>(emptyList()); private set

    /** Não nulo enquanto o AURA responde (inclui "digitando", com texto vazio). */
    var live by mutableStateOf<LiveReply?>(null); private set
    var notice by mutableStateOf<ChatNotice?>(null); private set

    val responding: Boolean get() = live != null

    private var entry = initial
    private var sessionId = initial.sessionId
    private var chatId = ""
    private var lastUserText = ""
    private var pendingText = ""
    private var engineReason = OfflineReason.EngineStopped
    private var nextMessageId = 0
    private var streamJob: kotlinx.coroutines.Job? = null
    private var waiter: kotlinx.coroutines.Job? = null
    private var resending = false
    // Uma fila só para o disco: as gravações saem na ordem em que foram pedidas e sobrevivem a sair da tela.
    private val disk = java.util.concurrent.ThreadPoolExecutor(
        0, 1, 5, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue(),
    ) { r -> Thread(r, "aura-chat-disco").apply { isDaemon = true } }

    private fun writeLater(block: () -> Unit) {
        disk.execute { runCatching(block) }
    }

    init {
        // Adiado: onEntryChange escreve estado da Home e não pode rodar durante a composição.
        scope.launch {
            restoreLocal()
            when {
                initial.jobId.isNotEmpty() -> { consumeEntry(); showJob(initial.jobId) }
                initial.prompt.isNotEmpty() -> { consumeEntry(); send(initial.prompt) }
                pendingText.isNotEmpty() -> {
                    notice = ChatNotice("O motor está desligado.", ChatAction.StartEngine)
                    startWaiter()
                }
                else -> syncFromEngine()
            }
        }
    }

    // ---- entrada da pessoa --------------------------------------------------------------

    /** Retorna true se o texto foi aceito (o composer só limpa nesse caso). */
    fun send(raw: String): Boolean {
        val text = raw.trim()
        if (text.isEmpty() || live != null) return false
        if (ChatCommands.isCommandText(text)) {
            runCommand(text)
            return true
        }
        setPending("")
        addMessage(true, text)
        respond(text)
        return true
    }

    /** Comando do app: instantâneo, local, nunca vai à IA. */
    private fun runCommand(text: String) {
        notice = null
        addMessage(true, text)
        val command = ChatCommands.find(text)
        if (command == null) {
            addMessage(false, "Não conheço ${ChatCommands.wordOf(text)}. Toque em um comando da lista.")
        } else {
            addMessage(false, command.opened)
            onNavigate(command.target, text.trim().substringAfter(' ', "").trim())
        }
    }

    /** Botão "Parar": corta a resposta, guarda o que já chegou e avisa o motor. */
    fun stop() {
        val current = live ?: return
        val id = chatId
        streamJob?.cancel()
        live = null
        if (current.text.isNotBlank()) addMessage(false, current.text)
        if (id.isNotEmpty()) {
            scope.launch {
                try {
                    gateway.stopChat(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // O texto já parou de aparecer; o motor termina sozinho.
                }
            }
        }
    }

    /** Ação do aviso atual (um botão só). */
    fun act() {
        when (notice?.action) {
            ChatAction.Retry -> retry()
            ChatAction.OpenConnections -> onNavigate(AppTarget.Connections, "")
            ChatAction.StartEngine -> onStartEngine(engineReason)
            null -> Unit
        }
    }

    /** Tenta de novo a última mensagem, sem repetir a bolha da pessoa. */
    fun retry() {
        val text = lastUserText
        if (text.isEmpty() || live != null) return
        respond(text)
    }

    /** O motor voltou (o aviso global ficou verde): manda o que estava guardado. */
    fun engineBack() = resendNow()

    /** Começa uma conversa nova: some o histórico daqui, o motor guarda o antigo. */
    fun newConversation() {
        stop()
        waiter?.cancel()
        messages = emptyList()
        notice = null
        sessionId = ""
        chatId = ""
        lastUserText = ""
        nextMessageId = 0
        setPending("")
        entry = entry.copy(open = true, sessionId = "")
        onEntryChange(entry)
        persistMessages()
        persistSession()
    }

    // ---- fluxo --------------------------------------------------------------------------

    private fun respond(text: String) {
        lastUserText = text
        notice = null
        chatId = ""
        live = LiveReply("", emptyList()) // síncrono: bloqueia envio duplicado
        streamJob = scope.launch { converse(text, silent = false) }
    }

    /** [silent]: reenvio de mensagem guardada; falhar de novo não muda a tela. */
    private suspend fun converse(text: String, silent: Boolean) {
        val started = try {
            gateway.startChat(text, sessionId.ifEmpty { null })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!silent) fail(e, text)
            return
        }
        setPending("")
        if (silent) {
            notice = null
            live = LiveReply("", emptyList())
        }
        chatId = started.chatId
        started.sessionId?.let { adoptSession(it) }
        try {
            gateway.chatStream(started.chatId).collect { handle(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finishWithNotice(ChatNotice("Perdi a conexão com o motor no meio da resposta.", ChatAction.Retry))
            return
        }
        // O fluxo acabou sem fim: guarda o que veio, sem fingir que terminou.
        if (live != null) finishWithNotice(ChatNotice("A resposta parou antes de terminar.", ChatAction.Retry))
    }

    private fun handle(event: ChatEvent) {
        val current = live ?: return
        when (event) {
            is ChatEvent.Text -> live = LiveReply(event.text, current.tools)
            is ChatEvent.Tool ->
                live = LiveReply(current.text, current.tools + LiveReply.ToolMark(current.text.length, event.summary))
            is ChatEvent.Done -> {
                event.sessionId?.let { adoptSession(it) }
                val finalText = event.text.ifBlank { current.text }
                live = null
                if (finalText.isBlank()) notice = ChatNotice("Não recebi resposta desta vez.", ChatAction.Retry)
                else addMessage(false, finalText)
            }
            is ChatEvent.Failed -> {
                if (current.text.isNotBlank()) addMessage(false, current.text)
                live = null
                // "Parada" não é erro: se algo já chegou, fica o texto e nenhum aviso.
                val calm = event.failure.code == "stopped" && current.text.isNotBlank()
                notice = if (calm) null else ChatNotice(event.failure.message, event.failure.action)
            }
        }
    }

    private fun finishWithNotice(n: ChatNotice) {
        val partial = live?.text.orEmpty()
        live = null
        if (partial.isNotBlank()) addMessage(false, partial)
        notice = n
    }

    private fun fail(e: Exception, text: String) {
        live = null
        val n = noticeFor(e)
        notice = n
        if (n.action == ChatAction.StartEngine) {
            setPending(text)
            startWaiter()
        }
    }

    private fun noticeFor(e: Exception): ChatNotice = when {
        e is ChatUnsupportedException ->
            ChatNotice("Este motor ainda não conversa. Atualize o AURA no Termux.", ChatAction.OpenConnections)
        e is EngineUnreachableException && e.reason == OfflineReason.NoToken ->
            ChatNotice("O AURA ainda não está conectado ao Termux.", ChatAction.OpenConnections)
        e is AuraEngineException && e.httpStatus == 401 ->
            ChatNotice("O AURA ainda não está conectado ao Termux.", ChatAction.OpenConnections)
        e is EngineUnreachableException -> {
            engineReason = e.reason
            ChatNotice("O motor está desligado.", ChatAction.StartEngine)
        }
        e is IOException -> ChatNotice("O motor está desligado.", ChatAction.StartEngine)
        else -> ChatNotice("Não consegui responder agora.", ChatAction.Retry)
    }

    // ---- mensagem guardada (motor desligado) --------------------------------------------

    private fun startWaiter() {
        if (waiter?.isActive == true) return
        waiter = scope.launch {
            while (pendingText.isNotEmpty()) {
                delay(retryMillis)
                resendNow()
            }
        }
    }

    private fun resendNow() {
        val text = pendingText
        if (text.isEmpty() || live != null || resending) return
        resending = true
        streamJob = scope.launch {
            try {
                converse(text, silent = true)
            } finally {
                resending = false
            }
        }
    }

    private fun setPending(text: String) {
        pendingText = text
        val s = store ?: return
        writeLater { s.savePending(text) }
    }

    // ---- disco e motor ------------------------------------------------------------------

    private suspend fun restoreLocal() {
        val s = store ?: return
        val saved = withContext(Dispatchers.IO) { Triple(s.loadChat(), s.loadSessionId(), s.loadPending()) }
        if (messages.isEmpty() && saved.first.isNotEmpty()) {
            messages = saved.first
            nextMessageId = saved.first.maxOf { it.id } + 1
        }
        if (sessionId.isEmpty()) sessionId = saved.second
        if (pendingText.isEmpty()) pendingText = saved.third
    }

    /** O motor é a fonte da verdade da conversa; sem ele vale o que está no disco. */
    private suspend fun syncFromEngine() {
        try {
            val id = sessionId.ifEmpty { if (messages.isEmpty()) gateway.chats().firstOrNull()?.id.orEmpty() else "" }
            if (id.isEmpty()) return
            val lines = gateway.chatMessages(id)
            if (lines.isEmpty() || live != null || pendingText.isNotEmpty()) return
            messages = lines.mapIndexed { i, l -> ChatMessage(i, l.fromUser, l.text) }
            nextMessageId = lines.size
            if (sessionId.isEmpty()) adoptSession(id)
            persistMessages()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Sem motor: fica o que está no disco.
        }
    }

    /** Resumo de um trabalho da Atividade, em texto. O andamento fica na aba Atividade. */
    private suspend fun showJob(id: String) {
        try {
            val job = gateway.jobs().firstOrNull { it.id == id }
            if (job == null) {
                addMessage(false, "Não encontrei mais esse trabalho. Veja a lista na aba Atividade.")
                return
            }
            val result = job.result?.takeIf {
                it.isNotBlank() && !it.trimStart().startsWith("{") && !it.trimStart().startsWith("[")
            }
            val line = when (job.status) {
                JobStatus.Completed -> result ?: "terminou."
                JobStatus.Failed -> "não deu certo."
                JobStatus.Cancelled -> "foi cancelado."
                else -> "ainda está em andamento. Acompanhe na aba Atividade."
            }
            addMessage(false, "${job.title}: $line")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addMessage(false, "Não consegui falar com o motor agora. Veja o trabalho na aba Atividade.")
        }
    }

    // ---- util ---------------------------------------------------------------------------

    private fun addMessage(fromUser: Boolean, text: String) {
        messages = messages + ChatMessage(nextMessageId++, fromUser, text)
        persistMessages()
    }

    private fun persistMessages() {
        val s = store ?: return
        val snapshot = messages
        writeLater { s.saveChat(snapshot) }
    }

    private fun persistSession() {
        val s = store ?: return
        val id = sessionId
        writeLater { s.saveSessionId(id) }
    }

    private fun adoptSession(id: String) {
        if (id == sessionId) return
        sessionId = id
        entry = entry.copy(open = true, sessionId = id)
        onEntryChange(entry)
        persistSession()
    }

    private fun consumeEntry() {
        entry = entry.copy(open = true, prompt = "", jobId = "", draft = "")
        onEntryChange(entry)
    }
}
