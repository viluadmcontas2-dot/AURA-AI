package ai.aura.app.integration

import ai.aura.app.ui.engine.ChatAction
import ai.aura.app.ui.engine.ChatFailure

/** Códigos do motor que só se resolvem em Conexões (instalar ou entrar na conta de IA). */
private val NEEDS_CONNECTIONS = setOf("cli_missing", "no_executor", "not_logged_in")

/**
 * Erro de conversa em português. A ação sai do CÓDIGO do motor (lista explícita, nunca por pedaço do
 * texto); a frase é a `message_pt` dele. Todo outro erro vira "Tentar de novo". `stopped` é calmo:
 * quem decide não mostrar alarme é a conversa (ver [ChatFailure.code]).
 */
internal fun chatFailureFor(code: String?, messagePt: String?): ChatFailure {
    val c = code?.takeIf { it.isNotBlank() } ?: "chat_error"
    val action = when {
        c == "engine_down" -> ChatAction.StartEngine
        c in NEEDS_CONNECTIONS -> ChatAction.OpenConnections
        else -> ChatAction.Retry
    }
    val fallback = when {
        c == "stopped" -> "Resposta interrompida."
        action == ChatAction.OpenConnections -> "O AURA ainda não está conectado a uma conta de IA. Abra Conexões para entrar."
        action == ChatAction.StartEngine -> "O motor está desligado."
        else -> "Não consegui responder agora. Tente de novo."
    }
    return ChatFailure(c, messagePt?.takeIf { it.isNotBlank() } ?: fallback, action)
}
