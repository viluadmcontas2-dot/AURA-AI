package ai.aura.app.ui.screens.sessions

import ai.aura.app.offline.LinkState
import ai.aura.app.offline.LocalStore
import ai.aura.app.offline.OfflineReason
import ai.aura.app.ui.engine.Capability
import ai.aura.app.ui.engine.EngineEvent
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.EngineResult
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobRequest
import ai.aura.app.ui.engine.ProcessInfo
import ai.aura.app.ui.engine.RouteResult
import ai.aura.app.ui.engine.SessionInfo
import ai.aura.app.ui.theme.AuraTheme
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
import java.util.concurrent.CopyOnWriteArrayList

private const val NOW = 1_700_000_000_000L

private val AURA = SessionInfo("aura-1", "Arrumar o login", "meu/app", NOW - 5 * 60_000, 4, fromTerminal = false, running = true)
private val TERMINAL = SessionInfo("term-1", "Ideia no terminal", "casa/loja", NOW - 3 * 3_600_000, 2, fromTerminal = true, running = false)

/** Motor de mentira só para as sessões: guarda o que o app pediu e muda a lista como o motor mudaria. */
private class SessionsFake(
    var sessions: EngineResult<List<SessionInfo>> = EngineResult.Ok(listOf(AURA, TERMINAL)),
    var processes: EngineResult<List<ProcessInfo>> = EngineResult.Ok(emptyList()),
) : EngineGateway {
    val renamed = CopyOnWriteArrayList<Pair<String, String>>()
    val hidden = CopyOnWriteArrayList<String>()
    val stopped = CopyOnWriteArrayList<String>()
    val killed = CopyOnWriteArrayList<Long>()

    override val events: Flow<EngineEvent> = emptyFlow()

    override suspend fun sessions(): EngineResult<List<SessionInfo>> = sessions
    override suspend fun processes(): EngineResult<List<ProcessInfo>> = processes

    override suspend fun renameSession(id: String, title: String): EngineResult<Unit> {
        renamed += id to title
        (sessions as? EngineResult.Ok)?.let { ok -> sessions = EngineResult.Ok(ok.value.map { if (it.id == id) it.copy(title = title) else it }) }
        return EngineResult.Ok(Unit)
    }

    override suspend fun hideSession(id: String): EngineResult<Unit> {
        hidden += id
        (sessions as? EngineResult.Ok)?.let { ok -> sessions = EngineResult.Ok(ok.value.filterNot { it.id == id }) }
        return EngineResult.Ok(Unit)
    }

    override suspend fun stopSession(id: String): EngineResult<Unit> {
        stopped += id
        (sessions as? EngineResult.Ok)?.let { ok -> sessions = EngineResult.Ok(ok.value.map { if (it.id == id) it.copy(running = false) else it }) }
        return EngineResult.Ok(Unit)
    }

    override suspend fun stopProcess(pid: Long): EngineResult<Unit> {
        killed += pid
        (processes as? EngineResult.Ok)?.let { ok -> processes = EngineResult.Ok(ok.value.filterNot { it.pid == pid }) }
        return EngineResult.Ok(Unit)
    }

    override suspend fun jobs(): List<Job> = error("não usado")
    override suspend fun approve(id: String): Job = error("não usado")
    override suspend fun cancel(id: String): Job = error("não usado")
    override suspend fun route(text: String): RouteResult = error("não usado")
    override suspend fun submit(requestId: String, request: JobRequest): Job = error("não usado")
    override suspend fun capabilities(): List<Capability> = emptyList()
}

/** Tela Sessões: lista, abrir, renomear, esconder, parar e "No celular agora". Clica nos botões reais, na JVM. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionsScreenTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val opened = mutableListOf<String>()
    private var newChats = 0
    private var engineAction: OfflineReason? = null

    private fun show(gw: EngineGateway, link: LinkState = LinkState.Online) {
        rule.setContent {
            AuraTheme {
                SessionsScreen(
                    gateway = gw, link = link, onEngineAction = { engineAction = it },
                    onOpenSession = { opened += it }, onNewChat = { newChats++ }, onBack = {},
                    store = LocalStore(tmp.root), nowMillis = { NOW },
                )
            }
        }
    }

    private fun has(text: String, substring: Boolean = false) =
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun waitText(text: String, substring: Boolean = false) {
        val end = System.currentTimeMillis() + 10_000
        while (!has(text, substring)) {
            check(System.currentTimeMillis() < end) { "não apareceu: $text" }
            rule.waitForIdle()
            Thread.sleep(20)
        }
    }

    private fun gone(text: String) {
        val end = System.currentTimeMillis() + 10_000
        while (has(text)) {
            check(System.currentTimeMillis() < end) { "não sumiu: $text" }
            rule.waitForIdle()
            Thread.sleep(20)
        }
    }

    @Test fun `a lista mostra de onde vem cada sessao e tocar abre com o id certo`() {
        show(SessionsFake())
        waitText("Arrumar o login")

        assertTrue(has("No AURA"))
        assertTrue(has("No terminal"))
        assertTrue(has("meu/app", substring = true))
        assertTrue(has("casa/loja", substring = true))
        assertTrue(has("há 5 min", substring = true))
        assertTrue(has("há 3 h", substring = true))
        assertEquals("só a sessão que responde ganha o ponto", 1, rule.onAllNodesWithText("Respondendo agora", useUnmergedTree = true).fetchSemanticsNodes().size)

        rule.onNodeWithText("Ideia no terminal").performClick()
        assertEquals(listOf("term-1"), opened)
        rule.onNodeWithText("Arrumar o login").performClick()
        assertEquals(listOf("term-1", "aura-1"), opened)

        // O que o motor mostrou fica guardado (gravação em segundo plano) para quando ele estiver desligado.
        rule.waitUntil(10_000) { LocalStore(tmp.root).loadSessions().size == 2 }
        assertEquals(listOf("aura-1", "term-1"), LocalStore(tmp.root).loadSessions().map { it.id })
    }

    @Test fun `renomear chama o gateway com o nome novo e a lista mostra o nome`() {
        val gw = SessionsFake()
        show(gw)
        waitText("Ideia no terminal")

        rule.onNodeWithContentDescription("Ações de Ideia no terminal").performClick()
        rule.onNodeWithText("Renomear").performClick()
        rule.onNode(hasSetTextAction()).performTextClearance()
        rule.onNode(hasSetTextAction()).performTextInput("Loja nova")
        rule.onNodeWithText("Salvar nome").performClick()

        rule.waitUntil(10_000) { gw.renamed.isNotEmpty() }
        assertEquals(listOf("term-1" to "Loja nova"), gw.renamed.toList())
        waitText("Loja nova")
        assertFalse(has("Ideia no terminal"))
    }

    @Test fun `esconder uma sessao do terminal explica que so esconde no AURA e pede confirmacao`() {
        val gw = SessionsFake()
        show(gw)
        waitText("Ideia no terminal")

        rule.onNodeWithContentDescription("Ações de Ideia no terminal").performClick()
        rule.onNodeWithText("Esconder").performClick()
        waitText("só esconde", substring = true)
        assertTrue("diz que o terminal fica como está", has("No terminal ela continua", substring = true))
        assertTrue("ainda não escondeu nada", gw.hidden.isEmpty())

        rule.onNodeWithText("Esconder conversa").performClick()
        rule.waitUntil(10_000) { gw.hidden.isNotEmpty() }
        assertEquals(listOf("term-1"), gw.hidden.toList())
        gone("Ideia no terminal")
        assertTrue(has("Arrumar o login"))
    }

    @Test fun `parar so existe para a sessao que esta respondendo e chama o gateway dela`() {
        val gw = SessionsFake()
        show(gw)
        waitText("Ideia no terminal")

        rule.onNodeWithContentDescription("Ações de Ideia no terminal").performClick()
        waitText("Renomear")
        assertFalse("sessão parada não tem Parar", has("Parar resposta"))

        rule.onNodeWithContentDescription("Ações de Arrumar o login").performClick()
        waitText("Parar resposta")
        rule.onNodeWithText("Parar resposta").performClick()
        rule.waitUntil(10_000) { gw.stopped.isNotEmpty() }
        assertEquals(listOf("aura-1"), gw.stopped.toList())
        gone("Respondendo agora")
    }

    @Test fun `No celular agora lista os programas do motor e Parar chama o processo certo`() {
        val gw = SessionsFake(
            processes = EngineResult.Ok(listOf(
                ProcessInfo(4242, "claude", "aura-1", NOW - 2 * 60_000),
                ProcessInfo(7, "gemini", null, null),
                ProcessInfo(1, "engine", null, null),
            )),
        )
        show(gw)
        waitText("No celular agora")
        waitText("Claude Code")
        assertTrue(has("Gemini"))
        assertTrue(has("Motor do AURA"))
        assertTrue("a linha liga o programa à conversa", has("Conversa: Arrumar o login", substring = true))
        assertFalse("o motor não tem botão", has("Parar Motor do AURA"))

        rule.onNodeWithText("Parar Claude Code").performClick()
        rule.waitUntil(10_000) { gw.killed.isNotEmpty() }
        assertEquals(listOf(4242L), gw.killed.toList())
        gone("Parar Claude Code")
        assertTrue(has("Parar Gemini"))
    }

    @Test fun `sem nenhuma conversa a tela convida a escrever`() {
        show(SessionsFake(sessions = EngineResult.Ok(emptyList())))
        waitText("Nenhuma conversa ainda. Escreva algo para começar.")
        rule.onNodeWithText("Escrever no chat").performClick()
        assertEquals(1, newChats)
    }

    @Test fun `motor desligado mostra o aviso com Iniciar o motor e as sessoes ja vistas`() {
        LocalStore(tmp.root).saveSessions(listOf(TERMINAL))
        val gw = SessionsFake(sessions = EngineResult.Unavailable("Termux não responde"), processes = EngineResult.Unavailable("Termux não responde"))
        show(gw, link = LinkState.Offline(OfflineReason.EngineStopped))

        waitText("O motor está desligado.", substring = true)
        waitText("Ideia no terminal") // guardada no disco, nunca tela em branco
        assertTrue(has("No terminal"))
        rule.onNodeWithText("Iniciar o motor").performClick()
        assertEquals(OfflineReason.EngineStopped, engineAction)

        rule.onNodeWithText("Ideia no terminal").performClick()
        assertEquals(listOf("term-1"), opened)
    }

    @Test fun `motor desligado e nada guardado ainda mostra o aviso e a acao`() {
        val gw = SessionsFake(sessions = EngineResult.Unavailable("Termux não responde"))
        show(gw, link = LinkState.Offline(OfflineReason.EngineStopped))
        waitText("O motor está desligado.", substring = true)
        rule.onNodeWithText("Iniciar o motor").performClick()
        assertEquals(OfflineReason.EngineStopped, engineAction)
    }
}
