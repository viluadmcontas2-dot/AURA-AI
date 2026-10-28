package ai.aura.app.ui.screens.sessions

import ai.aura.app.offline.LocalStore
import ai.aura.app.ui.engine.SessionInfo
import ai.aura.app.ui.screens.readList
import ai.aura.app.ui.screens.writeList
import org.json.JSONObject

/** As sessões que o motor mostrou da última vez: aparecem quando ele está desligado (nunca tela em branco). */
private const val SEEN = "sessions-seen"
private const val MAX_SEEN = 100

internal fun LocalStore.loadSessions(): List<SessionInfo> = readList(SEEN) { o ->
    val id = o.optString("id").ifEmpty { return@readList null }
    SessionInfo(
        id = id,
        title = o.optString("title").ifBlank { "Conversa sem título" },
        project = o.optString("project"),
        updatedAtMillis = o.optLong("updated_at"),
        messages = o.optInt("messages"),
        fromTerminal = o.optBoolean("terminal"),
        running = false, // guardada: não dá para saber se ainda responde
    )
}

internal fun LocalStore.saveSessions(sessions: List<SessionInfo>) = writeList(
    SEEN,
    sessions.take(MAX_SEEN).map {
        JSONObject().put("id", it.id).put("title", it.title).put("project", it.project)
            .put("updated_at", it.updatedAtMillis).put("messages", it.messages).put("terminal", it.fromTerminal)
    },
)

/** "há 5 min", "há 3 h", "há 2 d". Vazio se o motor não informou a data. */
internal fun agoLabel(thenMillis: Long, nowMillis: Long): String {
    if (thenMillis <= 0L) return ""
    val minutes = ((nowMillis - thenMillis) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 1 -> "há instantes"
        minutes < 60 -> "há $minutes min"
        minutes < 24 * 60 -> "há ${minutes / 60} h"
        minutes < 30 * 24 * 60 -> "há ${minutes / (24 * 60)} d"
        else -> "há mais de um mês"
    }
}

/** Nome do programa para a tela. O motor manda claude, agy, gemini, codex ou engine. */
internal fun processLabel(kind: String): String = when (kind) {
    "claude" -> "Claude Code"
    "agy" -> "Antigravity"
    "gemini" -> "Gemini"
    "codex" -> "Codex"
    "engine" -> "Motor do AURA"
    else -> kind.replaceFirstChar { it.uppercase() }
}

/** O motor não pode ser parado por aqui: parar o motor deixaria o AURA sem resposta. */
internal fun canStopProcess(kind: String): Boolean = kind != "engine"
