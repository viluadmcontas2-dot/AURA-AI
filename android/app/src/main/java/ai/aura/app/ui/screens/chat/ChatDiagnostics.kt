package ai.aura.app.ui.screens.chat

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Anel de log em memória para "Copiar diagnóstico": as últimas [capacity] linhas com horário, execução, resultado de
 * cada consulta, erro, tempo esgotado e mudança de estado. Quem escreve passa só códigos, ids curtos e números:
 * NUNCA o texto de uma mensagem, resposta ou token.
 */
class ChatDiagnostics(private val clock: () -> Long = { System.currentTimeMillis() }, private val capacity: Int = 300) {
    private val ring = ArrayDeque<String>(capacity + 1)

    @Synchronized
    fun log(chatId: String?, what: String) {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(clock()))
        ring.addLast("$stamp [${chatId?.take(8)?.ifEmpty { null } ?: "-"}] ${what.take(160)}")
        while (ring.size > capacity) ring.removeFirst()
    }

    @Synchronized
    fun lines(): List<String> = ring.toList()

    /** Cabeçalho com o estado de agora + o anel. Sem texto de mensagem. */
    fun report(header: List<String>): String =
        (listOf("AURA - diagnóstico do chat") + header + listOf("--- últimas linhas ---") + lines()).joinToString("\n")
}
