package ai.aura.app.ui.screens.chat

import ai.aura.app.offline.LinkState
import ai.aura.app.offline.LocalStore
import ai.aura.app.offline.OfflineReason
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

/**
 * Chat de verdade: a pessoa escreve, vê a bolha dela na hora e a resposta do AURA crescendo em
 * texto. Sem plano, sem rota, sem aprovação. Comandos "/" abrem telas do app ([onNavigate]).
 * O microfone usa a digitação por voz do Google e o texto cai neste mesmo campo.
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
) {
    val scope = rememberCoroutineScope()
    val navigate by rememberUpdatedState(onNavigate)
    val startEngine by rememberUpdatedState(onStartEngine)
    val controller = remember(gateway) {
        ChatController(
            gateway, scope, entry, onEntryChange, store = store,
            onNavigate = { target, rest -> navigate(target, rest) },
            onStartEngine = { reason -> startEngine(reason) },
        )
    }
    var text by rememberSaveable { mutableStateOf(entry.draft) }
    val voice = LocalVoiceInput.current

    LaunchedEffect(link) { if (link == LinkState.Online) controller.engineBack() }

    // Ao sair com texto não enviado, o rascunho volta para o campo da Início.
    val close = { if (text.isNotBlank()) onLeaveDraft(text); onClose() }
    BackHandler(enabled = true) { close() }

    val messages = controller.messages
    val title = messages.firstOrNull { it.fromUser }?.text ?: "Nova conversa"
    val commands = ChatCommands.suggestionsFor(text)

    Box(modifier.fillMaxSize().background(AuraColors.Bg)) {
        Column(Modifier.fillMaxSize()) {
            ChatHeader(
                title = title, onBack = close,
                onNew = if (messages.isNotEmpty() && !controller.responding) ({ controller.newConversation() }) else null,
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ChatBody(controller = controller, onFillComposer = { text = it })
            }
            if (commands.isNotEmpty()) {
                CommandsPanel(commands, onPick = { text = it.name + " " })
            }
            AuraComposerField(
                value = text,
                onValueChange = { text = it },
                onSend = { if (controller.send(text)) text = "" },
                placeholder = "Escreva para o AURA…",
                // Voz é só um jeito de digitar: o texto falado cai no campo e a pessoa confirma em enviar.
                onMic = voice?.let { listen -> { listen { spoken -> text = (text.trim() + " " + spoken.trim()).trim() } } },
                onStop = if (controller.responding) ({ controller.stop() }) else null,
            )
        }
    }
}

@Composable
private fun ChatBody(controller: ChatController, onFillComposer: (String) -> Unit) {
    val scroll = rememberScrollState()
    val messages = controller.messages
    val live = controller.live
    val notice = controller.notice
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(messages.size, live?.text?.length, live?.tools?.size, live != null, notice) {
        scroll.scrollTo(scroll.maxValue)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (messages.isEmpty() && live == null && notice == null) {
            EmptyState(
                title = "Sobre o que vamos falar?",
                body = "Escreva o que você quer fazer. Digite / para ver os comandos.",
                actionText = "Ver comandos",
                onAction = { onFillComposer("/") },
            )
        }
        messages.forEach { MessageBubble(it) }
        if (live != null) {
            if (live.text.isBlank() && live.tools.isEmpty()) TypingBubble() else LiveBubble(live)
        } else if (notice != null) {
            NoticeBubble(notice, onAction = controller::act)
        } else if (lastIsAnswer(messages)) {
            AnswerActions(
                onCopy = { clipboard.setText(AnnotatedString(messages.last().text)) },
                onContinue = { controller.send("Continue.") },
            )
        }
    }
}

/** A última bolha é resposta da IA a uma mensagem comum (não a um comando do app). */
internal fun lastIsAnswer(messages: List<ChatMessage>): Boolean {
    val last = messages.lastOrNull() ?: return false
    val before = messages.getOrNull(messages.size - 2) ?: return false
    return !last.fromUser && before.fromUser && !ChatCommands.isCommandText(before.text)
}
