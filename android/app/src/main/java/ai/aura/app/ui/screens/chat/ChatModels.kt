package ai.aura.app.ui.screens.chat

import ai.aura.app.ui.components.StatusKind
import ai.aura.app.ui.engine.Capability
import ai.aura.app.ui.engine.JobRisk
import ai.aura.app.ui.engine.JobStatus
import ai.aura.app.ui.engine.RouteResult
import ai.aura.app.ui.engine.Job
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/**
 * O que a aba Início sabe sobre o chat aberto. Só strings, para sobreviver a rotação e a
 * morte do processo (rememberSaveable). O estado vivo (status do job) sempre vem do engine.
 */
data class ChatEntry(
    val open: Boolean = false,
    /** Pedido a enviar ao abrir o chat (vazio = chat em branco). */
    val prompt: String = "",
    val requestId: String = "",
    /** Job já existente a acompanhar (vazio = nenhum). */
    val jobId: String = "",
    /** Texto pré-preenchido no composer, sem enviar. */
    val draft: String = "",
) {
    companion object {
        val Closed = ChatEntry()
        val Saver: Saver<ChatEntry, Any> = listSaver<ChatEntry, String>(
            save = { listOf(if (it.open) "1" else "0", it.prompt, it.requestId, it.jobId, it.draft) },
            restore = { ChatEntry(it[0] == "1", it[1], it[2], it[3], it[4]) },
        )
    }
}

data class ChatMessage(val id: Int, val fromUser: Boolean, val text: String)

sealed interface ChatPhase {
    object Idle : ChatPhase
    object Routing : ChatPhase
    /** Rota entendida; nada foi iniciado. [blocked] != null = ferramenta indisponível neste aparelho. */
    class Understood(val route: RouteResult, val blocked: Capability?) : ChatPhase
    object Submitting : ChatPhase
    class Tracking(val job: Job) : ChatPhase
}

/** Erro mostrado com o que fazer; [retry] repete exatamente a última tentativa. */
class ChatError(val title: String, val body: String, val retry: () -> Unit)

class ChatCommand(val name: String, val description: String, private val expansion: String) {
    /** Texto em português simples que vai para o /v1/route. */
    fun expand(args: String): String = if (args.isBlank()) expansion else "$expansion: $args"
}

object ChatCommands {
    const val CONTINUE = "/continuar"
    val all: List<ChatCommand> = listOf(
        ChatCommand(CONTINUE, "Retomar de onde parou", "Continuar a tarefa em andamento"),
        ChatCommand("/projeto", "Abrir ou criar um projeto", "Abrir ou criar um projeto"),
        ChatCommand("/aparelho", "Falar com TV, celular ou multimídia", "Controlar um aparelho Android"),
        ChatCommand("/instalar", "Instalar um APK em um aparelho", "Instalar um APK em um aparelho"),
        ChatCommand("/explicar", "Explicar o último resultado em português simples", "Explicar o último resultado em português simples"),
    )

    /** Mostra o painel só enquanto o usuário digita a palavra do comando (sem espaço ainda). */
    fun suggestionsFor(text: String): List<ChatCommand> =
        if (text.startsWith("/") && text.none { it.isWhitespace() }) all.filter { it.name.startsWith(text.lowercase()) } else emptyList()

    fun find(text: String): ChatCommand? = all.firstOrNull { it.name == text.substringBefore(' ').lowercase() }
}

internal fun kindLabel(kind: String): String = when (kind) {
    "claude_cli", "claude_api" -> "Claude"
    "gemini_cli", "gemini_api" -> "Gemini"
    "agy_cli" -> "Antigravity"
    "github" -> "GitHub"
    "adb" -> "Controle de Android"
    "shell" -> "Terminal do Termux"
    "files" -> "Arquivos"
    "system" -> "Sistema"
    "skills" -> "Habilidades"
    else -> "Ferramenta do AURA"
}

internal fun riskLabel(risk: JobRisk): String = when (risk) {
    JobRisk.Read -> "Só leitura"
    JobRisk.Write -> "Altera arquivos"
    JobRisk.Spend -> "Pode gerar custo"
    JobRisk.Critical -> "Ação crítica"
}

internal fun riskKind(risk: JobRisk): StatusKind = when (risk) {
    JobRisk.Read -> StatusKind.Ready
    JobRisk.Write, JobRisk.Spend -> StatusKind.NeedsYou
    JobRisk.Critical -> StatusKind.Risk
}

internal fun riskWarning(risk: JobRisk): String = when (risk) {
    JobRisk.Read -> "Este pedido só lê informações."
    JobRisk.Write -> "Este pedido pode criar ou alterar arquivos e serviços conectados."
    JobRisk.Spend -> "Este pedido pode gerar custo. Confira antes de aprovar."
    JobRisk.Critical -> "Ação crítica. Pode não ser possível desfazer."
}

internal fun statusKind(status: JobStatus): StatusKind = when (status) {
    JobStatus.Queued, JobStatus.Cancelled -> StatusKind.Neutral
    JobStatus.AwaitingApproval, JobStatus.Interrupted -> StatusKind.NeedsYou
    JobStatus.Running -> StatusKind.Info
    JobStatus.Completed -> StatusKind.Ready
    JobStatus.Failed -> StatusKind.Risk
}

internal fun statusText(status: JobStatus): String = when (status) {
    JobStatus.Queued -> "Na fila"
    JobStatus.AwaitingApproval -> "Precisa de você"
    JobStatus.Running -> "Rodando"
    JobStatus.Completed -> "Concluído"
    JobStatus.Failed -> "Falhou"
    JobStatus.Cancelled -> "Cancelado"
    JobStatus.Interrupted -> "Interrompido"
}

internal fun errorText(code: String?): String = when (code) {
    "auth_required" -> "Falta entrar na conta do serviço (por exemplo, GitHub)."
    null -> "O motor não informou o motivo."
    else -> "O motor recusou ou não conseguiu terminar o pedido."
}
