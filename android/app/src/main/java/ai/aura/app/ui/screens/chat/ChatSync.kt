package ai.aura.app.ui.screens.chat

import ai.aura.app.ui.engine.HistoryLine
import ai.aura.app.ui.engine.SessionLineRole

/** Tempos do chat (ms). Em produção são estes; testes que usam relógio falso podem encurtá-los. */
data class ChatTiming(
    /** Consulta do estado do motor enquanto há resposta em andamento. */
    val busyPollMs: Long = 2_000L,
    /** Consulta com a conversa aberta e parada; depois de [backoffAfter] consultas iguais passa a [idleBackoffMs]. */
    val idlePollMs: Long = 3_000L,
    val idleBackoffMs: Long = 10_000L,
    val backoffAfter: Int = 5,
    /** Sem avanço do Claude por tanto tempo: "Está demorando mais que o normal." */
    val slowAfterMs: Long = 30_000L,
    /** Sem avanço (mas com o motor vivo) por tanto tempo: "Ainda trabalhando". */
    val workingAfterMs: Long = 15_000L,
    /** Sem nenhum sinal do motor por tanto tempo: "Perdi o contato com o motor." */
    val lostAfterMs: Long = 15_000L,
    /** Parada pedida e não confirmada por tanto tempo: "Ainda não confirmei a parada." */
    val stopConfirmMs: Long = 10_000L,
    val tickMs: Long = 1_000L,
    /** Teto de cada chamada de leitura ao motor (nada fica esperando para sempre). */
    val callTimeoutMs: Long = 6_000L,
)

internal fun norm(s: String): String = s.trim().replace(Regex("\\s+"), " ")

/** O que o histórico do motor já mostra de uma mensagem enviada por este celular. */
internal class TurnView(val turn: Turn, val userConfirmed: Boolean, val replyConfirmed: Boolean)

/**
 * A mensagem está no histórico quando há mais mensagens iguais do que havia ao enviar ([Turn.baseline]); a resposta, quando
 * existe uma fala do Claude depois dela. Comparar texto é só para casar o que ESTE celular enviou; o histórico em si
 * nunca é mesclado por texto (as linhas do motor têm id próprio).
 */
internal fun viewOf(history: List<HistoryLine>, turn: Turn): TurnView {
    val b = turn.baseline ?: return TurnView(turn, false, false)
    val wanted = norm(turn.text)
    val at = history.indices.filter { history[it].role == SessionLineRole.User && norm(history[it].text) == wanted }
    if (at.size <= b) return TurnView(turn, false, false)
    val replied = history.drop(at[b] + 1).any { it.role == SessionLineRole.Assistant }
    return TurnView(turn, true, replied)
}

internal fun userCount(history: List<HistoryLine>, text: String): Int {
    val wanted = norm(text)
    return history.count { it.role == SessionLineRole.User && norm(it.text) == wanted }
}

/** Junta por id: a linha que já existe é trocada no lugar (a fala do Claude pode ter crescido); a nova entra no fim. */
internal fun mergeById(held: List<HistoryLine>, incoming: List<HistoryLine>): List<HistoryLine> {
    if (incoming.isEmpty()) return held
    val position = HashMap<String, Int>(held.size * 2)
    held.forEachIndexed { i, l -> position[l.id] = i }
    val out = held.toMutableList()
    for (line in incoming) {
        val at = position[line.id]
        if (at != null) out[at] = line else { position[line.id] = out.size; out += line }
    }
    return out
}

/** Linhas mais antigas entram na frente; as que já estão aqui não se repetem. */
internal fun prependOlder(held: List<HistoryLine>, older: List<HistoryLine>): List<HistoryLine> {
    val have = held.mapTo(HashSet()) { it.id }
    return older.filter { it.id !in have } + held
}

internal class Transcript(
    val messages: List<ChatMessage>,
    val queue: List<QueuedMessage>,
    /** Pedidos já respondidos e confirmados pelo histórico do motor: saem da lista local. */
    val resolved: Set<String>,
)

/** O que a tela mostra: o histórico do motor + as mensagens daqui que o histórico ainda não tem. Função pura. */
internal fun buildTranscript(history: List<HistoryLine>, turns: List<Turn>): Transcript {
    val out = ArrayList<ChatMessage>(history.size + 4)
    history.forEach {
        out += ChatMessage(out.size, it.role == SessionLineRole.User, it.text, tool = it.role == SessionLineRole.Tool)
    }
    val queue = ArrayList<QueuedMessage>()
    val resolved = HashSet<String>()
    for (turn in turns) {
        val view = viewOf(history, turn)
        when (turn.state) {
            TurnState.Queued -> queue += QueuedMessage(turn.requestId, turn.text, waitingEngine = false)
            TurnState.WaitingEngine -> queue += QueuedMessage(turn.requestId, turn.text, waitingEngine = true)
            TurnState.Sending -> if (!view.userConfirmed) out += ChatMessage(out.size, true, turn.text)
            TurnState.Answered -> {
                if (!view.userConfirmed) out += ChatMessage(out.size, true, turn.text)
                val reply = turn.reply
                if (reply != null && !view.replyConfirmed) out += ChatMessage(out.size, false, reply)
                if (view.userConfirmed && (reply == null || view.replyConfirmed)) resolved += turn.requestId
            }
            TurnState.Failed -> {
                val reply = turn.reply
                if (reply != null) {
                    // Parou no meio: o que já chegou fica na tela, junto com o pedido.
                    if (!view.userConfirmed) out += ChatMessage(out.size, true, turn.text)
                    if (!view.replyConfirmed) out += ChatMessage(out.size, false, reply)
                } else if (!view.userConfirmed) {
                    queue += QueuedMessage(turn.requestId, turn.text, waitingEngine = false, failed = true)
                }
            }
        }
    }
    return Transcript(out, queue, resolved)
}

// ---- "Pensando · 12 s" e os avisos de demora -------------------------------------------------

internal enum class LiveHealth { Normal, Working, Slow, Lost, StopUnconfirmed }

internal fun phaseLabel(phase: String): String = when (phase) {
    "reading_files" -> "Lendo arquivos"
    "editing_files" -> "Editando arquivos"
    "running_tool" -> "Rodando comando"
    "responding" -> "Escrevendo"
    else -> "Pensando"
}

internal fun elapsedLabel(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s < 60) "$s s" else "${s / 60} min ${(s % 60).toString().padStart(2, '0')} s"
}

internal fun healthOf(live: LiveReply, now: Long, timing: ChatTiming): LiveHealth = when {
    live.stopRequestedAt > 0 -> if (now - live.stopRequestedAt >= timing.stopConfirmMs) LiveHealth.StopUnconfirmed else LiveHealth.Normal
    live.lastSignalAt > 0 && now - live.lastSignalAt >= timing.lostAfterMs -> LiveHealth.Lost
    live.lastProgressAt > 0 && now - live.lastProgressAt >= timing.slowAfterMs -> LiveHealth.Slow
    live.lastProgressAt > 0 && now - live.lastProgressAt >= timing.workingAfterMs -> LiveHealth.Working
    else -> LiveHealth.Normal
}

/** A linha de estado da resposta: fase conhecida + tempo real decorrido. */
internal fun statusLine(live: LiveReply, now: Long, timing: ChatTiming): String {
    if (live.stopRequestedAt > 0 || live.stopping) return "Pedindo para parar…"
    val elapsed = if (live.startedAt > 0) elapsedLabel(now - live.startedAt) else null
    val label = if (healthOf(live, now, timing) == LiveHealth.Working && live.phase in setOf("starting", "waiting_claude", "unknown")) {
        "Ainda trabalhando"
    } else {
        phaseLabel(live.phase)
    }
    return if (elapsed == null) "$label…" else "$label · $elapsed"
}
