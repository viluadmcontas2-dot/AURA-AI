package ai.aura.app.ui.screens.chat

import ai.aura.app.ui.components.AuraComposerField
import ai.aura.app.ui.components.EmptyState
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.JobStatus
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraTheme
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Fluxo de chat (Chat-1..5) por estado, sem navigation-compose:
 * pedido -> Entendi -> Começar -> acompanhar -> aprovação -> resultado em 4 blocos.
 * Composer ancorado (imePadding no próprio AuraComposerField); o texto digitado é guardado com
 * rememberSaveable e só é limpo quando o pedido é aceito.
 *
 * @param entry como o chat foi aberto (pedido, job a acompanhar ou rascunho)
 * @param onEntryChange o chat avisa o pedido/job atual para a Home guardar (rotação/aba)
 * @param onClose volta ao início; tarefas em andamento continuam no engine
 */
@Composable
fun ChatScreen(
    gateway: EngineGateway,
    entry: ChatEntry,
    onEntryChange: (ChatEntry) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onLeaveDraft: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val controller = remember(gateway) { ChatController(gateway, scope, entry, onEntryChange) }
    var text by rememberSaveable { mutableStateOf(entry.draft) }
    var sheetRequested by rememberSaveable { mutableStateOf(false) }

    val phase = controller.phase
    val job = (phase as? ChatPhase.Tracking)?.job
    val showSheet = sheetRequested && job != null && job.status == JobStatus.AwaitingApproval

    // Aprovação abre sozinha uma vez por pedido que passa a esperar o usuário (fluxo Chat-3 -> Chat-4).
    LaunchedEffect(job?.id, job?.status) {
        if (job?.status == JobStatus.AwaitingApproval) sheetRequested = true else sheetRequested = false
    }

    // Ao sair com texto não enviado, o rascunho volta para o campo da Início.
    val close = { if (text.isNotBlank()) onLeaveDraft(text); onClose() }
    BackHandler(enabled = true) { if (showSheet) sheetRequested = false else close() }

    val title = job?.title ?: controller.messages.lastOrNull { it.fromUser }?.text ?: "Nova conversa"
    val subtitle = job?.let { kindLabel(it.kind) } ?: "AURA"
    val commands = ChatCommands.suggestionsFor(text)
    val placeholder = when {
        job != null && !job.status.isTerminal -> "Mudou de ideia? Diga ao AURA…"
        job != null -> "Responder ao AURA…"
        else -> "Peça algo ao AURA…"
    }

    Box(modifier.fillMaxSize().background(AuraColors.Bg)) {
        Column(Modifier.fillMaxSize()) {
            ChatHeader(title = title, subtitle = subtitle, status = job, onBack = close)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ChatBody(
                    controller = controller,
                    onReviewApproval = { sheetRequested = true },
                    onFillComposer = { text = it },
                    onAdjust = { text = controller.adjust() },
                )
            }
            if (commands.isNotEmpty()) {
                CommandsPanel(commands, onPick = { text = it.name + " " })
            }
            AuraComposerField(
                value = text,
                onValueChange = { text = it },
                onSend = { if (controller.send(text)) text = "" },
                placeholder = placeholder,
            )
        }
        if (showSheet && job != null) {
            ApprovalSheet(
                job = job,
                busy = controller.busy,
                onApprove = { controller.approve() },
                onDecline = { controller.cancel(); sheetRequested = false },
                onDismiss = { sheetRequested = false },
            )
        }
    }
}

@Composable
private fun ChatBody(
    controller: ChatController,
    onReviewApproval: () -> Unit,
    onFillComposer: (String) -> Unit,
    onAdjust: () -> Unit,
) {
    val scroll = rememberScrollState()
    val phase = controller.phase
    val jobStatus = (phase as? ChatPhase.Tracking)?.job?.status
    LaunchedEffect(controller.messages.size, phase::class, jobStatus, controller.error) {
        scroll.scrollTo(scroll.maxValue)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        controller.messages.forEach { MessageBubble(it) }
        when (phase) {
            ChatPhase.Idle ->
                if (controller.messages.isEmpty() && controller.error == null) {
                    EmptyState(
                        title = "Sobre o que vamos falar?",
                        body = "Escreva o que você quer fazer. Digite / para ver os comandos.",
                        actionText = "Ver comandos",
                        onAction = { onFillComposer("/") },
                    )
                }
            ChatPhase.Routing -> ThinkingRow("Entendendo o seu pedido…")
            is ChatPhase.Understood -> UnderstoodCard(phase, onStart = controller::start, onAdjust = onAdjust)
            ChatPhase.Submitting -> ThinkingRow("Enviando ao AURA…")
            is ChatPhase.Tracking -> JobPanel(
                job = phase.job,
                events = controller.events,
                connectionLost = controller.connectionLost,
                busy = controller.busy,
                onReviewApproval = onReviewApproval,
                onStop = controller::cancel,
                onRetry = controller::retryJob,
                onExplain = { controller.send("Explicar em português simples: ${phase.job.title}") },
                onNew = controller::newRequest,
            )
        }
        controller.error?.let { ChatErrorCard(it) }
    }
}
