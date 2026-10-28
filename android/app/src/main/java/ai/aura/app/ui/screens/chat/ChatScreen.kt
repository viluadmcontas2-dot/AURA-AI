package ai.aura.app.ui.screens.chat

import ai.aura.app.offline.LinkState
import ai.aura.app.offline.LocalStore
import ai.aura.app.offline.OfflineReason
import ai.aura.app.ui.components.AuraButtonText
import ai.aura.app.ui.components.AuraComposerField
import ai.aura.app.ui.components.EmptyState
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.screens.rememberAuraStore
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.voice.LocalVoiceInput
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch

/**
 * Chat de verdade: a pessoa escreve, vê a bolha dela na hora e a resposta do AURA crescendo em texto. Sem plano, sem
 * rota, sem aprovação. Enviar nunca bloqueia (durante uma resposta a mensagem entra na fila) e Parar é um botão à parte.
 * Comandos "/" abrem telas do app ([onNavigate]). O microfone dita no próprio campo.
 *
 * A conversa em si mora no [hub] (além desta tela): sair para Sessões e voltar durante uma resposta continua a mesma resposta.
 *
 * @param entry como o chat foi aberto (mensagem a enviar, conversa em andamento ou rascunho)
 * @param onEntryChange o chat avisa a conversa atual para a Home guardar (rotação/aba)
 * @param onClose volta ao início
 * @param link estado do motor; ao voltar a "no ar", a mensagem guardada sai sozinha
 * @param onStartEngine botão "Iniciar o motor" (o mesmo do aviso global)
 */
@Composable
fun ChatScreen(
    gateway: EngineGateway,
    entry: ChatEntry,
    onEntryChange: (ChatEntry) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onLeaveDraft: (String) -> Unit = {},
    link: LinkState = LinkState.Unknown,
    onNavigate: (AppTarget, String) -> Unit = { _, _ -> },
    onStartEngine: (OfflineReason) -> Unit = {},
    store: LocalStore = rememberAuraStore(),
    hub: ChatHub = rememberChatHub(),
) {
    val navigate by rememberUpdatedState(onNavigate)
    val startEngine by rememberUpdatedState(onStartEngine)
    val entryChange by rememberUpdatedState(onEntryChange)
    val controller = remember(hub, gateway, entry.sessionId) {
        hub.open(
            gateway, store, entry,
            onEntryChange = { entryChange(it) },
            onNavigate = { target, rest -> navigate(target, rest) },
            onStartEngine = { reason -> startEngine(reason) },
        )
    }
    DisposableEffect(controller) {
        controller.setVisible(true)
        onDispose {
            controller.setVisible(false)
            controller.unbind()
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> controller.onResume()
                Lifecycle.Event.ON_START -> controller.setVisible(true)
                Lifecycle.Event.ON_STOP -> controller.setVisible(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    var text by rememberSaveable { mutableStateOf(entry.draft) }
    val voice = LocalVoiceInput.current
    var details by rememberSaveable { mutableStateOf(false) }
    var stick by remember { mutableIntStateOf(0) }

    LaunchedEffect(link) { if (link == LinkState.Online) controller.engineBack() }

    // Ao sair com texto não enviado, o rascunho volta para o campo da Início.
    val close = { if (text.isNotBlank()) onLeaveDraft(text); onClose() }
    BackHandler(enabled = true) { close() }

    val messages = controller.messages
    val title = messages.firstOrNull { it.fromUser }?.text ?: controller.queue.firstOrNull()?.text ?: "Nova conversa"
    val commands = ChatCommands.suggestionsFor(text)

    Box(modifier.fillMaxSize().background(AuraColors.Bg)) {
        Column(Modifier.fillMaxSize()) {
            ChatHeader(
                title = title, onBack = close,
                onNew = if ((messages.isNotEmpty() || controller.sessionKnown) && !controller.responding && controller.queue.isEmpty()) ({ controller.newConversation() }) else null,
                onSessions = { onNavigate(AppTarget.Sessions, "") },
                onDetails = { details = !details },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ChatBody(controller = controller, onFillComposer = { text = it }, showDetails = details, stick = stick)
            }
            if (commands.isNotEmpty()) {
                CommandsPanel(commands, onPick = { text = it.name + " " })
            }
            ImprovePromptBar(
                gateway = gateway, text = text, onTextChange = { text = it },
                onOpenConnections = { onNavigate(AppTarget.Connections, "") },
            )
            AuraComposerField(
                value = text,
                onValueChange = { text = it },
                // Enviar nunca espera a resposta: se o Claude ainda responde, a mensagem entra na fila.
                onSend = { if (controller.send(text)) { text = ""; stick++ } },
                placeholder = "Escreva para o AURA…",
                // Voz é só um jeito de digitar: o texto falado cai no campo e a pessoa confirma em enviar.
                onMic = voice?.let { listen -> { listen { spoken -> text = (text.trim() + " " + spoken.trim()).trim() } } },
                onStop = if (controller.responding) ({ controller.stop() }) else null,
            )
        }
    }
}

/** A conversa já tem id no motor: vale como "há o que começar de novo". */
private val ChatController.sessionKnown: Boolean get() = currentSessionId.isNotEmpty()

@Composable
private fun ChatBody(controller: ChatController, onFillComposer: (String) -> Unit, showDetails: Boolean, stick: Int) {
    val scroll = rememberScrollState()
    val messages = controller.messages
    val live = controller.live
    val notice = controller.notice
    val queue = controller.queue
    val clipboard = LocalClipboardManager.current
    var newBelow by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var lastMax by remember { mutableIntStateOf(0) }
    var forceBottom by remember { mutableStateOf(false) }
    val goDown = androidx.compose.runtime.rememberCoroutineScope()
    val atBottom by remember { derivedStateOf { scroll.maxValue - scroll.value <= NEAR_BOTTOM_PX } }

    // Mandou uma mensagem: vai para o fim. Chegou coisa nova: só acompanha se a pessoa já estava no fim.
    LaunchedEffect(stick) { if (stick > 0) forceBottom = true }
    LaunchedEffect(scroll.maxValue) {
        val wasAtBottom = scroll.value >= lastMax - NEAR_BOTTOM_PX
        if (wasAtBottom || forceBottom) {
            scroll.scrollTo(scroll.maxValue)
            newBelow = false
            forceBottom = false
        } else if (scroll.maxValue > lastMax) {
            newBelow = true
        }
        lastMax = scroll.maxValue
    }
    LaunchedEffect(atBottom) { if (atBottom) newBelow = false }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().testTag("lista-mensagens").verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showDetails) {
                DetailsPanel(
                    lines = detailLines(controller),
                    copied = copied,
                    onCopy = { clipboard.setText(AnnotatedString(controller.diagnosticReport())); copied = true },
                )
            }
            if (controller.loading) {
                ThinkingLine("Carregando conversa…")
            } else if (controller.refreshing) {
                QuietLine("Atualizando conversa…")
            }
            if (controller.fromCache && !controller.loading) QuietLine("Mostrando mensagens guardadas neste celular.")
            if (controller.hasOlder) {
                AuraButtonText(if (controller.loadingOlder) "Carregando…" else "Carregar mensagens anteriores", controller::loadOlder)
            }
            if (messages.isEmpty() && live == null && notice == null && queue.isEmpty() && !controller.loading) {
                EmptyState(
                    title = "Sobre o que vamos falar?",
                    body = "Escreva o que você quer fazer. Digite / para ver os comandos.",
                    actionText = "Ver comandos",
                    onAction = { onFillComposer("/") },
                )
            }
            messages.forEach { MessageBubble(it) }
            if (controller.externalRunning && live == null) ThinkingLine("O Claude está respondendo no terminal…")
            if (live != null) {
                val status = statusLine(live, controller.now, controller.timingConfig)
                LiveBubble(live, status = status)
                HealthNotice(healthOf(live, controller.now, controller.timingConfig), onPress = controller::press)
            }
            if (notice != null) {
                NoticeBubble(notice, onAction = controller::act, onPress = controller::press)
            } else if (live == null && queue.isEmpty() && lastIsAnswer(messages)) {
                AnswerActions(
                    onCopy = { clipboard.setText(AnnotatedString(messages.last().text)) },
                    onContinue = { controller.send("Continue.") },
                )
            }
            controller.info?.let { if (live == null) QuietLine(it) }
            queue.forEach { item ->
                QueuedBubble(
                    item,
                    onSendNow = { controller.sendNow(item.requestId) },
                    onCancel = { controller.cancelQueued(item.requestId) },
                )
            }
            if (controller.queuePaused && queue.isNotEmpty()) {
                QueuePausedBubble(queue.size, onContinue = controller::resumeQueue, onClear = controller::clearQueue)
            }
        }
        if (newBelow) {
            NewMessagesPill(
                onClick = { newBelow = false; goDown.launch { scroll.animateScrollTo(scroll.maxValue) } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            )
        }
    }
}

private const val NEAR_BOTTOM_PX = 120

/** O estado do chat em frases simples (sem texto de mensagens), para "Ver detalhes". */
private fun detailLines(c: ChatController): List<String> = listOf(
    "Conversa: " + c.currentSessionId.take(8).ifEmpty { "ainda não criada" },
    "Motor: " + if (c.responding) "respondendo" else "parado",
    "Fila: ${c.queue.size} mensagem(ns)" + if (c.queuePaused) " (parada)" else "",
)

/** A última bolha é resposta da IA a uma mensagem comum (não a um comando do app). */
internal fun lastIsAnswer(all: List<ChatMessage>): Boolean {
    val messages = all.filterNot { it.tool } // linhas de ferramenta do histórico não contam
    val last = messages.lastOrNull() ?: return false
    val before = messages.getOrNull(messages.size - 2) ?: return false
    return !last.fromUser && before.fromUser && !ChatCommands.isCommandText(before.text)
}
