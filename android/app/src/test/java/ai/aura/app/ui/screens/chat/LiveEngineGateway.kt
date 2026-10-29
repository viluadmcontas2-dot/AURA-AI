package ai.aura.app.ui.screens.chat

import ai.aura.app.integration.AuraEngineException
import ai.aura.app.ui.engine.Capability
import ai.aura.app.ui.engine.ChatEvent
import ai.aura.app.ui.engine.ChatSnapshot
import ai.aura.app.ui.engine.ChatStarted
import ai.aura.app.ui.engine.ChatStatus
import ai.aura.app.ui.engine.EngineEvent
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.EngineResult
import ai.aura.app.ui.engine.HistoryLine
import ai.aura.app.ui.engine.HistoryPage
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobRequest
import ai.aura.app.ui.engine.LastChat
import ai.aura.app.ui.engine.RouteResult
import ai.aura.app.ui.engine.SessionLineRole
import ai.aura.app.ui.engine.SessionState
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow

/**
 * Um motor de mentira que fala o contrato novo (estado da sessão, páginas do histórico, retrato da resposta) e guarda o
 * histórico como o motor de verdade: a mensagem da pessoa entra na hora, a resposta só quando o teste manda
 * [finish]. As chaves de falha ([down], [streamAlive]) derrubam as rotas como num celular real.
 */
internal class LiveEngineGateway : EngineGateway {
    class Chat(val id: String, val sid: String, val requestId: String?) {
        @Volatile var state = "running"
        @Volatile var phase = "waiting_claude"
        @Volatile var partial = ""
        @Volatile var reply = ""
        @Volatile var failureCode: String? = null
    }

    class Sess(val id: String) {
        val lines = CopyOnWriteArrayList<HistoryLine>()
        @Volatile var version = 1
        @Volatile var external = false
    }

    class Call(val text: String, val sessionId: String?, val requestId: String?, val executor: String?)

    val sessions = LinkedHashMap<String, Sess>()
    val chats = LinkedHashMap<String, Chat>()
    private val byRequest = HashMap<String, String>()
    val calls = CopyOnWriteArrayList<Call>()
    val stopRequests = CopyOnWriteArrayList<String>()

    /** Todo pedido falha com IOException (motor desligado ou sem rede). */
    @Volatile var down = false

    /** false = os eventos da resposta e GET /v1/chat/{id} estão mortos; /v1/sessions/{id}/state continua. */
    @Volatile var streamAlive = true

    /** true = Parar é confirmado na hora; false = o teste confirma com [confirmStop]. */
    @Volatile var autoConfirmStop = false

    /** Portões para segurar uma resposta de uma sessão (simula leitura atrasada). */
    val pageGates = HashMap<String, CompletableDeferred<Unit>>()
    private var chatCounter = 0
    private var sessionCounter = 0
    private var lineCounter = 0

    override val events: MutableSharedFlow<EngineEvent> = MutableSharedFlow(extraBufferCapacity = 64)

    fun session(id: String): Sess = synchronized(this) { sessions.getOrPut(id) { Sess(id) } }

    fun seed(id: String, vararg lines: Pair<SessionLineRole, String>) {
        val s = session(id)
        lines.forEach { (role, text) -> s.lines += HistoryLine("seed:${lineCounter++}", role, text) }
        s.version++
    }

    /** Alguém escreveu na conversa por fora (terminal) ou o Claude respondeu: entra no histórico. */
    fun appendOutside(id: String, role: SessionLineRole, text: String) {
        val s = session(id)
        s.lines += HistoryLine("out:${lineCounter++}", role, text)
        s.version++
    }

    /** O motor termina a resposta: a fala do Claude entra no histórico e a execução fica "done". */
    fun finish(chatId: String, text: String) {
        val c = chats.getValue(chatId)
        val s = session(c.sid)
        c.reply = text
        s.lines += HistoryLine("a:${lineCounter++}", SessionLineRole.Assistant, text)
        s.version++
        c.state = "done"
    }

    fun fail(chatId: String, code: String) {
        val c = chats.getValue(chatId)
        c.failureCode = code
        c.state = "error"
        session(c.sid).version++
    }

    fun confirmStop(chatId: String) {
        val c = chats.getValue(chatId)
        if (c.state == "running" || c.state == "stopping") {
            c.state = "interrupted"
            session(c.sid).version++
        }
    }

    private fun check() {
        if (down) throw IOException("Termux não responde")
    }

    private fun statusOf(c: Chat) = ChatStatus(
        chatId = c.id, sessionId = c.sid, seq = 1, state = c.state, phase = c.phase, elapsedMs = 0,
        lastOutputAtMillis = null, heartbeatAtMillis = null, canStop = c.state == "running",
    )

    override suspend fun startChat(text: String, sessionId: String?, workspace: String): ChatStarted =
        startChat(text, sessionId, workspace, null, null)

    override suspend fun startChat(text: String, sessionId: String?, workspace: String, requestId: String?, executor: String?): ChatStarted {
        check()
        calls += Call(text, sessionId, requestId, executor)
        requestId?.let { rid -> byRequest[rid]?.let { old -> chats[old]?.let { return ChatStarted(it.id, it.sid, rid, it.state) } } }
        val sid = sessionId ?: synchronized(this) { "sess_${++sessionCounter}" }
        if (sessionId != null && sessionId !in sessions) {
            throw AuraEngineException(404, "Não encontrei esta conversa no Termux.", "session_not_found")
        }
        val s = session(sid)
        val chat = Chat("chat_${++chatCounter}", sid, requestId)
        chats[chat.id] = chat
        requestId?.let { byRequest[it] = chat.id }
        s.lines += HistoryLine("u:${lineCounter++}", SessionLineRole.User, text)
        s.version++
        return ChatStarted(chat.id, sid, requestId, "running")
    }

    override fun chatStream(chatId: String): Flow<ChatEvent> = flow {
        var lastPartial = ""
        while (true) {
            if (!streamAlive || down) throw IOException("rota fora do ar")
            val c = chats.getValue(chatId)
            if (c.partial != lastPartial) {
                lastPartial = c.partial
                emit(ChatEvent.Text(c.partial))
            }
            when (c.state) {
                "done" -> { emit(ChatEvent.Done(c.reply, c.sid)); return@flow }
                "error" -> {
                    emit(ChatEvent.Failed(ai.aura.app.ui.engine.ChatFailure(c.failureCode ?: "failed", "Não consegui responder agora.", ai.aura.app.ui.engine.ChatAction.Retry)))
                    return@flow
                }
                "interrupted" -> {
                    emit(ChatEvent.Failed(ai.aura.app.ui.engine.ChatFailure("stopped", "Resposta interrompida.", ai.aura.app.ui.engine.ChatAction.Retry)))
                    return@flow
                }
            }
            delay(100)
        }
    }

    override suspend fun stopChat(chatId: String) {
        check()
        stopRequests += chatId
        val c = chats.getValue(chatId)
        if (c.state == "running") c.state = "stopping"
        if (autoConfirmStop) confirmStop(chatId)
    }

    override suspend fun chatSnapshot(chatId: String): EngineResult<ChatSnapshot> {
        if (down) return EngineResult.Unavailable("Termux não responde")
        if (!streamAlive) return EngineResult.Unavailable("rota fora do ar")
        val c = chats[chatId] ?: return EngineResult.Rejected("not_found", "Chat não encontrado.")
        val failure = if (c.state == "error") ai.aura.app.integration.chatFailureFor(c.failureCode, null) else null
        return EngineResult.Ok(ChatSnapshot(statusOf(c), c.requestId, c.reply.ifEmpty { c.partial }, failure))
    }

    override suspend fun chatByRequest(requestId: String): EngineResult<ChatSnapshot> {
        if (down) return EngineResult.Unavailable("Termux não responde")
        val id = byRequest[requestId] ?: return EngineResult.Rejected("request_not_found", "Este pedido não foi aceito pelo motor.")
        return chatSnapshot(id)
    }

    override suspend fun sessionState(id: String): EngineResult<SessionState> {
        if (down) return EngineResult.Unavailable("Termux não responde")
        val s = sessions[id] ?: return EngineResult.Rejected("session_not_found", "Sessão não encontrada.")
        val mine = chats.values.filter { it.sid == id }
        val active = mine.lastOrNull { it.state == "running" || it.state == "stopping" }
        val last = mine.lastOrNull()
        return EngineResult.Ok(
            SessionState(
                sessionId = id, version = "v${s.version}", revision = "r0",
                messages = s.lines.count { it.role != SessionLineRole.Tool }, total = s.lines.size,
                updatedAtMillis = 0, running = active != null, activeChatId = active?.id,
                externalRunning = s.external, historyComplete = true, fromTerminal = false,
                active = active?.let { statusOf(it) },
                lastChat = last?.let { LastChat(it.id, it.state, it.requestId) },
            ),
        )
    }

    override suspend fun sessionPage(id: String, limit: Int, before: String?, after: Int?): EngineResult<HistoryPage> {
        pageGates[id]?.await()
        if (down) return EngineResult.Unavailable("Termux não responde")
        val s = sessions[id] ?: return EngineResult.Rejected("session_not_found", "Sessão não encontrada.")
        val all = s.lines.toList()
        val first: Int
        val chunk: List<HistoryLine>
        when {
            after != null -> { first = after.coerceIn(0, all.size); chunk = all.drop(first) }
            before != null -> {
                val end = before.removePrefix("c").toIntOrNull()?.coerceIn(0, all.size) ?: all.size
                first = (end - limit).coerceAtLeast(0)
                chunk = all.subList(first, end)
            }
            else -> { first = (all.size - limit).coerceAtLeast(0); chunk = all.drop(first) }
        }
        return EngineResult.Ok(
            HistoryPage(chunk, if (first > 0) "c$first" else null, first > 0, true, all.size, first, "r0", null),
        )
    }

    override suspend fun watchSession(id: String): EngineResult<Unit> = EngineResult.Ok(Unit)

    // Nada de tarefas no chat: se o chat chamar qualquer uma destas, o teste quebra.
    override suspend fun jobs(): List<Job> = throw UnsupportedOperationException("o chat não usa tarefas")
    override suspend fun approve(id: String): Job = throw UnsupportedOperationException("o chat não aprova")
    override suspend fun cancel(id: String): Job = throw UnsupportedOperationException("o chat não cancela tarefas")
    override suspend fun route(text: String): RouteResult = throw UnsupportedOperationException("o chat não interpreta rota")
    override suspend fun submit(requestId: String, request: JobRequest): Job = throw UnsupportedOperationException("o chat não cria tarefa")
    override suspend fun capabilities(): List<Capability> = emptyList()
}
