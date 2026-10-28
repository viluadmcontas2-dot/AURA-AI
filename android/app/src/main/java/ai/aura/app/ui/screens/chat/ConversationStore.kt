package ai.aura.app.ui.screens.chat

import ai.aura.app.offline.LocalStore
import ai.aura.app.ui.engine.HistoryLine
import ai.aura.app.ui.engine.SessionLineRole
import org.json.JSONArray
import org.json.JSONObject

/** Em que ponto está uma mensagem da pessoa. Só [Answered] e [Failed] já passaram pela fila. */
internal enum class TurnState(val wire: String) {
    /** Sem motor: guardada neste celular, sai quando ele voltar. */
    WaitingEngine("waiting_engine"),

    /** Na fila atrás de uma resposta em andamento (ou de outra mensagem). */
    Queued("queued"),

    /** Enviada ao motor; o Claude está respondendo. */
    Sending("sending"),

    /** Respondida. Fica aqui só até o histórico do motor mostrar a mesma conversa. */
    Answered("answered"),

    /** Não deu: espera a pessoa decidir (tentar de novo ou cancelar). */
    Failed("failed");

    companion object {
        fun fromWire(value: String): TurnState? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Uma mensagem da pessoa e a resposta a ela, do envio até o histórico do motor confirmar as duas.
 * [requestId] é a chave de idempotência do POST /v1/chat. [baseline] = quantas mensagens iguais já havia no histórico
 * quando ela saiu (null = ainda não medido): depois disso, uma a mais no histórico significa "o motor registrou".
 */
internal data class Turn(
    val requestId: String,
    val text: String,
    val createdAt: Long,
    val executor: String? = null,
    val state: TurnState = TurnState.Queued,
    val baseline: Int? = null,
    val chatId: String = "",
    val reply: String? = null,
)

/** O que se guarda de uma conversa para abri-la sem motor. */
internal class CachedHistory(
    val lines: List<HistoryLine>,
    val version: String?,
    val total: Int,
    val firstIndex: Int,
    val revision: String?,
    val complete: Boolean,
)

/**
 * Disco do chat, UM ARQUIVO POR CONVERSA (histórico guardado e fila/respostas pendentes). "chat-session" é só o
 * ponteiro da última conversa aberta. Nada aqui é global: abrir a conversa B nunca toca no que é da A.
 */
internal class ConversationStore(private val store: LocalStore?) {
    private fun key(sessionId: String) = sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80).ifEmpty { "novo" }

    fun lastSessionId(): String = store?.loadSessionId().orEmpty()

    fun saveLastSessionId(id: String) {
        store?.saveSessionId(id)
    }

    fun loadHistory(sessionId: String): CachedHistory? {
        val s = store ?: return null
        val o = s.read("chat-h-" + key(sessionId))
        if (o != null) {
            val items = o.optJSONArray("items") ?: JSONArray()
            val lines = (0 until items.length()).mapNotNull { i ->
                val it = items.optJSONObject(i) ?: return@mapNotNull null
                val role = when (it.optString("role")) {
                    "user" -> SessionLineRole.User
                    "assistant" -> SessionLineRole.Assistant
                    "tool" -> SessionLineRole.Tool
                    else -> return@mapNotNull null
                }
                val text = it.optString("text").takeIf { t -> t.isNotEmpty() } ?: return@mapNotNull null
                val id = it.optString("id").takeIf { x -> x.isNotEmpty() } ?: return@mapNotNull null
                HistoryLine(id, role, text)
            }
            return CachedHistory(
                lines, o.optString("version").ifEmpty { null }, o.optInt("total", lines.size), o.optInt("first_index", 0),
                o.optString("revision").ifEmpty { null }, o.optBoolean("complete", true),
            )
        }
        // Dado de uma versão anterior do app: um histórico só, de uma conversa só (a do ponteiro).
        if (sessionId.isNotEmpty() && sessionId == s.loadSessionId()) {
            val old = s.loadChat()
            if (old.isNotEmpty()) {
                val lines = old.mapIndexed { i, m ->
                    HistoryLine("local:$i", if (m.tool) SessionLineRole.Tool else if (m.fromUser) SessionLineRole.User else SessionLineRole.Assistant, m.text)
                }
                return CachedHistory(lines, null, lines.size, 0, "local", true)
            }
        }
        return null
    }

    fun saveHistory(sessionId: String, h: CachedHistory) {
        val s = store ?: return
        val tail = h.lines.takeLast(MAX_CACHED)
        val first = h.firstIndex + (h.lines.size - tail.size)
        val items = JSONArray()
        tail.forEach {
            items.put(JSONObject().put("id", it.id).put("role", it.role.name.lowercase()).put("text", it.text))
        }
        s.write(
            "chat-h-" + key(sessionId),
            JSONObject().put("version", h.version ?: "").put("total", h.total).put("first_index", first)
                .put("revision", h.revision ?: "").put("complete", h.complete).put("items", items),
        )
    }

    fun loadTurns(sessionId: String): List<Turn> {
        val s = store ?: return emptyList()
        val o = s.read("chat-q-" + key(sessionId))
        if (o != null) {
            val items = o.optJSONArray("items") ?: return emptyList()
            return (0 until items.length()).mapNotNull { i ->
                val it = items.optJSONObject(i) ?: return@mapNotNull null
                val text = it.optString("text").takeIf { t -> t.isNotEmpty() } ?: return@mapNotNull null
                val state = TurnState.fromWire(it.optString("state")) ?: return@mapNotNull null
                Turn(
                    requestId = it.optString("request_id").ifEmpty { return@mapNotNull null },
                    text = text,
                    createdAt = it.optLong("created_at"),
                    executor = it.optString("executor").ifEmpty { null },
                    state = state,
                    baseline = if (it.has("baseline") && !it.isNull("baseline")) it.optInt("baseline") else null,
                    chatId = it.optString("chat_id"),
                    reply = if (it.has("reply") && !it.isNull("reply")) it.optString("reply") else null,
                )
            }
        }
        // Fila de uma versão anterior: mensagens que não saíram por falta de motor.
        val legacy = if (sessionId == s.loadSessionId()) s.loadPending() else emptyList()
        if (legacy.isNotEmpty()) {
            s.delete("chat-pending") // migrou: a fila passa a viver em chat-q-<conversa>
            return legacy.map { Turn(java.util.UUID.randomUUID().toString(), it, 0L, state = TurnState.WaitingEngine) }
        }
        return emptyList()
    }

    fun saveTurns(sessionId: String, turns: List<Turn>) {
        val s = store ?: return
        val name = "chat-q-" + key(sessionId)
        if (turns.isEmpty()) {
            s.delete(name)
            return
        }
        val items = JSONArray()
        turns.takeLast(MAX_TURNS).forEach {
            items.put(
                JSONObject().put("request_id", it.requestId).put("text", it.text).put("created_at", it.createdAt)
                    .put("executor", it.executor ?: "").put("state", it.state.wire)
                    .put("baseline", it.baseline ?: JSONObject.NULL).put("chat_id", it.chatId)
                    .put("reply", it.reply ?: JSONObject.NULL),
            )
        }
        s.write(name, JSONObject().put("items", items))
    }

    /** A fila de uma conversa que ainda não tinha identidade passa a ser da identidade que o motor deu. */
    fun moveTurns(from: String, to: String) {
        val s = store ?: return
        if (key(from) == key(to)) return
        s.delete("chat-q-" + key(from))
    }

    private companion object {
        const val MAX_CACHED = 200
        const val MAX_TURNS = 30
    }
}
