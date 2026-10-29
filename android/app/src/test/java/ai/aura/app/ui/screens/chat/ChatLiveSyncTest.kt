package ai.aura.app.ui.screens.chat

import ai.aura.app.offline.LinkState
import ai.aura.app.offline.LocalStore
import ai.aura.app.ui.engine.SessionLineRole
import ai.aura.app.ui.theme.AuraTheme
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import java.time.Duration
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * O chat como VISÃO do estado do motor (requisitos do fluxo contínuo): sem tocar na tela, com o fluxo de eventos
 * morto, saindo e voltando, e com o relógio do looper avançado por [pass] (tempo virtual: 30 s passam em instantes).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatLiveSyncTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()

    private val engine = LiveEngineGateway()
    private lateinit var hub: ChatHub
    private var show by mutableStateOf(true)
    private var entry by mutableStateOf(ChatEntry(open = true))

    @Before fun setUp() {
        hub = ChatHub(clock = { SystemClock.uptimeMillis() }, retryMillis = 500)
    }

    @After fun tearDown() {
        hub.scope.cancel()
    }

    private fun open(timing: ChatTiming? = null) {
        if (timing != null) {
            hub.scope.cancel()
            hub = ChatHub(clock = { SystemClock.uptimeMillis() }, timing = timing, retryMillis = 500)
        }
        rule.setContent {
            AuraTheme {
                if (show) {
                    ChatScreen(
                        gateway = engine, entry = entry, onEntryChange = { entry = it }, onClose = {},
                        link = LinkState.Online, store = LocalStore(tmp.root), hub = hub,
                    )
                }
            }
        }
        pass(50)
    }

    /** Deixa [ms] de tempo VIRTUAL passar (os `delay` do chat andam junto) e a tela recompor. */
    private fun pass(ms: Long = 250) {
        rule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        rule.waitForIdle()
        Thread.sleep(2)
    }

    private fun has(text: String, substring: Boolean = false) =
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    /** Passa o tempo até [cond]; devolve quantos ms virtuais foram. Falha se passar de [limit]. */
    private fun passUntil(limit: Long, what: String, step: Long = 250, cond: () -> Boolean): Long {
        var spent = 0L
        while (!cond()) {
            check(spent <= limit) { "não aconteceu em ${limit / 1000} s: $what" }
            pass(step)
            spent += step
        }
        return spent
    }

    private fun type(text: String) {
        rule.onNodeWithContentDescription("Mensagem para o AURA").performTextInput(text)
        rule.onNodeWithContentDescription("Enviar mensagem").performClick()
        pass(50)
    }

    private fun statusSeconds(): Int? = rule.onAllNodesWithText("Pensando · ", substring = true).fetchSemanticsNodes()
        .firstNotNullOfOrNull { n ->
            val text = (if (n.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text)) n.config[androidx.compose.ui.semantics.SemanticsProperties.Text] else emptyList())
                .joinToString { it.text }
            Regex("Pensando · (\\d+) s").find(text)?.groupValues?.get(1)?.toInt()
        }

    // ---- requisito central: não existe "Pensando" preso ----

    @Test fun `fluxo de eventos morto mas o motor termina a resposta, ela aparece em ate 5 s e o Pensando some`() {
        engine.streamAlive = false // morreram /v1/events e GET /v1/chat/{id}; só /v1/sessions/{id}/state responde
        open()
        type("Quero uma lista de compras")
        passUntil(2_000, "Pensando") { has("Pensando", substring = true) }

        engine.finish("chat_1", "Aqui está a lista de compras")
        val spent = passUntil(5_000, "a resposta na tela") { has("Aqui está a lista de compras") }

        assertTrue("em até 5 s (levou $spent ms)", spent <= 5_000)
        assertFalse("o Pensando some", has("Pensando", substring = true))
        assertFalse(rule.onAllNodesWithContentDescription("Parar resposta").fetchSemanticsNodes().isNotEmpty())
        assertEquals("a pergunta aparece uma vez só", 1, rule.onAllNodesWithContentDescription("Você: Quero uma lista de compras").fetchSemanticsNodes().size)
        assertEquals(1, engine.calls.size)
    }

    @Test fun `sair para Sessoes e voltar durante a resposta continua a mesma resposta, sem reiniciar`() {
        open()
        type("Faça o relatório")
        passUntil(2_000, "Pensando") { has("Pensando", substring = true) }
        val sid = engine.calls[0].sessionId ?: engine.chats.getValue("chat_1").sid

        show = false // Sessões abriu: a tela do chat saiu da composição
        pass(3_000)
        engine.finish("chat_1", "Relatório pronto")
        pass(2_500) // a conferência segue sozinha, com a tela fora

        entry = ChatEntry(open = true, sessionId = sid) // a pessoa tocou na conversa em Sessões
        show = true
        passUntil(3_000, "a resposta ao voltar") { has("Relatório pronto") }

        assertEquals("não reiniciou nem mandou de novo", 1, engine.calls.size)
        assertFalse(has("Pensando", substring = true))
        assertEquals(1, rule.onAllNodesWithContentDescription("Você: Faça o relatório").fetchSemanticsNodes().size)
    }

    @Test fun `sair e voltar com a resposta ainda em andamento mostra o Pensando de onde parou`() {
        open()
        type("Tarefa longa")
        pass(6_000)
        val before = statusSeconds()
        assertTrue("já passaram uns 6 s (era $before)", before != null && before >= 5)
        val sid = engine.chats.getValue("chat_1").sid

        show = false
        pass(4_000)
        entry = ChatEntry(open = true, sessionId = sid)
        show = true
        pass(1_000)

        val after = statusSeconds()
        assertTrue("o relógio não reiniciou (era $before, ficou $after)", after != null && after >= 10)
        assertEquals(1, engine.calls.size)
    }

    @Test fun `Pensando com segundos avanca sozinho, depois vem Esta demorando e Perdi o contato`() {
        open()
        type("Faça algo demorado")
        pass(5_000)
        val a = statusSeconds()
        assertTrue("avança sozinho (era $a)", a != null && a in 4..6)
        pass(5_000)
        val b = statusSeconds()
        assertTrue("segue avançando (era $b)", b != null && b >= 9)

        // 30 s sem o Claude avançar
        passUntil(40_000, "aviso de demora", step = 1_000) { has("Está demorando mais que o normal.") }
        assertTrue(has("Continuar esperando"))
        assertTrue(has("Parar"))
        rule.onNodeWithText("Continuar esperando").performClick()
        pass(100)
        assertFalse("Continuar esperando dispensa o aviso", has("Está demorando mais que o normal."))

        // o motor some: depois de 15 s sem sinal
        engine.down = true
        passUntil(30_000, "perdi o contato", step = 1_000) { has("Perdi o contato com o motor.") }
        assertTrue(has("Tentar de novo"))
        engine.down = false
        passUntil(10_000, "voltou a falar com o motor", step = 500) { !has("Perdi o contato com o motor.") }
    }

    @Test fun `Copiar diagnostico nao leva nenhum texto de mensagem`() {
        open()
        type("SEGREDO-DA-MENSAGEM-123")
        pass(500)
        engine.finish("chat_1", "RESPOSTA-SECRETA-456")
        passUntil(5_000, "resposta") { has("RESPOSTA-SECRETA-456") }

        rule.onNodeWithText("Ver detalhes").performClick()
        pass(50)
        rule.onNodeWithText("Copiar diagnóstico").performClick()
        pass(50)

        val clipboard = rule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertTrue("copiou o diagnóstico", copied.contains("diagnóstico do chat"))
        assertFalse(copied.contains("SEGREDO-DA-MENSAGEM-123"))
        assertFalse(copied.contains("RESPOSTA-SECRETA-456"))
        assertTrue("tem linhas do anel", copied.lines().size > 6)
        assertTrue(has("Diagnóstico copiado."))
    }

    // ---- histórico vivo ----

    @Test fun `mensagem acrescentada por fora aparece em ate 4 s sem tocar na tela`() {
        engine.seed("S1", SessionLineRole.User to "Pergunta antiga", SessionLineRole.Assistant to "Resposta antiga")
        entry = ChatEntry(open = true, sessionId = "S1")
        open()
        passUntil(4_000, "histórico") { has("Resposta antiga") }

        engine.appendOutside("S1", SessionLineRole.User, "Escrevi no terminal")
        engine.appendOutside("S1", SessionLineRole.Assistant, "Resposta dada no terminal")
        val spent = passUntil(4_000, "mensagem de fora") { has("Resposta dada no terminal") }

        assertTrue(spent <= 4_000)
        assertTrue(has("Escrevi no terminal"))
        assertEquals(1, rule.onAllNodesWithContentDescription("Você: Pergunta antiga").fetchSemanticsNodes().size)
    }

    @Test fun `voltar ao app com ON_RESUME traz as mensagens novas na hora`() {
        engine.seed("S1", SessionLineRole.User to "Oi", SessionLineRole.Assistant to "Olá")
        entry = ChatEntry(open = true, sessionId = "S1")
        // consulta espaçada de propósito: só o ON_RESUME pode trazer a mensagem rápido
        open(ChatTiming(idlePollMs = 600_000, idleBackoffMs = 600_000, busyPollMs = 600_000))
        passUntil(4_000, "histórico") { has("Olá") }

        engine.appendOutside("S1", SessionLineRole.Assistant, "Mensagem enquanto o app estava em segundo plano")
        pass(1_000)
        assertFalse("sem voltar ao app, nada chegou", has("Mensagem enquanto o app estava em segundo plano"))

        // É o que a tela faz no ON_RESUME do ciclo de vida
        hub.open(engine, LocalStore(tmp.root), entry, {}, { _, _ -> }, {}).onResume()
        passUntil(2_000, "mensagem após voltar") { has("Mensagem enquanto o app estava em segundo plano") }
    }

    @Test fun `A para B para A mostra sempre o historico completo e a leitura atrasada de B nao vaza`() {
        engine.seed("A", SessionLineRole.User to "pergunta A1", SessionLineRole.Assistant to "resposta A1", SessionLineRole.User to "pergunta A2", SessionLineRole.Assistant to "resposta A2")
        engine.seed("B", SessionLineRole.User to "pergunta B1", SessionLineRole.Assistant to "resposta B1")
        entry = ChatEntry(open = true, sessionId = "A")
        open()
        passUntil(4_000, "A") { has("resposta A2") }

        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        engine.pageGates["B"] = gate // a leitura de B demora
        entry = ChatEntry(open = true, sessionId = "B")
        pass(500)
        assertTrue("B ainda carregando", has("Carregando conversa…"))
        assertFalse("nada de A em cima de B", has("resposta A2"))

        entry = ChatEntry(open = true, sessionId = "A") // volta para A antes de B chegar
        pass(500)
        assertTrue("A inteira", has("pergunta A1") && has("resposta A1") && has("pergunta A2") && has("resposta A2"))

        gate.complete(Unit) // a leitura atrasada de B chega agora
        pass(1_000)
        assertFalse("B não vaza para dentro de A", has("resposta B1"))
        assertEquals("A não duplica", 1, rule.onAllNodesWithText("resposta A2").fetchSemanticsNodes().size)

        entry = ChatEntry(open = true, sessionId = "B")
        pass(500)
        assertTrue(has("pergunta B1") && has("resposta B1"))
        assertFalse(has("resposta A2"))
        entry = ChatEntry(open = true, sessionId = "A")
        pass(500)
        assertTrue(has("pergunta A1") && has("resposta A2"))
    }

    @Test fun `20 ciclos de sincronizacao com mensagens novas nao duplicam nada`() {
        engine.seed("S1", SessionLineRole.User to "primeira", SessionLineRole.Assistant to "resposta primeira")
        entry = ChatEntry(open = true, sessionId = "S1")
        open()
        passUntil(4_000, "histórico") { has("resposta primeira") }
        for (n in 1..20) {
            engine.appendOutside("S1", SessionLineRole.User, "msg $n")
            pass(3_100)
        }
        for (n in 1..20) {
            assertEquals("msg $n aparece uma vez", 1, rule.onAllNodesWithText("msg $n").fetchSemanticsNodes().size)
        }
        assertEquals(1, rule.onAllNodesWithContentDescription("Você: primeira").fetchSemanticsNodes().size)
    }

    @Test fun `sessao que roda no terminal mostra o Claude esta respondendo no terminal`() {
        engine.seed("S1", SessionLineRole.User to "pedi no terminal")
        engine.session("S1").external = true
        entry = ChatEntry(open = true, sessionId = "S1")
        open()
        passUntil(4_000, "aviso do terminal") { has("O Claude está respondendo no terminal…") }
        engine.session("S1").external = false
        passUntil(6_000, "aviso some") { !has("O Claude está respondendo no terminal…") }
    }

    @org.junit.Ignore("PENDENTE: a pílula não aparece no Robolectric (rolagem por performScrollTo); ver docs/PACOTE_P1_STATUS.md")
    @Test fun `lendo mensagens antigas aparece a pilula Mensagens novas e a posicao nao pula`() {
        val lines = (1..30).flatMap { n ->
            listOf(
                SessionLineRole.User to "pergunta $n " + "texto ".repeat(12),
                SessionLineRole.Assistant to "resposta $n " + "texto ".repeat(12),
            )
        }
        engine.seed("S1", *lines.toTypedArray())
        entry = ChatEntry(open = true, sessionId = "S1")
        open()
        passUntil(4_000, "histórico") { has("resposta 30", substring = true) }

        val first = "Você: pergunta 1 " + "texto ".repeat(12)
        rule.onNodeWithContentDescription(first).performScrollTo() // a pessoa subiu para ler o começo
        pass(300)
        rule.onNodeWithContentDescription(first).assertIsDisplayed()

        engine.appendOutside("S1", SessionLineRole.Assistant, "NOVIDADE VINDA DE FORA")
        passUntil(6_000, "pílula") { rule.onAllNodesWithContentDescription("Mensagens novas").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription(first).assertIsDisplayed() // a posição não pulou

        rule.onNodeWithContentDescription("Mensagens novas").performClick()
        passUntil(2_000, "foi para o fim") { runCatching { rule.onNodeWithText("NOVIDADE VINDA DE FORA").assertIsDisplayed() }.isSuccess }
        assertFalse("a pílula some", rule.onAllNodesWithContentDescription("Mensagens novas").fetchSemanticsNodes().isNotEmpty())
    }
}
