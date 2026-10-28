package ai.aura.app.ui.screens

import ai.aura.app.offline.LinkState
import ai.aura.app.offline.LocalStore
import ai.aura.app.offline.OfflineReason
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Aba Início. Alterna por estado entre a Home e o fluxo de chat (sem navigation-compose).
 * O ChatEntry é salvo (rememberSaveable): rotação e troca de aba voltam ao mesmo pedido/tarefa.
 * Assinatura estável (gateway, modifier), como o AuraApp espera; os parâmetros novos têm padrão.
 *
 * O rascunho mora no disco ([store], lido fora da thread principal); [draft] (preferências do v5) só vale
 * se o disco não tem nada. [link] e [onEngineAction] vêm de quem sabe o estado do motor.
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
    store: LocalStore = rememberAuraStore(),
    link: LinkState = LinkState.Unknown,
    onEngineAction: ((OfflineReason) -> Unit)? = null,
) {
    var entry by rememberSaveable(stateSaver = ChatEntry.Saver) { mutableStateOf(ChatEntry.Closed) }
    var current by remember { mutableStateOf<String?>(null) } // null = ainda lendo o disco
    val scope = rememberCoroutineScope()
    LaunchedEffect(store) {
        val saved = withContext(Dispatchers.IO) { store.read("drafts")?.optString("home") }
        if (current == null) current = saved ?: draft
    }
    // shortcut: uma gravação por tecla, em paralelo; se virar problema, junte as teclas (debounce) e serialize.
    val saveDraft: (String) -> Unit = { text ->
        current = text
        onDraftChange(text)
        scope.launch(Dispatchers.IO + NonCancellable) { store.write("drafts", JSONObject().put("home", text)) }
    }
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
            onLeaveDraft = saveDraft,
            modifier = modifier,
        )
    } else {
        current?.let { start ->
            HomeContent(
                gateway = gateway, onOpenChat = { entry = it }, modifier = modifier, onOpenSettings = onOpenSettings,
                initialDraft = start, onDraftChange = saveDraft, link = link, onEngineAction = onEngineAction,
            )
        }
    }
}
