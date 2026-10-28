package ai.aura.app.ui.screens.chat

import ai.aura.app.ui.engine.Capability
import ai.aura.app.ui.engine.ChatEvent
import ai.aura.app.ui.engine.ChatLine
import ai.aura.app.ui.engine.ChatStarted
import ai.aura.app.ui.engine.ChatSummary
import ai.aura.app.ui.engine.EngineEvent
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobRequest
import ai.aura.app.ui.engine.RouteResult
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow

/** Um passo de uma resposta de mentira: um evento, ou uma espera até o teste liberar. */
internal sealed interface Step {
    class Emit(val event: ChatEvent) : Step
    class Wait(val gate: CompletableDeferred<Unit> = CompletableDeferred()) : Step
}

/**
 * Motor de conversa de mentira, só para o app: registra cada mensagem (texto + session_id) e cada
 * "parar", e responde com os passos de [script]. O motor falso por HTTP fica em RealEngineChatTest.
 */
internal class ScriptedChatGateway(
    @Volatile var down: Boolean = false,
    @Volatile var script: () -> List<Step> = { listOf(Step.Emit(ChatEvent.Done("Olá!", "sess_1"))) },
    var history: List<ChatLine> = emptyList(),
) : EngineGateway {
    /** (texto, session_id) de cada POST /v1/chat. */
    val started = CopyOnWriteArrayList<Pair<String, String?>>()
    val stopped = CopyOnWriteArrayList<String>()
    /** session_id que o motor "não conhece mais": o POST com ele recebe 404 session_not_found. */
    @Volatile var unknownSession: String? = null
    val rejected = CopyOnWriteArrayList<String?>()
    private var counter = 0

    override val events: Flow<EngineEvent> = emptyFlow()

    override suspend fun startChat(text: String, sessionId: String?, workspace: String): ChatStarted {
        if (down) throw IOException("Termux não responde")
        if (sessionId != null && sessionId == unknownSession) {
            rejected += sessionId
            throw ai.aura.app.integration.AuraEngineException(404, "Conversa não encontrada.", "session_not_found")
        }
        started += text to sessionId
        counter++
        return ChatStarted("chat_$counter", sessionId ?: "sess_1")
    }

    override fun chatStream(chatId: String): Flow<ChatEvent> = flow {
        for (step in script()) {
            when (step) {
                is Step.Emit -> emit(step.event)
                is Step.Wait -> step.gate.await()
            }
        }
    }

    override suspend fun stopChat(chatId: String) {
        stopped += chatId
    }

    override suspend fun chats(): List<ChatSummary> =
        if (down) throw IOException("Termux não responde") else if (history.isEmpty()) emptyList() else listOf(ChatSummary("sess_1", "Conversa", 1L))

    override suspend fun chatMessages(id: String): List<ChatLine> =
        if (down) throw IOException("Termux não responde") else history

    // Nada de tarefas no chat: se o chat chamar qualquer uma destas, o teste quebra.
    override suspend fun jobs(): List<Job> = throw UnsupportedOperationException("o chat não usa tarefas")
    override suspend fun approve(id: String): Job = throw UnsupportedOperationException("o chat não aprova")
    override suspend fun cancel(id: String): Job = throw UnsupportedOperationException("o chat não cancela tarefas")
    override suspend fun route(text: String): RouteResult = throw UnsupportedOperationException("o chat não interpreta rota")
    override suspend fun submit(requestId: String, request: JobRequest): Job =
        throw UnsupportedOperationException("o chat não cria tarefa")
    override suspend fun capabilities(): List<Capability> = emptyList()
}
