package ai.aura.app.ui.screens.chat

import ai.aura.app.offline.LocalStore
import ai.aura.app.ui.engine.ChatAction
import ai.aura.app.ui.engine.ChatEvent
import ai.aura.app.ui.engine.ChatFailure
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A conversa sem tela: fila de mensagens, conversa que sumiu no motor e o que é comando "/". */
class ChatControllerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val thread = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + thread.asCoroutineDispatcher())

    @After fun stop() {
        scope.cancel()
        thread.shutdownNow()
    }

    private var last: ChatController? = null

    private fun waitUntil(what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!ok()) {
            check(System.currentTimeMillis() < end) { "não aconteceu: $what\n" + last?.diagnosticReport() }
            Thread.sleep(10)
        }
    }

    private fun controller(
        gw: ScriptedChatGateway,
        entry: ChatEntry = ChatEntry(open = true),
        store: LocalStore? = LocalStore(tmp.root),
        retryMillis: Long = 40,
    ): ChatController = ChatController(gw, scope, entry, store = store, retryMillis = retryMillis).also { last = it; Thread.sleep(200) }

    /** Um roteiro por resposta, na ordem: a 1ª mensagem usa o 1º, a 2ª o 2º... (depois disso, "ok"). */
    private fun perCall(vararg steps: List<Step>): ScriptedChatGateway {
        val calls = AtomicInteger(0)
        return ScriptedChatGateway(script = {
            steps.getOrElse(calls.getAndIncrement()) { listOf(Step.Emit(ChatEvent.Done("ok", "sess_1"))) }
        })
    }

    /** O que a fila tem no disco da conversa nova ("novo") ou de [key]: "estado:texto" de cada mensagem. */
    private fun queuedOnDisk(key: String = "novo"): List<String> {
        val items = LocalStore(tmp.root).read("chat-q-$key")?.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).map { items.getJSONObject(it).let { o -> o.getString("state") + ":" + o.getString("text") } }
    }

    // ---- motor desligado: a mesma fila ----

    @Test fun `duas mensagens com o motor desligado ficam guardadas e saem uma por vez, em ordem, quando ele volta`() {
        val gw = ScriptedChatGateway(down = true)
        val c = controller(gw)

        assertTrue(c.send("Primeira"))
        waitUntil("aviso de motor desligado") { c.notice?.action == ChatAction.StartEngine }
        assertTrue(c.send("Segunda"))
        waitUntil("fila no disco") { queuedOnDisk() == listOf("waiting_engine:Primeira", "queued:Segunda") }
        assertEquals(listOf("Primeira", "Segunda"), c.queue.map { it.text })
        assertTrue("a primeira espera o motor", c.queue[0].waitingEngine)

        gw.down = false
        waitUntil("as duas saíram") { gw.started.size == 2 }

        // Uma por vez, na ordem; a segunda já na conversa que a primeira abriu.
        assertEquals(listOf("Primeira" to null, "Segunda" to "sess_1"), gw.started.toList())
        assertEquals(listOf("Primeira", "Olá!", "Segunda", "Olá!"), c.messages.map { it.text })
        waitUntil("fila vazia no disco") { queuedOnDisk("sess_1").isEmpty() && queuedOnDisk().isEmpty() }
    }

    @Test fun `mensagem nova com o motor de volta entra atras das guardadas e nenhuma se perde`() {
        val gw = ScriptedChatGateway(down = true)
        val c = controller(gw, retryMillis = 600_000) // o reenvio sozinho só sai bem depois
        assertTrue(c.send("Guardada"))
        waitUntil("guardada no disco") { queuedOnDisk() == listOf("waiting_engine:Guardada") }

        gw.down = false
        assertTrue(c.send("Nova"))
        waitUntil("as duas saíram") { gw.started.size == 2 }

        assertEquals(listOf("Guardada", "Nova"), gw.started.map { it.first })
    }

    // ---- conversa que sumiu: nunca recomeça escondido ----

    @Test fun `conversa que o motor nao conhece mais nao recomeca sozinha, avisa e deixa a pessoa escolher`() {
        val gw = ScriptedChatGateway()
        gw.unknownSession = "sess_velha"
        val c = controller(gw, entry = ChatEntry(open = true, sessionId = "sess_velha"))

        assertTrue(c.send("Oi"))
        waitUntil("aviso") { c.notice != null }

        assertEquals("Não encontrei esta conversa no Termux.", c.notice!!.message)
        assertEquals(listOf("Voltar às sessões", "Criar outra conversa"), c.notice!!.buttons.map { it.label })
        assertEquals(listOf<String?>("sess_velha"), gw.rejected.toList())
        assertTrue("nada foi enviado como conversa nova", gw.started.isEmpty())

        c.press(NoticeKind.NewConversation)
        waitUntil("mandou na conversa nova") { gw.started.isNotEmpty() }
        assertEquals(listOf("Oi" to null), gw.started.toList())
        waitUntil("ganhou a identidade nova") { c.currentSessionId == "sess_1" }
    }

    @Test fun `resume_failed mostra Tentar retomar e Criar outra conversa, e retomar manda de novo na MESMA sessao`() {
        val attempts = AtomicInteger(0)
        val gw = ScriptedChatGateway(script = {
            if (attempts.getAndIncrement() == 0) {
                listOf(Step.Emit(ChatEvent.Failed(ChatFailure("resume_failed", "Não achei a conversa.", ChatAction.Retry))))
            } else {
                listOf(Step.Emit(ChatEvent.Done("Agora foi.", "sess_velha")))
            }
        })
        val c = controller(gw, entry = ChatEntry(open = true, sessionId = "sess_velha"))

        assertTrue(c.send("Oi"))
        waitUntil("aviso") { c.notice != null }
        assertEquals(listOf("Tentar retomar", "Criar outra conversa"), c.notice!!.buttons.map { it.label })
        assertTrue(c.notice!!.message.contains("O histórico foi preservado"))
        assertEquals("só a tentativa de retomar, nada de conversa nova escondida", listOf("Oi" to "sess_velha"), gw.started.toList())

        c.press(NoticeKind.Retry)
        waitUntil("resposta") { c.messages.any { !it.fromUser && it.text == "Agora foi." } }
        assertEquals(listOf("Oi" to "sess_velha", "Oi" to "sess_velha"), gw.started.toList())
        assertEquals("cada tentativa é um pedido novo, com chave nova", 2, gw.requests.map { it.first }.toSet().size)
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
        assertEquals("sess_1", c.currentSessionId)
    }

    @Test fun `parada do motor com texto ja recebido nao mostra aviso e guarda o texto`() {
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
        assertEquals("Resposta interrompida. O texto recebido foi guardado.", c.info)
    }

    // ---- fila de mensagens (o Claude está respondendo) ----

    @Test fun `enviar durante a resposta enfileira, nao chama o motor ate terminar e sai em ordem`() {
        val gate = CompletableDeferred<Unit>()
        val gw = perCall(listOf(Step.Wait(gate), Step.Emit(ChatEvent.Done("Resposta A", "sess_1"))))
        val c = controller(gw)

        assertTrue(c.send("A"))
        waitUntil("A saiu") { gw.started.size == 1 }
        assertTrue(c.responding)

        assertTrue("Enviar nunca bloqueia", c.send("B"))
        assertTrue(c.send("C"))
        waitUntil("na fila") { c.queue.map { it.text } == listOf("B", "C") }
        Thread.sleep(150)
        assertEquals("nenhum POST enquanto a resposta não termina", 1, gw.started.size)
        waitUntil("fila no disco") { queuedOnDisk("sess_1").containsAll(listOf("queued:B", "queued:C")) }

        gate.complete(Unit)
        waitUntil("todas saíram") { gw.started.size == 3 }
        assertEquals(listOf("A" to null, "B" to "sess_1", "C" to "sess_1"), gw.started.toList())
        waitUntil("respondeu tudo") { c.messages.count { !it.fromUser } == 3 }
        assertEquals(listOf("A", "Resposta A", "B", "ok", "C", "ok"), c.messages.map { it.text })
        assertTrue(c.queue.isEmpty())
    }

    @Test fun `Enviar agora para a resposta atual, guarda o que veio e manda a escolhida em seguida`() {
        val gw = perCall(
            listOf(Step.Emit(ChatEvent.Text("pela metade")), Step.Wait()),
            listOf(Step.Emit(ChatEvent.Done("Resposta B", "sess_1"))),
        )
        val c = controller(gw)
        c.send("A")
        waitUntil("A respondendo") { c.live?.text == "pela metade" }
        c.send("B")
        c.send("C")
        waitUntil("na fila") { c.queue.size == 2 }

        c.sendNow(c.queue[1].requestId) // "C" passa na frente

        waitUntil("parou a atual") { gw.stopped.isNotEmpty() }
        waitUntil("C saiu") { gw.started.size >= 2 }
        assertEquals(listOf("A" to null, "C" to "sess_1"), gw.started.take(2))
        assertTrue("o que já tinha vindo ficou", c.messages.any { !it.fromUser && it.text == "pela metade" })
        waitUntil("C respondida") { c.messages.any { it.text == "Resposta B" } }
        // B continua na fila (ou saiu depois dela): nada se perde
        waitUntil("B também sai") { gw.started.size == 3 }
        assertEquals("B", gw.started[2].first)
    }

    @Test fun `Parar pausa a fila, Continuar manda a proxima e Limpar fila descarta`() {
        val gw = perCall(
            listOf(Step.Emit(ChatEvent.Text("texto")), Step.Wait()),
            listOf(Step.Emit(ChatEvent.Done("Resposta B", "sess_1"))),
        )
        val c = controller(gw)
        c.send("A")
        waitUntil("A respondendo") { c.live != null }
        c.send("B")
        c.send("C")
        waitUntil("na fila") { c.queue.size == 2 }

        c.stop()
        waitUntil("parou") { gw.stopped.isNotEmpty() && c.live == null }
        waitUntil("fila parada") { c.queuePaused }
        Thread.sleep(150)
        assertEquals("a fila não anda sozinha depois de Parar", 1, gw.started.size)
        assertEquals(listOf("B", "C"), c.queue.map { it.text })

        c.resumeQueue()
        waitUntil("B saiu") { gw.started.size >= 2 }
        assertEquals("B", gw.started[1].first)
        waitUntil("C também") { gw.started.size == 3 }

        // outra rodada: Parar e Limpar fila
        val gw2 = perCall(listOf(Step.Wait()))
        val c2 = ChatController(gw2, scope, ChatEntry(open = true), store = null, retryMillis = 40)
        Thread.sleep(150)
        c2.send("X")
        waitUntil("X respondendo") { c2.live != null }
        c2.send("Y")
        waitUntil("Y na fila") { c2.queue.size == 1 }
        c2.stop()
        waitUntil("fila parada") { c2.queuePaused }
        c2.clearQueue()
        assertTrue(c2.queue.isEmpty())
        assertFalse(c2.queuePaused)
        Thread.sleep(150)
        assertEquals(1, gw2.started.size)
    }

    @Test fun `cancelar tira da fila uma mensagem que ainda nao saiu`() {
        val gw = perCall(listOf(Step.Wait()))
        val c = controller(gw)
        c.send("A")
        waitUntil("A saiu") { gw.started.size == 1 }
        c.send("B")
        waitUntil("B na fila") { c.queue.size == 1 }
        c.cancelQueued(c.queue[0].requestId)
        assertTrue(c.queue.isEmpty())
        waitUntil("some do disco") { queuedOnDisk("sess_1").none { it.endsWith(":B") } }
        assertEquals(1, gw.started.size)
    }

    @Test fun `a fila sobrevive a fechar e reabrir o app`() {
        val gw = perCall(listOf(Step.Wait()))
        val first = controller(gw)
        first.send("A")
        waitUntil("A respondendo") { first.live != null }
        first.send("B")
        waitUntil("B no disco") { queuedOnDisk("sess_1").contains("queued:B") }

        // O app fechou: um controlador novo, no mesmo disco, com o motor ainda desligado.
        val gw2 = ScriptedChatGateway(down = true)
        val reopened = controller(gw2, entry = ChatEntry(open = true, sessionId = "sess_1"))
        waitUntil("fila de volta") { reopened.queue.map { it.text }.contains("B") }
        assertTrue("o que estava enviando é incerto e volta a aguardar o motor", reopened.queue.any { it.text == "A" && it.waitingEngine } ||
            reopened.messages.any { it.text == "A" })
    }

    @Test fun `cada mensagem leva a propria chave, o reenvio guardado leva a mesma e o assistente pedido vai junto`() {
        val gw = ScriptedChatGateway(down = true)
        val c = controller(gw, retryMillis = 40)
        c.send("Oi", executor = "gemini")
        waitUntil("aviso") { c.notice?.action == ChatAction.StartEngine }
        Thread.sleep(150) // tentou de novo algumas vezes sem motor
        gw.down = false
        waitUntil("saiu") { gw.requests.isNotEmpty() }
        assertEquals("gemini", gw.requests[0].second)
        val key = gw.requests[0].first
        assertTrue(!key.isNullOrBlank())
        c.send("Outra")
        waitUntil("segunda saiu") { gw.requests.size == 2 }
        assertTrue("chave nova por mensagem", gw.requests[1].first != key)
        assertEquals(null, gw.requests[1].second)
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
