package ai.aura.app.ui.screens.chat

import ai.aura.app.integration.AuraEngineException
import ai.aura.app.integration.chatFailureFor
import ai.aura.app.offline.EngineUnreachableException
import ai.aura.app.offline.LocalStore
import ai.aura.app.offline.OfflineReason
import ai.aura.app.ui.engine.ChatAction
import ai.aura.app.ui.engine.ChatEvent
import ai.aura.app.ui.engine.ChatFailure
import ai.aura.app.ui.engine.ChatSnapshot
import ai.aura.app.ui.engine.ChatStatus
import ai.aura.app.ui.engine.ChatUnsupportedException
import ai.aura.app.ui.engine.ENGINE_NOT_EXPOSED
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.EngineResult
import ai.aura.app.ui.engine.HistoryLine
import ai.aura.app.ui.engine.JobStatus
import ai.aura.app.ui.engine.SessionLineRole
import ai.aura.app.ui.engine.SessionState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Uma conversa de verdade, e ela é uma VISÃO do que o motor sabe:
 *  - o que a tela mostra = histórico da sessão no motor + resposta em andamento + mensagens daqui ainda não confirmadas;
 *  - "respondendo agora" vem do estado do motor (GET /v1/sessions/{id}/state), não só de um fluxo de eventos: o fluxo
 *    (eventos e GET /v1/chat/{id}) é apenas um acelerador. Um laço independente confere o motor a cada 2 s durante uma
 *    resposta, então é impossível ficar em "Pensando" depois que o motor terminou;
 *  - Enviar nunca bloqueia: durante uma resposta a mensagem entra na fila e sai sozinha, uma por vez;
 *  - o controlador vive no [ChatHub] (além da tela): sair e voltar não perde nem reinicia nada.
 *
 * Comandos "/" são do app: abrem uma tela e nunca vão à IA. Texto comum vai direto ao motor (que responde com o Claude).
 * Roda no [scope] dado (em produção, o do hub, na thread principal).
 */
@Stable
class ChatController(
    gateway: EngineGateway,
    private val scope: CoroutineScope,
    initial: ChatEntry,
    onEntryChange: (ChatEntry) -> Unit = {},
    /** Onde histórico, fila e conversa ficam no disco, um arquivo por conversa (null = não grava). */
    private val store: LocalStore? = null,
    /** Abre uma tela do app; o texto é o que veio depois do comando (pode ser vazio). */
    onNavigate: (AppTarget, String) -> Unit = { _, _ -> },
    /** "Iniciar o motor": mesma ação do aviso global, conforme o motivo. */
    onStartEngine: (OfflineReason) -> Unit = {},
    private val retryMillis: Long = 3_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val timing: ChatTiming = ChatTiming(),
    /** O hub quer saber quando a conversa ganha (ou perde) o id do motor. */
    private val onIdentity: (String) -> Unit = {},
) {
    var messages by mutableStateOf<List<ChatMessage>>(emptyList()); private set

    /** Mensagens daqui que ainda não saíram, em ordem: "Na fila", "Aguardando o motor" ou "Não enviada". */
    var queue by mutableStateOf<List<QueuedMessage>>(emptyList()); private set

    /** Não nulo enquanto o AURA responde (inclui "digitando", com texto vazio). */
    var live by mutableStateOf<LiveReply?>(null); private set
    var notice by mutableStateOf<ChatNotice?>(null); private set

    /** Linha discreta depois de uma parada confirmada ("Resposta interrompida. O texto recebido foi guardado."). */
    var info by mutableStateOf<String?>(null); private set

    /** Parar pausa a fila: "A fila está parada (2 mensagens)." com Continuar e Limpar fila. */
    var queuePaused by mutableStateOf(false); private set

    /** Sem nada guardado: "Carregando conversa…". Com algo guardado: [refreshing] ("Atualizando conversa…"). */
    var loading by mutableStateOf(false); private set
    var refreshing by mutableStateOf(false); private set

    /** O que está na tela é só o que ficou guardado neste celular ("Mostrando mensagens guardadas neste celular."). */
    var fromCache by mutableStateOf(false); private set

    /** Alguém responde nesta conversa pelo terminal do Termux ("O Claude está respondendo no terminal…"). */
    var externalRunning by mutableStateOf(false); private set
    var hasOlder by mutableStateOf(false); private set
    var loadingOlder by mutableStateOf(false); private set

    /** Relógio do chat (ms), atualizado a cada segundo enquanto responde: a tela calcula "Pensando · 12 s" com ele. */
    var now by mutableStateOf(clock()); private set

    val responding: Boolean get() = live != null
    val diagnostics = ChatDiagnostics(clock)
    val timingConfig: ChatTiming get() = timing

    /** Identidade da conversa no motor ("" = ainda não criada). */
    val currentSessionId: String get() = sessionId

    private var gateway: EngineGateway = gateway
    private var onEntryChange: (ChatEntry) -> Unit = onEntryChange
    private var onNavigate: (AppTarget, String) -> Unit = onNavigate
    private var onStartEngine: (OfflineReason) -> Unit = onStartEngine

    private val cs = ConversationStore(store)
    private var entry = initial
    private var sessionId = initial.sessionId
    private var engineReason = OfflineReason.EngineStopped

    // Histórico do motor (a cauda que este celular tem) e o que se sabe dele.
    private var history: List<HistoryLine> = emptyList()
    private var historyLoaded = false
    private var historyVersion: String? = null
    private var historyRevision: String? = null
    private var historyTotal = 0
    private var historyFirstIndex = 0
    private var historyComplete = true
    private var olderCursor: String? = null

    // Mensagens daqui (fila, em envio e respondidas ainda não vistas no histórico) e linhas só do app (comandos).
    private var turns: List<Turn> = emptyList()
    private var notes: List<ChatMessage> = emptyList()
    private var posting: String? = null
    private var sendNowTarget: String? = null
    private var stopAfterAccept = false
    private var streamDead = false
    private var lostCycles = 0

    /** null = ainda não sei; false = motor antigo sem /state (modo antigo: só o fluxo da resposta). */
    private var stateSupported: Boolean? = null
    private var restored = false
    private var syncedOnce = false
    private var visible = true
    private var failures = 0
    private var legacyLoading = false

    private var streamJob: Job? = null
    private var loopJob: Job? = null
    private var tickJob: Job? = null
    private var waiter: Job? = null
    private var eventsJob: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

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
            restored = true
            when {
                initial.jobId.isNotEmpty() -> { consumeEntry(); showJob(initial.jobId) }
                initial.prompt.isNotEmpty() -> { consumeEntry(); send(initial.prompt) }
            }
            ensureLoop()
            pump() // mensagem escrita antes de o disco terminar de ler: sai agora
            if (turns.any { it.state == TurnState.WaitingEngine }) {
                notice = ChatNotice("O motor está desligado.", ChatAction.StartEngine)
                startWaiter()
            }
        }
    }

    // ---- ligação com a tela -------------------------------------------------------------

    /** A tela (re)abriu esta conversa: troca os ganchos e cuida do que a entrada traz (pedido a enviar, trabalho a mostrar). */
    fun bind(
        gateway: EngineGateway,
        entry: ChatEntry,
        onEntryChange: (ChatEntry) -> Unit,
        onNavigate: (AppTarget, String) -> Unit,
        onStartEngine: (OfflineReason) -> Unit,
    ) {
        this.gateway = gateway
        this.onEntryChange = onEntryChange
        this.onNavigate = onNavigate
        this.onStartEngine = onStartEngine
        this.entry = entry.copy(sessionId = sessionId.ifEmpty { entry.sessionId })
        scope.launch {
            if (!restored) return@launch // o início da própria construção já cuida da entrada
            when {
                entry.jobId.isNotEmpty() -> { consumeEntry(); showJob(entry.jobId) }
                entry.prompt.isNotEmpty() -> { consumeEntry(); send(entry.prompt) }
            }
        }
    }

    /** A tela saiu: os ganchos viram vazios (a composição morreu), mas a conversa continua viva. */
    fun unbind() {
        onEntryChange = {}
        onNavigate = { _, _ -> }
        onStartEngine = {}
    }

    /** Visível = confere o motor a cada poucos segundos; invisível, só enquanto houver resposta em andamento. */
    fun setVisible(value: Boolean) {
        visible = value
        if (value) {
            ensureLoop()
            wake.trySend(Unit)
        }
    }

    /** O app voltou ao primeiro plano: confere agora. */
    fun onResume() {
        diagnostics.log(null, "voltou ao app")
        ensureLoop()
        wake.trySend(Unit)
    }

    /** O motor voltou (o aviso global ficou verde): manda o que estava guardado e confere a conversa. */
    fun engineBack() {
        diagnostics.log(null, "motor de volta")
        failures = 0
        pump()
        ensureLoop()
        wake.trySend(Unit)
    }

    // ---- entrada da pessoa --------------------------------------------------------------

    /** Retorna true se o texto foi aceito (o composer só limpa nesse caso). Nunca recusa por estar respondendo. */
    fun send(raw: String, executor: String? = null): Boolean {
        val text = raw.trim()
        if (text.isEmpty()) return false
        if (ChatCommands.isCommandText(text)) {
            runCommand(text)
            return true
        }
        info = null
        val turn = Turn(UUID.randomUUID().toString(), text, clock(), executor, TurnState.Queued)
        turns = turns + turn
        diagnostics.log(null, "mensagem na fila (${turns.size})")
        persistTurns()
        publish()
        pump()
        ensureLoop()
        return true
    }

    /** Comando do app: instantâneo, local, nunca vai à IA. */
    private fun runCommand(text: String) {
        notice = null
        val command = ChatCommands.find(text)
        val reply = if (command == null) "Não conheço ${ChatCommands.wordOf(text)}. Toque em um comando da lista." else command.opened
        notes = notes + ChatMessage(notes.size, true, text) + ChatMessage(notes.size + 1, false, reply)
        publish()
        if (command != null) onNavigate(command.target, text.trim().substringAfter(' ', "").trim())
    }

    /** Botão "Parar": pede ao motor e espera a confirmação. A fila para junto (a pessoa decide continuar ou limpar). */
    fun stop() {
        val lv = live ?: return
        if (lv.stopRequestedAt > 0) return
        val turn = activeTurn()
        val id = turn?.chatId.orEmpty()
        live = lv.copy(stopRequestedAt = clock(), stopping = true)
        diagnostics.log(id, "pediu para parar")
        if (id.isEmpty()) {
            stopAfterAccept = true // o POST ainda está a caminho: para assim que o motor aceitar
            return
        }
        scope.launch {
            try {
                gateway.stopChat(id)
                diagnostics.log(id, "parada aceita pelo motor")
                if (stateSupported == false) turn?.let { finishStopped(it) } // motor antigo: não há como confirmar
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diagnostics.log(id, "parada falhou: ${e.javaClass.simpleName}")
            }
            wake.trySend(Unit)
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

    /** Um dos botões de um aviso com mais de uma escolha. */
    fun press(kind: NoticeKind) {
        when (kind) {
            NoticeKind.Retry -> retry()
            NoticeKind.OpenConnections -> onNavigate(AppTarget.Connections, "")
            NoticeKind.StartEngine -> onStartEngine(engineReason)
            NoticeKind.BackToSessions -> onNavigate(AppTarget.Sessions, "")
            NoticeKind.NewConversation -> createOtherConversation()
            NoticeKind.CheckProgress -> checkProgress()
            NoticeKind.KeepWaiting -> keepWaiting()
            NoticeKind.Stop -> stop()
        }
    }

    /** Tenta de novo a mensagem que não deu (uma nova tentativa, com chave nova), sem repetir a bolha da pessoa. */
    fun retry() {
        val failed = turns.firstOrNull { it.state == TurnState.Failed } ?: return
        if (live != null) return
        diagnostics.log(null, "tentar de novo")
        turns = turns.map {
            if (it.requestId == failed.requestId) it.copy(requestId = UUID.randomUUID().toString(), state = TurnState.Queued, baseline = null, chatId = "", reply = null) else it
        }
        notice = null
        queuePaused = false
        persistTurns()
        publish()
        pump()
    }

    /** "Verificar andamento" / "Tentar de novo" no aviso de demora: confere o motor agora. */
    fun checkProgress() {
        diagnostics.log(activeTurn()?.chatId, "verificar andamento")
        live = live?.copy(lastSignalAt = live?.lastSignalAt ?: 0L)
        failures = 0
        wake.trySend(Unit)
        ensureLoop()
    }

    /** "Continuar esperando": só adia o aviso de demora por mais um período. */
    fun keepWaiting() {
        live = live?.copy(lastProgressAt = clock())
    }

    // ---- fila ---------------------------------------------------------------------------

    /** "Enviar agora": para a resposta atual (guardando o que veio) e manda esta em seguida. */
    fun sendNow(requestId: String) {
        val target = turns.firstOrNull { it.requestId == requestId && (it.state == TurnState.Queued || it.state == TurnState.WaitingEngine) } ?: return
        diagnostics.log(null, "enviar agora")
        if (live == null && posting == null) {
            moveToFront(target.requestId)
            queuePaused = false
            pump()
            return
        }
        sendNowTarget = target.requestId
        stop()
    }

    /** "Cancelar": tira a mensagem da fila (ela ainda não saiu). */
    fun cancelQueued(requestId: String) {
        val target = turns.firstOrNull { it.requestId == requestId } ?: return
        if (target.state == TurnState.Sending || target.state == TurnState.Answered) return
        diagnostics.log(null, "mensagem cancelada")
        turns = turns.filterNot { it.requestId == requestId }
        if (sendNowTarget == requestId) sendNowTarget = null
        if (turns.none { it.state == TurnState.Queued || it.state == TurnState.WaitingEngine }) queuePaused = false
        if (target.state == TurnState.Failed) notice = null
        persistTurns()
        publish()
        pump()
    }

    /** "Continuar" depois de Parar: a fila volta a andar, uma por vez. */
    fun resumeQueue() {
        diagnostics.log(null, "fila continua")
        queuePaused = false
        pump()
    }

    /** "Limpar fila": descarta as mensagens que ainda não saíram. */
    fun clearQueue() {
        diagnostics.log(null, "fila limpa")
        turns = turns.filter { it.state == TurnState.Sending || it.state == TurnState.Answered }
        queuePaused = false
        sendNowTarget = null
        persistTurns()
        publish()
    }

    private fun moveToFront(requestId: String) {
        val t = turns.firstOrNull { it.requestId == requestId } ?: return
        val firstWaiting = turns.indexOfFirst { it.state == TurnState.Queued || it.state == TurnState.WaitingEngine }
        val rest = turns.filterNot { it.requestId == requestId }
        val at = rest.indexOfFirst { it.state == TurnState.Queued || it.state == TurnState.WaitingEngine }
        turns = if (firstWaiting < 0 || at < 0) rest + t else rest.take(at) + t + rest.drop(at)
    }

    // ---- conversas ----------------------------------------------------------------------

    /** Começa uma conversa nova: some o histórico daqui, o motor guarda o antigo. */
    fun newConversation() {
        stop()
        resetIdentity()
        notice = null
        entry = entry.copy(open = true, sessionId = "")
        onEntryChange(entry)
    }

    /** "Criar outra conversa" depois de uma conversa que não deu para retomar: leva só a mensagem que falhou. */
    private fun createOtherConversation() {
        val failed = turns.firstOrNull { it.state == TurnState.Failed }
        diagnostics.log(null, "criar outra conversa")
        resetIdentity()
        notice = null
        entry = entry.copy(open = true, sessionId = "")
        onEntryChange(entry)
        if (failed != null) {
            turns = listOf(failed.copy(requestId = UUID.randomUUID().toString(), state = TurnState.Queued, baseline = null, chatId = "", reply = null))
            persistTurns()
            publish()
            pump()
        }
    }

    private fun resetIdentity() {
        streamJob?.cancel()
        waiter?.cancel()
        live = null
        posting = null
        sendNowTarget = null
        stopAfterAccept = false
        queuePaused = false
        streamDead = false
        history = emptyList()
        historyLoaded = false
        historyVersion = null
        historyRevision = null
        historyTotal = 0
        historyFirstIndex = 0
        olderCursor = null
        hasOlder = false
        turns = emptyList()
        notes = emptyList()
        info = null
        fromCache = false
        loading = false
        refreshing = false
        externalRunning = false
        sessionId = ""
        cs.saveLastSessionId("")
        onIdentity("")
        publish()
    }

    // ---- fluxo da resposta --------------------------------------------------------------

    private fun activeTurn(): Turn? = turns.firstOrNull { it.state == TurnState.Sending }

    private fun pump() {
        if (!restored || !syncedOnce || posting != null || live != null || queuePaused) return
        if (turns.any { it.state == TurnState.Failed || it.state == TurnState.Sending }) return
        val next = turns.firstOrNull { it.state == TurnState.Queued || it.state == TurnState.WaitingEngine } ?: return
        startTurn(next)
    }

    private fun startTurn(turn: Turn) {
        val silent = turn.state == TurnState.WaitingEngine // reenvio de mensagem guardada: falhar de novo não muda a tela
        val baseline = turn.baseline ?: (userCount(history, turn.text) +
            turns.takeWhile { it.requestId != turn.requestId }.count { norm(it.text) == norm(turn.text) && it.baseline != null })
        posting = turn.requestId
        turns = turns.map { if (it.requestId == turn.requestId) it.copy(baseline = baseline, state = if (silent) it.state else TurnState.Sending) else it }
        if (!silent) beginLive()
        notice = if (silent) notice else null
        persistTurns()
        publish()
        val current = turns.first { it.requestId == turn.requestId }
        streamJob = scope.launch { converse(current, silent) }
    }

    private fun beginLive() {
        val t = clock()
        now = t
        live = LiveReply("", emptyList(), startedAt = t, phase = "starting", lastProgressAt = t, lastSignalAt = t)
        streamDead = false
        lostCycles = 0
        startTicker()
    }

    private suspend fun converse(turn: Turn, silent: Boolean) {
        val started = try {
            gateway.startChat(turn.text, sessionId.ifEmpty { null }, "default", turn.requestId, turn.executor)
        } catch (e: CancellationException) {
            posting = null
            throw e
        } catch (e: Exception) {
            posting = null
            onStartFailed(turn, e)
            return
        }
        posting = null
        diagnostics.log(started.chatId, "aceito (${started.state ?: "?"})")
        failures = 0
        if (silent) {
            turns = turns.map { if (it.requestId == turn.requestId) it.copy(state = TurnState.Sending) else it }
            notice = null
            beginLive()
        }
        turns = turns.map { if (it.requestId == turn.requestId) it.copy(chatId = started.chatId) else it }
        started.sessionId?.let { adoptSession(it) }
        persistTurns()
        publish()
        if (stopAfterAccept) {
            stopAfterAccept = false
            try {
                gateway.stopChat(started.chatId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        wake.trySend(Unit) // confere o estado do motor já: é assim que se descobre se ele entende /state
        try {
            gateway.chatStream(started.chatId).collect { handle(turn.requestId, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnostics.log(started.chatId, "fluxo caiu: ${e.javaClass.simpleName}")
            onStreamEnded(turn.requestId, failed = true)
            return
        }
        onStreamEnded(turn.requestId, failed = false)
    }

    private fun isActive(requestId: String): Boolean =
        live != null && turns.any { it.requestId == requestId && it.state == TurnState.Sending }

    private suspend fun onStreamEnded(requestId: String, failed: Boolean) {
        if (!isActive(requestId)) return
        if (stateSupported == null && sessionId.isNotEmpty()) syncOnce(false)
        if (!isActive(requestId)) return
        if (stateSupported == false) {
            // Motor antigo, sem como conferir por fora: o fim do fluxo é o fim da resposta.
            val turn = turns.first { it.requestId == requestId }
            finishWithNotice(
                turn,
                ChatNotice(
                    if (failed) "Perdi a conexão com o motor no meio da resposta." else "A resposta parou antes de terminar.",
                    ChatAction.Retry,
                ),
            )
        } else {
            // O fluxo morreu, mas a resposta não: o laço de conferência continua e termina quando o motor terminar.
            streamDead = true
            wake.trySend(Unit)
        }
    }

    private fun handle(requestId: String, event: ChatEvent) {
        val current = live ?: return
        val turn = turns.firstOrNull { it.requestId == requestId && it.state == TurnState.Sending } ?: return
        val t = clock()
        when (event) {
            is ChatEvent.Status -> applyStatus(event.status)
            is ChatEvent.Text -> {
                live = current.copy(
                    text = event.text, lastSignalAt = t,
                    lastProgressAt = if (event.text.length > current.text.length) t else current.lastProgressAt,
                    phase = if (event.text.isNotBlank() && current.phase in EARLY_PHASES) "responding" else current.phase,
                )
            }
            is ChatEvent.Tool -> live = current.copy(
                tools = current.tools + LiveReply.ToolMark(current.text.length, event.summary), lastSignalAt = t, lastProgressAt = t,
            )
            is ChatEvent.Done -> finishDone(turn, event.text, event.sessionId)
            is ChatEvent.Failed -> onFailed(turn, event.failure)
        }
    }

    /** Batimento do motor: fase nova, saída nova ou só "estou vivo". */
    private fun applyStatus(status: ChatStatus) {
        val current = live ?: return
        val t = clock()
        val progressed = status.phase != current.phase && status.phase != "unknown" ||
            (status.lastOutputAtMillis != null && status.lastOutputAtMillis > current.lastOutputSeen)
        live = current.copy(
            phase = if (status.phase == "unknown") current.phase else status.phase,
            lastSignalAt = t,
            lastProgressAt = if (progressed) t else current.lastProgressAt,
            lastOutputSeen = maxOf(current.lastOutputSeen, status.lastOutputAtMillis ?: 0L),
            stopping = current.stopping || status.state == "stopping",
        )
    }

    private fun finishDone(turn: Turn, text: String, sid: String?) {
        val lv = live
        sid?.let { adoptSession(it) }
        val final = text.ifBlank { lv?.text.orEmpty() }.trim()
        stopTicker()
        live = null
        streamJob?.cancel()
        diagnostics.log(turn.chatId, "terminou")
        if (final.isEmpty() && !viewOf(history, turn).replyConfirmed) {
            turns = turns.map { if (it.requestId == turn.requestId) it.copy(state = TurnState.Failed) else it }
            notice = ChatNotice("Não recebi resposta desta vez.", ChatAction.Retry)
            persistTurns()
            publish()
            return
        }
        completeTurn(turn, final.ifEmpty { null })
    }

    /** A resposta acabou (bem ou parada com o que veio): a mensagem fica como respondida e a fila anda. */
    private fun completeTurn(turn: Turn, reply: String?) {
        if (stateSupported != true) {
            // Motor antigo, sem histórico próprio: esta conversa passa a ser o histórico daqui.
            val base = history.size
            history = history + HistoryLine("local:$base", SessionLineRole.User, turn.text) +
                (if (reply != null) listOf(HistoryLine("local:${base + 1}", SessionLineRole.Assistant, reply)) else emptyList())
            historyLoaded = true
            turns = turns.filterNot { it.requestId == turn.requestId }
            persistHistory()
        } else {
            turns = turns.map { if (it.requestId == turn.requestId) it.copy(state = TurnState.Answered, reply = reply) else it }
        }
        persistTurns()
        publish()
        pump()
        wake.trySend(Unit)
    }

    private fun onFailed(turn: Turn, failure: ChatFailure) {
        val partial = live?.text.orEmpty().trim()
        if (failure.code == "stopped") {
            finishStopped(turn)
            return
        }
        stopTicker()
        live = null
        streamJob?.cancel()
        diagnostics.log(turn.chatId, "falhou: ${failure.code}")
        // O que já chegou não se perde: fica como resposta parcial.
        val ids = turn.requestId
        if (partial.isNotEmpty()) {
            completeTurnKeepingFailure(turn, partial)
        } else {
            turns = turns.map { if (it.requestId == ids) it.copy(state = TurnState.Failed) else it }
        }
        notice = noticeForFailure(failure)
        persistTurns()
        publish()
    }

    /** Resposta parcial guardada + o pedido marcado como não concluído: "Tentar de novo" manda o texto de novo. */
    private fun completeTurnKeepingFailure(turn: Turn, partial: String) {
        if (stateSupported != true) {
            val base = history.size
            history = history + HistoryLine("local:$base", SessionLineRole.User, turn.text) +
                HistoryLine("local:${base + 1}", SessionLineRole.Assistant, partial)
            historyLoaded = true
            // Sem a bolha daqui (já está no histórico), mas o pedido continua para "Tentar de novo".
            turns = turns.map {
                if (it.requestId == turn.requestId) it.copy(state = TurnState.Failed, baseline = userCount(history, turn.text) - 1) else it
            }
            persistHistory()
        } else {
            turns = turns.map { if (it.requestId == turn.requestId) it.copy(state = TurnState.Failed, reply = partial) else it }
        }
    }

    private fun noticeForFailure(f: ChatFailure): ChatNotice = when (f.code) {
        "resume_failed" -> ChatNotice(
            "Não consegui continuar esta conversa. O histórico foi preservado. Se criar outra conversa, o contexto desta não vai junto.",
            ChatAction.Retry,
            listOf(NoticeButton("Tentar retomar", NoticeKind.Retry), NoticeButton("Criar outra conversa", NoticeKind.NewConversation)),
        )
        "session_not_found" -> ChatNotice(
            "Não encontrei esta conversa no Termux.",
            null,
            listOf(NoticeButton("Voltar às sessões", NoticeKind.BackToSessions), NoticeButton("Criar outra conversa", NoticeKind.NewConversation)),
        )
        else -> ChatNotice(f.message, f.action)
    }

    /** O motor confirmou (ou, em motor antigo, o app assumiu) que a resposta parou. O texto recebido fica. */
    private fun finishStopped(turn: Turn) {
        if (turns.none { it.requestId == turn.requestId && it.state == TurnState.Sending }) return
        val partial = live?.text.orEmpty().trim()
        stopTicker()
        live = null
        streamJob?.cancel()
        diagnostics.log(turn.chatId, "parada confirmada")
        info = "Resposta interrompida. O texto recebido foi guardado."
        completeTurnPartial(turn, partial.ifEmpty { null })
        afterStop()
    }

    private fun completeTurnPartial(turn: Turn, partial: String?) {
        if (stateSupported != true) {
            val base = history.size
            history = history + HistoryLine("local:$base", SessionLineRole.User, turn.text) +
                (if (partial != null) listOf(HistoryLine("local:${base + 1}", SessionLineRole.Assistant, partial)) else emptyList())
            historyLoaded = true
            turns = turns.filterNot { it.requestId == turn.requestId }
            persistHistory()
        } else {
            turns = turns.map { if (it.requestId == turn.requestId) it.copy(state = TurnState.Answered, reply = partial) else it }
        }
        persistTurns()
        publish()
    }

    private fun afterStop() {
        val target = sendNowTarget
        sendNowTarget = null
        if (target != null && turns.any { it.requestId == target }) {
            moveToFront(target)
            queuePaused = false
            persistTurns()
            publish()
            pump()
        } else {
            queuePaused = turns.any { it.state == TurnState.Queued || it.state == TurnState.WaitingEngine }
        }
        wake.trySend(Unit)
    }

    /** Fim de resposta sem terminar direito (motor antigo): guarda o que veio e oferece tentar de novo. */
    private fun finishWithNotice(turn: Turn, n: ChatNotice) {
        val partial = live?.text.orEmpty().trim()
        stopTicker()
        live = null
        diagnostics.log(turn.chatId, "fim sem terminal")
        if (partial.isNotEmpty()) completeTurnKeepingFailure(turn, partial)
        else turns = turns.map { if (it.requestId == turn.requestId) it.copy(state = TurnState.Failed) else it }
        notice = n
        persistTurns()
        publish()
    }

    private fun onStartFailed(turn: Turn, e: Exception) {
        val code = (e as? AuraEngineException)?.code
        diagnostics.log(null, "envio falhou: ${e.javaClass.simpleName} ${(e as? AuraEngineException)?.httpStatus ?: ""} ${code ?: ""}".trim())
        stopTicker()
        live = null
        stopAfterAccept = false
        sendNowTarget = null
        when {
            e is ChatUnsupportedException -> failTurn(turn, ChatNotice("Este motor ainda não conversa. Atualize o AURA no Termux.", ChatAction.OpenConnections))
            e is AuraEngineException && code == "session_not_found" ->
                failTurn(turn, noticeForFailure(ChatFailure("session_not_found", "", ChatAction.Retry)))
            e is AuraEngineException && code == "request_conflict" ->
                failTurn(turn, ChatNotice("Este pedido já foi usado com outro conteúdo. Toque em Tentar de novo.", ChatAction.Retry))
            e is EngineUnreachableException && e.reason == OfflineReason.NoToken ->
                waitForEngine(turn, ChatNotice("O AURA ainda não está conectado ao Termux.", ChatAction.OpenConnections))
            e is AuraEngineException && e.httpStatus == 401 ->
                waitForEngine(turn, ChatNotice("O AURA ainda não está conectado ao Termux.", ChatAction.OpenConnections))
            e is EngineUnreachableException -> {
                engineReason = e.reason
                waitForEngine(turn, ChatNotice("O motor está desligado.", ChatAction.StartEngine))
            }
            e is IOException -> waitForEngine(turn, ChatNotice("O motor está desligado.", ChatAction.StartEngine))
            else -> failTurn(turn, ChatNotice("Não consegui responder agora.", ChatAction.Retry))
        }
    }

    private fun failTurn(turn: Turn, n: ChatNotice) {
        turns = turns.map { if (it.requestId == turn.requestId) it.copy(state = TurnState.Failed) else it }
        notice = n
        persistTurns()
        publish()
    }

    /** Sem motor: a mensagem fica guardada (mesma chave) e sai sozinha quando ele voltar. */
    private fun waitForEngine(turn: Turn, n: ChatNotice) {
        turns = turns.map { if (it.requestId == turn.requestId) it.copy(state = TurnState.WaitingEngine, chatId = "") else it }
        if (notice?.message != n.message || notice?.action != n.action) notice = n
        persistTurns()
        publish()
        startWaiter()
    }

    private fun startWaiter() {
        if (waiter?.isActive == true) return
        waiter = scope.launch {
            while (turns.any { it.state == TurnState.WaitingEngine }) {
                delay(retryMillis)
                pump()
            }
        }
    }

    // ---- conferir o motor (a fonte da verdade) -------------------------------------------

    private fun ensureLoop() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch { runLoop() }
    }

    private fun busy(): Boolean = live != null || posting != null || externalRunning || !syncedOnce

    private suspend fun runLoop() {
        var unchanged = 0
        var lastWatch = Long.MIN_VALUE
        startEventsWatch()
        while (true) {
            if (!busy() && !visible) break
            val before = historyVersion
            syncOnce(force = !historyLoaded)
            if (visible && sessionId.isNotEmpty() && clock() - lastWatch >= WATCH_RENEW_MS) {
                lastWatch = clock()
                runCatching { withTimeoutOrNull(timing.callTimeoutMs) { gateway.watchSession(sessionId) } }
            }
            unchanged = if (historyVersion == before) unchanged + 1 else 0
            val wait = when {
                live != null || posting != null || externalRunning -> timing.busyPollMs
                unchanged >= timing.backoffAfter -> timing.idleBackoffMs
                else -> timing.idlePollMs
            }
            withTimeoutOrNull(wait) { wake.receive() }
            if (stateSupported == false && live == null && posting == null) break // motor antigo: nada a conferir sozinho
        }
        eventsJob?.cancel()
    }

    /** Eventos do motor (session.updated, conexão) só acordam o laço mais cedo; a conferência por consulta basta sozinha. */
    private fun startEventsWatch() {
        if (eventsJob?.isActive == true) return
        eventsJob = scope.launch {
            try {
                gateway.events.collect {
                    when (it.type) {
                        "session.updated" -> if (it.payload["session_id"] == sessionId) wake.trySend(Unit)
                        "connection.restored" -> { failures = 0; wake.trySend(Unit) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun <T> call(block: suspend () -> EngineResult<T>): EngineResult<T> = try {
        withTimeoutOrNull(timing.callTimeoutMs) { block() } ?: EngineResult.Unavailable("O motor demorou a responder.")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        EngineResult.Unavailable(e.message ?: "O motor não respondeu.")
    }

    private suspend fun syncOnce(force: Boolean) {
        val id = sessionId
        try {
            if (id.isEmpty()) return
            when (val r = call { gateway.sessionState(id) }) {
                is EngineResult.Ok -> onState(id, r.value, force)
                is EngineResult.Rejected -> onStateRejected(id, r)
                is EngineResult.Unavailable -> onStateUnavailable(id, r.reason)
            }
        } finally {
            if (!syncedOnce) {
                syncedOnce = true
                loading = false
                refreshing = false
                pump()
            }
        }
    }

    private suspend fun onState(id: String, st: SessionState, force: Boolean) {
        if (stateSupported != true) diagnostics.log(null, "estado do motor disponível")
        stateSupported = true
        failures = 0
        signal()
        externalRunning = st.externalRunning && !st.running
        if (force || !historyLoaded || st.version != historyVersion) pullHistory(id, st)
        loading = false
        refreshing = false
        if (historyLoaded && notice?.action == ChatAction.StartEngine && live == null && posting == null &&
            turns.none { it.state == TurnState.WaitingEngine }
        ) notice = null // o motor voltou e a conversa carregou: o aviso de "motor desligado" acabou
        reconcileLive(st)
    }

    private fun onStateRejected(id: String, r: EngineResult.Rejected) {
        diagnostics.log(null, "estado recusado: ${r.code}")
        if (r.code == "session_not_found") {
            loading = false
            refreshing = false
            if (!historyLoaded && turns.isEmpty() && live == null) {
                notice = ChatNotice(
                    "Não encontrei esta conversa no Termux.", null,
                    listOf(NoticeButton("Voltar às sessões", NoticeKind.BackToSessions), NoticeButton("Criar outra conversa", NoticeKind.NewConversation)),
                )
            }
        } else {
            onStateUnavailable(id, r.message)
        }
    }

    private fun onStateUnavailable(id: String, reason: String) {
        failures++
        if (reason == ENGINE_NOT_EXPOSED) {
            if (stateSupported != false) diagnostics.log(null, "motor antigo: sem estado de sessão")
            stateSupported = false
            foldAnswered()
            loadLegacyHistory(id)
            // Parar foi pedido antes de saber que o motor é antigo: não há como confirmar por fora, então vale a parada.
            val lv = live
            val running = activeTurn()
            if (lv != null && lv.stopRequestedAt > 0 && running != null) finishStopped(running)
            return
        }
        diagnostics.log(activeTurn()?.chatId, "estado indisponível ($failures)")
        loading = false
        refreshing = false
        if (history.isNotEmpty() || turns.isNotEmpty()) fromCache = true
        if (!historyLoaded && history.isEmpty() && turns.isEmpty() && live == null && notice == null && entry.sessionId.isNotEmpty()) {
            notice = ChatNotice("O motor está desligado. Esta conversa ainda não foi guardada neste celular.", ChatAction.StartEngine)
        }
    }

    /** Motor sem /state: lê o histórico inteiro de uma vez (rotas antigas) quando não há nada local para mostrar. */
    private fun loadLegacyHistory(id: String) {
        if (historyLoaded || legacyLoading) return
        legacyLoading = true
        scope.launch {
            try {
                val lines = (call { gateway.sessionMessages(id) } as? EngineResult.Ok)?.value.orEmpty()
                var engineDown = false
                val loaded = if (lines.isNotEmpty()) {
                    lines.mapIndexed { i, l -> HistoryLine("legacy:$i", l.role, l.text) }
                } else {
                    try {
                        gateway.chatMessages(id).mapIndexed { i, l -> HistoryLine("legacy:$i", if (l.fromUser) SessionLineRole.User else SessionLineRole.Assistant, l.text) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        engineDown = true
                        emptyList()
                    }
                }
                if (loaded.isNotEmpty()) {
                    history = loaded
                    historyLoaded = true
                    fromCache = false
                    if (notice?.action == ChatAction.StartEngine && turns.none { it.state == TurnState.WaitingEngine }) notice = null
                    persistHistory()
                    publish()
                } else if (engineDown && history.isEmpty() && turns.isEmpty() && live == null && notice == null) {
                    notice = ChatNotice("O motor está desligado. A conversa aparece quando ele ligar.", ChatAction.StartEngine)
                }
            } finally {
                legacyLoading = false
            }
        }
    }

    /** Descobriu que o motor é antigo: as respostas já guardadas daqui passam a ser o histórico daqui. */
    private fun foldAnswered() {
        val answered = turns.filter { it.state == TurnState.Answered }
        if (answered.isEmpty()) return
        var next = history.size
        val added = ArrayList<HistoryLine>()
        answered.forEach { t ->
            added += HistoryLine("local:${next++}", SessionLineRole.User, t.text)
            t.reply?.let { added += HistoryLine("local:${next++}", SessionLineRole.Assistant, it) }
        }
        history = history + added
        historyLoaded = true
        turns = turns.filterNot { it.state == TurnState.Answered }
        persistHistory()
        persistTurns()
        publish()
    }

    /** Traz do motor só o que falta (ou a cauda inteira, se não der para emendar) e emenda sem duplicar. */
    private suspend fun pullHistory(id: String, st: SessionState) {
        var incremental = historyLoaded && st.revision == historyRevision && st.total >= historyTotal && historyRevision != null
        var attempts = 0
        while (attempts++ < 2) {
            val after = if (incremental) maxOf(historyFirstIndex, historyTotal - 3) else null
            val r = call { gateway.sessionPage(id, if (incremental) 500 else 100, null, after) }
            if (r !is EngineResult.Ok) {
                diagnostics.log(null, "histórico indisponível")
                if (history.isNotEmpty() || turns.isNotEmpty()) fromCache = true
                return
            }
            val page = r.value
            if (incremental && page.firstIndex > historyFirstIndex + history.size) {
                incremental = false // buraco entre o que temos e o que veio: recarrega a cauda
                continue
            }
            history = if (incremental) mergeById(history, page.lines) else page.lines
            if (!incremental) {
                olderCursor = page.previousCursor
                hasOlder = page.hasMore
                historyFirstIndex = page.firstIndex
            }
            historyTotal = page.total
            historyRevision = page.revision
            historyVersion = st.version
            historyComplete = page.historyComplete
            historyLoaded = true
            fromCache = false
            diagnostics.log(null, "histórico ${if (incremental) "incremental" else "completo"}: ${page.lines.size} linhas, total ${page.total}")
            persistHistory()
            publish()
            return
        }
    }

    /** "Carregar mensagens anteriores": a página antes da primeira linha daqui, sem duplicar nem renumerar. */
    fun loadOlder() {
        val cursor = olderCursor ?: return
        if (loadingOlder) return
        val id = sessionId
        loadingOlder = true
        scope.launch {
            try {
                when (val r = call { gateway.sessionPage(id, 100, cursor, null) }) {
                    is EngineResult.Ok -> {
                        history = prependOlder(history, r.value.lines)
                        olderCursor = r.value.previousCursor
                        hasOlder = r.value.hasMore
                        historyFirstIndex = r.value.firstIndex
                        persistHistory()
                        publish()
                    }
                    is EngineResult.Rejected -> {
                        // A conversa mudou enquanto se lia (history_changed): recarrega a cauda, preservando o que há na tela.
                        historyLoaded = false
                        wake.trySend(Unit)
                    }
                    is EngineResult.Unavailable -> diagnostics.log(null, "mensagens anteriores indisponíveis")
                }
            } finally {
                loadingOlder = false
            }
        }
    }

    private fun signal() {
        val lv = live ?: return
        live = lv.copy(lastSignalAt = clock())
    }

    /** O motor é a fonte da verdade: se ele diz que esta resposta acabou, a tela acaba junto, mesmo sem o fluxo. */
    private suspend fun reconcileLive(st: SessionState) {
        val turn = activeTurn() ?: return
        if (live == null) return
        val chatId = turn.chatId
        if (chatId.isEmpty()) return // o envio ainda está a caminho
        val mine = st.activeChatId == chatId
        if (st.running && mine) {
            lostCycles = 0
            st.active?.let { if (it.chatId == chatId) applyStatus(it) }
            return
        }
        val last = st.lastChat
        if (last != null && last.chatId == chatId && last.state in TERMINAL_STATES) {
            settle(turn, last.state)
            return
        }
        if (st.running) return // outra execução anda na conversa; a minha espera na fila do motor
        if (last != null && last.chatId == chatId) return // a minha ainda consta como em andamento: confere no próximo ciclo
        // O motor não responde mais por esta execução e a última conversa dele é outra: pergunta direto antes de desistir.
        val snap = call { gateway.chatSnapshot(chatId) }
        if (snap is EngineResult.Ok && snap.value.status.isTerminal) {
            settleWith(turn, snap.value)
            return
        }
        if (++lostCycles >= LOST_CYCLES_LIMIT) {
            diagnostics.log(chatId, "execução sumiu do motor")
            onFailed(turn, ChatFailure("lost", "Não encontrei esta resposta no motor. Seu pedido está guardado.", ChatAction.Retry))
        }
    }

    private suspend fun settle(turn: Turn, state: String) {
        val snap = (call { gateway.chatSnapshot(turn.chatId) } as? EngineResult.Ok)?.value
        if (snap != null && snap.status.isTerminal) {
            settleWith(turn, snap)
            return
        }
        // Sem o retrato (rota fora do ar): vale o que o estado da sessão disse e o histórico já puxado.
        when (state) {
            "done" -> finishDone(turn, "", null)
            "interrupted" -> finishStopped(turn)
            else -> onFailed(turn, chatFailureFor("failed", null))
        }
    }

    private fun settleWith(turn: Turn, snap: ChatSnapshot) {
        when (snap.status.state) {
            "done" -> finishDone(turn, snap.text, snap.status.sessionId)
            "interrupted" -> finishStopped(turn)
            else -> onFailed(turn, snap.failure ?: chatFailureFor("failed", null))
        }
    }

    // ---- relógio da resposta -------------------------------------------------------------

    private fun startTicker() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (live != null) {
                now = clock()
                delay(timing.tickMs)
            }
            now = clock()
        }
    }

    private fun stopTicker() {
        tickJob?.cancel()
        tickJob = null
        now = clock()
    }

    // ---- disco ---------------------------------------------------------------------------

    private suspend fun restoreLocal() {
        if (store == null) return
        val saved = withContext(Dispatchers.IO) {
            val last = cs.lastSessionId()
            val sid = sessionId.ifEmpty { last }
            Restored(sid, last, cs.loadHistory(sid), cs.loadTurns(sid))
        }
        val (sid, last, cache, savedTurns) = saved
        if (sessionId.isEmpty() && sid.isNotEmpty()) {
            sessionId = sid
            entry = entry.copy(sessionId = sid)
            onIdentity(sid)
        } else if (sessionId.isNotEmpty() && sessionId != last) {
            persistSession() // abrir uma conversa a torna a "última": reabrir continua nela
        }
        if (cache != null) {
            history = cache.lines
            historyVersion = null // o disco guarda o que se viu, mas a versão só vale depois de conferir com o motor
            historyRevision = cache.revision
            historyTotal = cache.total
            historyFirstIndex = cache.firstIndex
            historyComplete = cache.complete
            hasOlder = cache.firstIndex > 0
            historyLoaded = false
            fromCache = true
        }
        // O que estava "enviando" quando o app fechou é incerto: sai de novo com a MESMA chave (o motor não repete).
        // Mensagens escritas enquanto o disco era lido ficam depois das guardadas.
        turns = savedTurns.map { if (it.state == TurnState.Sending) it.copy(state = TurnState.WaitingEngine, chatId = "") else it } + turns
        if (sessionId.isNotEmpty()) {
            if (cache == null && turns.isEmpty()) loading = true else refreshing = true
        } else {
            syncedOnce = true // conversa nova: não há o que conferir antes de enviar
        }
        publish()
    }

    private fun persistHistory() {
        val sid = sessionId
        if (store == null || sid.isEmpty()) return
        val snapshot = CachedHistory(history, historyVersion, historyTotal, historyFirstIndex, historyRevision, historyComplete)
        writeLater { cs.saveHistory(sid, snapshot) }
    }

    private fun persistTurns() {
        if (store == null) return
        val sid = sessionId
        val snapshot = turns
        writeLater { cs.saveTurns(sid, snapshot) }
    }

    private fun persistSession() {
        val id = sessionId
        if (store == null) return
        writeLater { cs.saveLastSessionId(id) }
    }

    /** Recalcula o que a tela mostra e descarta o que o histórico do motor já confirmou. */
    private fun publish() {
        val t = buildTranscript(history, turns)
        if (t.resolved.isNotEmpty()) {
            turns = turns.filterNot { it.requestId in t.resolved }
            persistTurns()
        }
        val shown = ArrayList(t.messages)
        notes.forEach { shown += ChatMessage(shown.size, it.fromUser, it.text) }
        messages = shown
        queue = t.queue
        if (queue.isEmpty()) queuePaused = false
    }

    private fun adoptSession(id: String) {
        if (id == sessionId) return
        val old = sessionId
        sessionId = id
        entry = entry.copy(open = true, sessionId = id)
        onEntryChange(entry)
        persistSession()
        if (old.isEmpty()) cs.moveTurns("", id)
        persistTurns()
        persistHistory()
        onIdentity(id)
    }

    private fun consumeEntry() {
        entry = entry.copy(open = true, prompt = "", jobId = "", draft = "")
        onEntryChange(entry)
    }

    /** Resumo de um trabalho da Atividade, em texto. O andamento fica na aba Atividade. */
    private suspend fun showJob(id: String) {
        try {
            val job = gateway.jobs().firstOrNull { it.id == id }
            if (job == null) {
                addNote(false, "Não encontrei mais esse trabalho. Veja a lista na aba Atividade.")
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
            addNote(false, "${job.title}: $line")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addNote(false, "Não consegui falar com o motor agora. Veja o trabalho na aba Atividade.")
        }
    }

    private fun addNote(fromUser: Boolean, text: String) {
        notes = notes + ChatMessage(notes.size, fromUser, text)
        publish()
    }

    // ---- diagnóstico --------------------------------------------------------------------

    /** Texto para "Copiar diagnóstico": estado de agora e o anel de log. Nunca tem texto de mensagem. */
    fun diagnosticReport(): String = diagnostics.report(
        listOf(
            "Conversa: ${sessionId.take(8).ifEmpty { "(nova)" }}",
            "Motor: " + when (stateSupported) { true -> "acompanhamento completo"; false -> "versão antiga"; null -> "ainda não conferido" },
            "Respondendo: ${if (live != null) "sim" else "não"}${live?.let { " (${it.phase})" }.orEmpty()}",
            "Fila: ${queue.size} mensagem(ns)${if (queuePaused) " (parada)" else ""}",
            "Histórico: ${history.size} linhas, versão ${historyVersion?.take(24) ?: "-"}, completo=${historyComplete}",
            "Terminal responde: ${if (externalRunning) "sim" else "não"}",
        ),
    )

    private data class Restored(val sid: String, val last: String, val cache: CachedHistory?, val turns: List<Turn>)

    private companion object {
        val TERMINAL_STATES = setOf("done", "error", "interrupted")
        val EARLY_PHASES = setOf("starting", "waiting_claude", "unknown")
        const val LOST_CYCLES_LIMIT = 3
        const val WATCH_RENEW_MS = 30_000L
    }
}
