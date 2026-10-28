package ai.aura.app.ui.screens.chat

import ai.aura.app.offline.LocalStore
import ai.aura.app.offline.OfflineReason
import ai.aura.app.ui.engine.EngineGateway
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Onde as conversas moram: DEPOIS da tela. O hub vive com a Activity (sobrevive a girar o celular e a sair da tela do
 * chat para Sessões e voltar), então uma resposta em andamento nunca é perdida nem reiniciada porque a composição
 * morreu. Cada conversa tem o seu [ChatController], achado pelo id do motor; a que ainda não tem id é a "conversa atual".
 */
class ChatHub(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val timing: ChatTiming = ChatTiming(),
    private val retryMillis: Long = 3_000L,
    /** Escopo que sobrevive à composição. Em produção, a thread principal; testes podem trocar. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel() {
    private val bySession = LinkedHashMap<String, ChatController>()
    private var fresh: ChatController? = null
    private var forceNew = false

    /**
     * O controlador desta entrada. Com id de sessão, o da conversa (criado do disco se ainda não existe). Sem id, a última
     * conversa usada, ou uma nova depois de [startNewConversation].
     */
    fun open(
        gateway: EngineGateway,
        store: LocalStore?,
        entry: ChatEntry,
        onEntryChange: (ChatEntry) -> Unit,
        onNavigate: (AppTarget, String) -> Unit,
        onStartEngine: (OfflineReason) -> Unit,
    ): ChatController {
        val wanted = entry.sessionId.ifEmpty { if (forceNew) "" else ConversationStore(store).lastSessionId() }
        val existing = if (wanted.isNotEmpty()) bySession[wanted] else fresh
        if (existing != null) {
            existing.bind(gateway, entry, onEntryChange, onNavigate, onStartEngine)
            return existing
        }
        val holder = arrayOfNulls<ChatController>(1)
        val controller = ChatController(
            gateway, scope, entry.copy(sessionId = wanted), onEntryChange, store, onNavigate, onStartEngine,
            retryMillis = retryMillis, clock = clock, timing = timing,
            onIdentity = { id -> holder[0]?.let { register(it, id) } },
        )
        holder[0] = controller
        if (wanted.isNotEmpty()) {
            bySession[wanted] = controller
            forceNew = false
        } else {
            fresh = controller
        }
        // Um controlador que adotou o id durante a construção (conversa da última vez) é achado por ele.
        if (controller.currentSessionId.isNotEmpty()) register(controller, controller.currentSessionId)
        return controller
    }

    /** O controlador ganhou (ou perdeu) o id do motor: passa a ser achado por ele. */
    private fun register(controller: ChatController, id: String) {
        bySession.entries.removeAll { it.value === controller }
        if (id.isEmpty()) {
            fresh = controller
        } else {
            bySession[id] = controller
            if (fresh === controller) fresh = null
            forceNew = false
        }
    }

    /** "Criar conversa": a próxima conversa aberta sem id começa do zero (as antigas ficam guardadas). */
    fun startNewConversation(store: LocalStore?) {
        forceNew = true
        fresh = null
        ConversationStore(store).saveLastSessionId("")
    }

    override fun onCleared() {
        scope.cancel()
    }
}

private fun Context.findComponentActivity(): ComponentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is ComponentActivity) return c
        c = c.baseContext
    }
    return null
}

/** O hub da Activity (sobrevive a girar a tela); sem Activity (pré-visualização), um hub só desta composição. */
@Composable
fun rememberChatHub(): ChatHub {
    val activity = LocalContext.current.findComponentActivity()
    return if (activity != null) {
        remember(activity) {
            ViewModelProvider(
                activity,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatHub() as T
                },
            )[ChatHub::class.java]
        }
    } else {
        remember { ChatHub() }
    }
}
