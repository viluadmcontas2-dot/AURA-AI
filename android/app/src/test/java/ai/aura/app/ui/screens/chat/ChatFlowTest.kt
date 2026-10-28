package ai.aura.app.ui.screens.chat

import ai.aura.app.offline.LinkState
import ai.aura.app.offline.LocalStore
import ai.aura.app.offline.OfflineReason
import ai.aura.app.ui.engine.ChatAction
import ai.aura.app.ui.engine.ChatEvent
import ai.aura.app.ui.engine.ChatFailure
import ai.aura.app.ui.engine.ChatLine
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.voice.LocalVoiceInput
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.CompletableDeferred

/** O chat de verdade, clicando de verdade (Compose + Robolectric, sem emulador). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Path.op real: sem isso o Robolectric descarta toques em balões de cantos desiguais
class ChatFlowTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val navigated = mutableListOf<Pair<AppTarget, String>>()
    private var engineStarted: OfflineReason? = null

    private fun open(
        gw: ScriptedChatGateway,
        link: () -> LinkState = { LinkState.Online },
        voice: ((String) -> Unit) -> Unit = {},
        withVoice: Boolean = false,
        entry: ChatEntry = ChatEntry(open = true),
    ) {
        rule.setContent {
            AuraTheme {
                CompositionLocalProvider(LocalVoiceInput provides (if (withVoice) voice else null)) {
                    ChatScreen(
                        gateway = gw, entry = entry, onEntryChange = {}, onClose = {},
                        link = link(), store = LocalStore(tmp.root),
                        onNavigate = { t, r -> navigated += t to r },
                        onStartEngine = { engineStarted = it },
                    )
                }
            }
        }
    }

    private fun has(text: String, substring: Boolean = false) =
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun waitText(text: String, substring: Boolean = false) {
        val end = System.currentTimeMillis() + 10_000
        while (!has(text, substring)) {
            check(System.currentTimeMillis() < end) { "não apareceu: $text" }
            rule.waitForIdle() // aplica o que voltou de IO na fila da thread principal e recompõe
            Thread.sleep(20)
        }
    }

    private fun type(text: String) {
        rule.onNodeWithContentDescription("Mensagem para o AURA").performTextInput(text)
        rule.onNodeWithContentDescription("Enviar mensagem").performClick()
    }

    private fun assertNoTaskUi() {
        for (t in listOf("Começar", "Ajustar pedido", "Por quê", "ENTENDI", "Usa um modelo de IA", "Altera arquivos",
            "FALTA", "PRÓXIMO PASSO", "subagent_stats", "ttft_ms", "Revisar e aprovar", "Parar tarefa")) {
            assertFalse("não deveria existir: $t", has(t, substring = true))
        }
    }

    @Test fun `mensagem comum aparece na hora, o texto cresce e termina sem cartao de tarefa`() {
        val gate = CompletableDeferred<Unit>()
        val gw = ScriptedChatGateway(script = {
            listOf(
                Step.Emit(ChatEvent.Text("Oi, tudo")),
                Step.Emit(ChatEvent.Tool("Lendo arquivos")),
                Step.Wait(gate),
                Step.Emit(ChatEvent.Text("Oi, tudo bem? Posso ajudar.")),
                Step.Emit(ChatEvent.Done("Oi, tudo bem? Posso ajudar.", "sess_1")),
            )
        })
        open(gw)
        type("Ola")

        waitText("Ola") // bolha da pessoa
        waitText("Oi, tudo") // texto chegando
        waitText("Lendo arquivos…") // linha discreta de ferramenta
        assertNoTaskUi()
        rule.onNodeWithContentDescription("Parar resposta").assertExists() // Parar no lugar do enviar

        gate.complete(Unit)
        waitText("Oi, tudo bem? Posso ajudar.")
        waitText("Copiar") // sobram texto e ações úteis
        waitText("Continuar")
        assertFalse(has("Lendo arquivos…"))
        assertNoTaskUi()
        rule.onNodeWithContentDescription("Enviar mensagem").assertExists()
    }

    @Test fun `Pensando aparece antes do primeiro texto, vira a ferramenta e o indicador some quando o texto cresce`() {
        val g1 = CompletableDeferred<Unit>()
        val g2 = CompletableDeferred<Unit>()
        val g3 = CompletableDeferred<Unit>()
        val gw = ScriptedChatGateway(script = {
            listOf(
                Step.Wait(g1),
                Step.Emit(ChatEvent.Tool("Lendo arquivos")),
                Step.Wait(g2),
                Step.Emit(ChatEvent.Text("Achei o problema")),
                Step.Wait(g3),
                Step.Emit(ChatEvent.Done("Achei o problema e corrigi.", "sess_1")),
            )
        })
        open(gw)
        type("Conserta o login")

        waitText("Pensando…") // entre o envio e a primeira palavra
        assertFalse(has("Achei o problema"))
        assertEquals(1, rule.onAllNodesWithTag("indicador-resposta", useUnmergedTree = true).fetchSemanticsNodes().size)

        g1.complete(Unit)
        waitText("Lendo arquivos…") // a ferramenta toma o lugar do "Pensando…"
        assertFalse(has("Pensando…"))

        g2.complete(Unit)
        waitText("Achei o problema") // o texto começa a crescer
        assertFalse(has("Pensando…"))
        assertEquals("sem indicador enquanto o texto cresce", 0,
            rule.onAllNodesWithTag("indicador-resposta", useUnmergedTree = true).fetchSemanticsNodes().size)

        g3.complete(Unit)
        waitText("Achei o problema e corrigi.")
        assertFalse("nada de aviso ruidoso ao terminar", has("Concluído", substring = true))
        assertFalse(has("Pensando…"))
        assertEquals(0, rule.onAllNodesWithTag("indicador-resposta", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun `comando com barra abre a tela e nunca chama o chat da IA`() {
        val gw = ScriptedChatGateway()
        open(gw)
        type("/projeto")

        waitText("Abri Projetos.")
        assertEquals(listOf(AppTarget.Projects to ""), navigated)
        assertTrue("comando não pode virar mensagem à IA", gw.started.isEmpty())

        type("/xyz")
        waitText("Não conheço /xyz", substring = true)
        assertTrue(gw.started.isEmpty())
        assertEquals(1, navigated.size)
    }

    @Test fun `a segunda mensagem leva o session_id da primeira`() {
        val gw = ScriptedChatGateway()
        open(gw)
        type("Primeira")
        waitText("Copiar")
        type("Segunda")
        rule.waitUntil(10_000) { gw.started.size == 2 }

        assertEquals(listOf("Primeira" to null, "Segunda" to "sess_1"), gw.started.toList())
    }

    @Test fun `sem motor a mensagem vira rascunho com Iniciar o motor e sai sozinha quando volta`() {
        val gw = ScriptedChatGateway(down = true)
        var link by mutableStateOf<LinkState>(LinkState.Offline(OfflineReason.EngineStopped))
        open(gw, link = { link })
        type("Oi")

        waitText("O motor está desligado.")
        assertTrue(gw.started.isEmpty())
        waitText("Oi") // a bolha dela continua
        val store = LocalStore(tmp.root)
        rule.waitUntil(10_000) { store.read("chat-pending")?.optString("text") == "Oi" }

        rule.onNodeWithText("Iniciar o motor").performClick()
        assertEquals(OfflineReason.EngineStopped, engineStarted)

        gw.down = false
        link = LinkState.Online // o aviso global ficou verde
        waitText("Olá!")
        assertEquals(listOf("Oi" to null), gw.started.toList())
        rule.waitUntil(10_000) { store.read("chat-pending") == null || store.read("chat-pending")?.optString("text").isNullOrEmpty() }
        assertFalse(has("O motor está desligado."))
    }

    @Test fun `erro de conta mostra Abrir Conexoes e o botao leva la`() {
        val gw = ScriptedChatGateway(script = {
            listOf(
                Step.Emit(ChatEvent.Failed(ChatFailure("not_logged_in", "O Claude ainda não está conectado neste aparelho.", ChatAction.OpenConnections))),
            )
        })
        open(gw)
        type("Oi")

        waitText("O Claude ainda não está conectado neste aparelho.")
        rule.onNodeWithText("Abrir Conexões").performClick()
        assertEquals(listOf(AppTarget.Connections to ""), navigated)
        assertNoTaskUi()
    }

    @Test fun `Parar chama o stop do motor e guarda o que ja chegou`() {
        val gw = ScriptedChatGateway(script = { listOf(Step.Emit(ChatEvent.Text("Pensando em voz alta")), Step.Wait()) })
        open(gw)
        type("Conte uma historia")

        waitText("Pensando em voz alta")
        rule.onNodeWithContentDescription("Parar resposta").performClick()

        rule.waitUntil(10_000) { gw.stopped.isNotEmpty() }
        assertEquals(listOf("chat_1"), gw.stopped.toList())
        rule.onNodeWithContentDescription("Enviar mensagem").assertExists() // voltou a poder enviar
        assertTrue(has("Pensando em voz alta"))
    }

    @Test fun `microfone usa a digitacao por voz e o texto cai no campo do mesmo chat`() {
        val gw = ScriptedChatGateway()
        var asked = 0
        open(gw, withVoice = true, voice = { onText -> asked++; onText("abrir o projeto da loja") })
        rule.onNodeWithContentDescription("Falar um pedido pelo microfone").performClick()

        assertEquals(1, asked)
        rule.onNodeWithContentDescription("Mensagem para o AURA").assert(hasText("abrir o projeto da loja"))
        assertTrue("falar não envia sozinho", gw.started.isEmpty())
        rule.onNodeWithContentDescription("Enviar mensagem").performClick()
        rule.waitUntil(10_000) { gw.started.size == 1 }
        assertEquals("abrir o projeto da loja", gw.started[0].first)
    }

    @Test fun `reabrir recupera a conversa do motor e guarda no disco`() {
        val lines = listOf(ChatLine(true, "Oi antiga"), ChatLine(false, "Resposta antiga"))
        open(ScriptedChatGateway(history = lines))
        waitText("Resposta antiga")
        val store = LocalStore(tmp.root)
        rule.waitUntil(10_000) { (store.read("chat-history")?.optJSONArray("items")?.length() ?: 0) == 2 }
        assertEquals("Oi antiga", store.read("chat-history")!!.getJSONArray("items").getJSONObject(0).getString("text"))
    }
}
