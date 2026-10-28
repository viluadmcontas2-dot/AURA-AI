package ai.aura.app.ui.screens

import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.screens.chat.ChatEntry
import ai.aura.app.ui.screens.chat.ChatScreen
import ai.aura.app.ui.screens.home.HomeContent
import ai.aura.app.ui.theme.AuraTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

/**
 * Aba Início. Alterna por estado entre a Home e o fluxo de chat (sem navigation-compose).
 * O ChatEntry é salvo (rememberSaveable): rotação e troca de aba voltam ao mesmo pedido/tarefa.
 * Assinatura estável (gateway, modifier), como o AuraApp espera.
 */
@Composable
fun HomeScreen(
    gateway: EngineGateway,
    modifier: Modifier = Modifier,
    onChatOpenChange: (Boolean) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    openJobId: String? = null,
    onOpenJobHandled: () -> Unit = {},
    draft: String = "",
    onDraftChange: (String) -> Unit = {},
) {
    var entry by rememberSaveable(stateSaver = ChatEntry.Saver) { mutableStateOf(ChatEntry.Closed) }
    // Avisa o shell para esconder a barra inferior enquanto o chat estiver aberto.
    LaunchedEffect(entry.open) { onChatOpenChange(entry.open) }
    DisposableEffect(Unit) { onDispose { onChatOpenChange(false) } }
    // Atividade pediu para reabrir uma tarefa no chat (o ChatController restaura por jobId).
    LaunchedEffect(openJobId) {
        if (!openJobId.isNullOrEmpty()) {
            entry = ChatEntry(open = true, jobId = openJobId)
            onOpenJobHandled()
        }
    }
    if (entry.open) {
        ChatScreen(
            gateway = gateway,
            entry = entry,
            onEntryChange = { entry = it },
            onClose = { entry = ChatEntry.Closed },
            onLeaveDraft = onDraftChange,
            modifier = modifier,
        )
    } else {
        HomeContent(gateway = gateway, onOpenChat = { entry = it }, modifier = modifier, onOpenSettings = onOpenSettings,
            initialDraft = draft, onDraftChange = onDraftChange)
    }
}
