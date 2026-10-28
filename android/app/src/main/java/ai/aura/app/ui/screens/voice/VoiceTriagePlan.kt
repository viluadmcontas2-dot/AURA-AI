package ai.aura.app.ui.screens.voice

import ai.aura.app.integration.QueueGateway
import kotlinx.coroutines.CancellationException

/** Para onde vai um pedido falado ao tocar em Enviar. */
sealed interface VoiceOutcome {
    /** Vira mensagem no chat (mesmo caminho do composer). */
    data class Chat(val text: String) : VoiceOutcome

    /** Já há trabalho rodando: o dono escolhe onde o pedido entra. [taskId] é o da entrada no motor. */
    data class Triage(val taskId: String, val state: TriageUiState) : VoiceOutcome

    /** O motor entendeu "pare" e já parou o trabalho atual; não nasce pedido novo. */
    object Stopped : VoiceOutcome
}

/** Escolha da triagem como o motor espera (POST /v1/inputs/{id}/apply). */
val TriageChoice.mode: String
    get() = when (this) {
        TriageChoice.AfterCurrent -> "later"
        TriageChoice.MergeIntoCurrent -> "merge"
        TriageChoice.StopAndDoNow -> "now"
    }

/**
 * Só cai na triagem quando o motor informa um trabalho rodando e classifica o pedido como novo ou ajuste.
 * Qualquer falha (motor fora, resposta ruim) devolve [VoiceOutcome.Chat]: o chat já sabe avisar "motor desligado".
 */
suspend fun planVoiceRequest(queue: QueueGateway, text: String): VoiceOutcome = try {
    val snapshot = queue.queue()
    if (snapshot.runningTitle == null) {
        VoiceOutcome.Chat(text)
    } else {
        val input = queue.receive(text, "voice")
        when (input.kind) {
            "new_task", "adjust" -> VoiceOutcome.Triage(
                input.id,
                TriageUiState(
                    heard = input.clean.ifBlank { text },
                    currentTitle = snapshot.runningTitle,
                    step = 0, total = 0,
                    queued = snapshot.queued, saved = snapshot.saved,
                ),
            )
            "stop" -> VoiceOutcome.Stopped
            else -> VoiceOutcome.Chat(text)
        }
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    VoiceOutcome.Chat(text)
}
