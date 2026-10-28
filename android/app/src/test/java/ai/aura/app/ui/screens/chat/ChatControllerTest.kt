package ai.aura.app.ui.screens.chat

import ai.aura.app.offline.LocalStore
import ai.aura.app.ui.engine.ChatAction
import ai.aura.app.ui.engine.ChatEvent
import ai.aura.app.ui.engine.ChatFailure
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A conversa sem tela: fila de mensagens guardadas, conversa que sumiu no motor e o que é comando "/". */
class ChatControllerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun stop() = scope.cancel()

    private fun waitUntil(what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!ok()) {
            check(System.currentTimeMillis() < end) { "não aconteceu: $what" }
            Thread.sleep(10)
        }
    }

    private fun controller(
        gw: ScriptedChatGateway,
        entry: ChatEntry = ChatEntry(open = true),
        store: LocalStore? = LocalStore(tmp.root),
        retryMillis: Long = 40,
    ): ChatController = ChatController(gw, scope, entry, store = store, retryMillis = retryMillis).also { Thread.sleep(200) }

    @Test fun `duas mensagens com o motor desligado ficam na fila e saem juntas, em ordem, quando ele volta`() {
        val gw = ScriptedChatGateway(down = true)
        val store = LocalStore(tmp.root)
        val c = controller(gw, store = store)

        assertTrue(c.send("Primeira"))
        waitUntil("aviso de motor desligado") { c.notice?.action == ChatAction.StartEngine }
        waitUntil("segunda aceita") { c.send("Segunda") }
        waitUntil("aviso de novo") { c.notice?.action == ChatAction.StartEngine }
        waitUntil("fila no disco") { store.loadPending() == listOf("Primeira", "Segunda") }

        gw.down = false
        waitUntil("saiu") { gw.started.isNotEmpty() }

        assertEquals(1, gw.started.size)
        assertEquals("Primeira\n\nSegunda", gw.started[0].first)
        assertEquals(listOf("Primeira", "Segunda"), c.messages.filter { it.fromUser }.map { it.text })
        waitUntil("fila vazia no disco") { store.loadPending().isEmpty() }
    }

    @Test fun `mensagem nova com o motor de volta leva as guardadas na frente e nenhuma se perde`() {
        val gw = ScriptedChatGateway(down = true)
        val store = LocalStore(tmp.root)
        val c = controller(gw, store = store, retryMillis = 600_000) // o reenvio sozinho só sai bem depois
        assertTrue(c.send("Guardada"))
        waitUntil("guardada no disco") { store.loadPending() == listOf("Guardada") }

        gw.down = false
        assertTrue(c.send("Nova"))
        waitUntil("saiu") { gw.started.isNotEmpty() }

        assertEquals("Guardada\n\nNova", gw.started[0].first)
        waitUntil("fila vazia no disco") { store.loadPending().isEmpty() }
    }

    @Test fun `conversa que o motor nao conhece mais recomeca sozinha, sem session_id, uma vez so`() {
        val gw = ScriptedChatGateway()
        gw.unknownSession = "sess_velha"
        val c = controller(gw, entry = ChatEntry(open = true, sessionId = "sess_velha"))

        assertTrue(c.send("Oi"))
        waitUntil("resposta") { c.messages.any { !it.fromUser } }

        assertEquals(listOf<String?>("sess_velha"), gw.rejected.toList())
        assertEquals(listOf("Oi" to null), gw.started.toList())
        assertNull(c.notice)
    }

    @Test fun `erro de --resume no fluxo, antes de qualquer texto, reenvia como conversa nova uma vez`() {
        val attempts = AtomicInteger(0)
        val gw = ScriptedChatGateway(script = {
            if (attempts.getAndIncrement() == 0) {
                listOf(Step.Emit(ChatEvent.Failed(ChatFailure("resume_failed", "Não achei a conversa.", ChatAction.Retry))))
            } else {
                listOf(Step.Emit(ChatEvent.Done("Agora foi.", "sess_nova")))
            }
        })
        val c = controller(gw, entry = ChatEntry(open = true, sessionId = "sess_velha"))

        assertTrue(c.send("Oi"))
        waitUntil("resposta") { c.messages.any { !it.fromUser } }

        assertEquals(listOf("Oi" to "sess_velha", "Oi" to null), gw.started.toList())
        assertEquals("Agora foi.", c.messages.last().text)
        assertNull(c.notice)
    }

    @Test fun `erro que nao e de conversa sumida nao apaga a conversa`() {
        val gw = ScriptedChatGateway(script = {
            listOf(Step.Emit(ChatEvent.Failed(ChatFailure("timeout", "Demorou demais.", ChatAction.Retry))))
        })
        val c = controller(gw, entry = ChatEntry(open = true, sessionId = "sess_1"))
        assertTrue(c.send("Oi"))
        waitUntil("aviso") { c.notice != null }
        assertEquals(1, gw.started.size)
        assertEquals("sess_1", gw.started[0].second)
    }

    @Test fun `parada do motor com texto ja recebido nao mostra aviso`() {
        val gw = ScriptedChatGateway(script = {
            listOf(
                Step.Emit(ChatEvent.Text("Resposta pela metade")),
                Step.Emit(ChatEvent.Failed(ChatFailure("stopped", "Resposta interrompida.", ChatAction.Retry))),
            )
        })
        val c = controller(gw)
        assertTrue(c.send("Oi"))
        waitUntil("texto guardado") { c.messages.any { !it.fromUser && it.text == "Resposta pela metade" } }
        assertNull(c.notice)
    }

    @Test fun `so e comando a barra com palavra conhecida ou palavra curta sem outra barra`() {
        assertTrue(ChatCommands.isCommandText("/projeto"))
        assertTrue(ChatCommands.isCommandText("/Projeto meu app"))
        assertTrue(ChatCommands.isCommandText("/conexoes"))
        assertTrue(ChatCommands.isCommandText("/xyz")) // desconhecido e curto: o app diz "Não conheço /xyz"
        assertFalse(ChatCommands.isCommandText("/data/data/com.termux/files/home"))
        assertFalse(ChatCommands.isCommandText("/ isso é uma frase que começa com barra"))
        assertFalse(ChatCommands.isCommandText("/palavra muito longa seguida de mais texto"))
        assertFalse(ChatCommands.isCommandText("/umapalavramuitolongaquepassadevinte"))
        assertFalse(ChatCommands.isCommandText("Olá /projeto"))
    }
}
