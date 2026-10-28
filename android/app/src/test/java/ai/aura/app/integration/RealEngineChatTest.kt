package ai.aura.app.integration

import ai.aura.app.ui.engine.ChatAction
import ai.aura.app.ui.engine.ChatEvent
import ai.aura.app.ui.engine.ChatUnsupportedException
import ai.aura.app.ui.engine.IntentResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * O app (AuraEngineClient + RealEngineGateway REAIS) contra um servidor HTTP de verdade que fala o
 * contrato de conversa: POST /v1/chat, GET /v1/chat/{id}, stop, chats, intent e os eventos chat.*.
 */
class RealEngineChatTest {
    private lateinit var server: MiniHttpServer
    private lateinit var gateway: RealEngineGateway

    /** Corpo de cada POST /v1/chat, na ordem. */
    private val chatBodies = CopyOnWriteArrayList<JSONObject>()
    private val stops = CopyOnWriteArrayList<String>()
    private val stateHits = AtomicInteger(0)
    @Volatile private var toolServedAt = 0L

    /** Como o chat_1 termina: "done" (com texto parcial antes) ou "error". */
    @Volatile private var ending = "done"
    @Volatile private var intentStatus = 404
    @Volatile private var intentBody = "{}"
    @Volatile private var chatStatus = 202

    private fun reply(ex: MiniHttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    @Before
    fun start() {
        server = MiniHttpServer.create()
        server.createContext("/v1/chat") { ex ->
            val path = ex.requestURI.path
            when {
                path == "/v1/chat" && ex.requestMethod == "POST" -> {
                    chatBodies += JSONObject(String(ex.requestBody.readBytes(), Charsets.UTF_8))
                    if (chatStatus != 202) reply(ex, chatStatus, """{"error":{"code":"not_found","message":"sem chat"}}""")
                    else reply(ex, 202, """{"chat_id":"chat_1","session_id":"sess_1"}""")
                }
                path == "/v1/chat/chat_1/stop" -> { stops += path; reply(ex, 200, """{"ok":true}""") }
                path == "/v1/chat/chat_1" -> {
                    val n = stateHits.incrementAndGet()
                    val toolDelivered = toolServedAt != 0L && System.currentTimeMillis() - toolServedAt > 1_600
                    val json = when {
                        ending == "error" && n >= 2 ->
                            """{"state":"error","text":"","error":{"code":"not_logged_in","message_pt":"O Claude ainda não está conectado.","action_pt":"Abrir Conexões"}}"""
                        n == 1 -> """{"state":"running","text":"Ol"}"""
                        ending == "done" && !toolDelivered -> """{"state":"running","text":"Olá, tudo"}"""
                        else -> """{"state":"done","text":"Olá, tudo bem?","session_id":"sess_1"}"""
                    }
                    reply(ex, 200, json)
                }
                else -> reply(ex, 404, """{"error":{"code":"not_found","message":"?"}}""")
            }
        }
        server.createContext("/v1/events") { ex ->
            // O evento de ferramenta só aparece depois de a leitura de estado já ter rodado várias vezes.
            val events = JSONArray()
            if (stateHits.get() >= 3) {
                if (toolServedAt == 0L) toolServedAt = System.currentTimeMillis()
                events.put(
                    JSONObject().put("id", 7).put("type", "chat.tool").put("created_at", 1.0)
                        .put("payload", JSONObject().put("chat_id", "chat_1").put("summary", "Lendo arquivos")),
                )
            }
            reply(ex, 200, JSONObject().put("events", events).toString())
        }
        server.createContext("/v1/chats") { ex ->
            if (ex.requestURI.path == "/v1/chats") {
                reply(ex, 200, """[{"id":"sess_1","title":"Teste","updated_at":20},{"id":"sess_0","title":"Velha","updated_at":10}]""")
            } else {
                reply(ex, 200, """{"messages":[{"role":"user","text":"Oi"},{"role":"assistant","text":"Olá!"},{"role":"assistant","text":" "}]}""")
            }
        }
        server.createContext("/v1/intent") { ex -> reply(ex, intentStatus, intentBody) }
        server.start()
        val client = AuraEngineClient({ "token-de-teste" }, "http://127.0.0.1:${server.address.port}")
        gateway = RealEngineGateway(client, chatPollMillis = 100L)
    }

    @After
    fun stop() = server.stop(0)

    @Test
    fun `texto cresce, ferramenta aparece no meio e termina com session_id`() = runBlocking {
        val started = gateway.startChat("Ola", null)
        assertEquals("chat_1", started.chatId)
        assertEquals("sess_1", started.sessionId)
        // primeira mensagem: sem session_id no corpo; o texto vai como a pessoa digitou
        assertEquals("Ola", chatBodies[0].getString("text"))
        assertTrue(!chatBodies[0].has("session_id"))

        val events = gateway.chatStream("chat_1").toList()

        val texts = events.filterIsInstance<ChatEvent.Text>().map { it.text }
        assertTrue("texto cresce: $texts", texts.size >= 2 && texts == texts.sortedBy { it.length })
        assertEquals("Ol", texts.first())
        val tool = events.indexOfFirst { it is ChatEvent.Tool }
        assertTrue("evento de ferramenta: $events", tool >= 0)
        assertEquals("Lendo arquivos", (events[tool] as ChatEvent.Tool).summary)
        val done = events.last() as ChatEvent.Done
        assertEquals("Olá, tudo bem?", done.text)
        assertEquals("sess_1", done.sessionId)
    }

    @Test
    fun `a mensagem seguinte manda o session_id`() = runBlocking {
        gateway.startChat("Primeira", null)
        gateway.startChat("Segunda", "sess_1")
        assertTrue(!chatBodies[0].has("session_id"))
        assertEquals("sess_1", chatBodies[1].getString("session_id"))
    }

    @Test
    fun `erro do motor vira falha em portugues com Abrir Conexoes`() = runBlocking {
        ending = "error"
        gateway.startChat("Oi", null)
        val last = gateway.chatStream("chat_1").toList().last() as ChatEvent.Failed
        assertEquals("not_logged_in", last.failure.code)
        assertEquals("O Claude ainda não está conectado.", last.failure.message)
        assertEquals(ChatAction.OpenConnections, last.failure.action)
    }

    @Test
    fun `parar chama o stop do chat`() = runBlocking {
        gateway.stopChat("chat_1")
        assertEquals(listOf("/v1/chat/chat_1/stop"), stops.toList())
    }

    @Test
    fun `motor sem chat vira ChatUnsupported e nao erro cru`() = runBlocking {
        chatStatus = 404
        try {
            gateway.startChat("Oi", null)
            fail("deveria dizer que o motor não conversa")
        } catch (e: ChatUnsupportedException) {
            // ok
        }
    }

    @Test
    fun `lista de conversas e mensagens guardadas`() = runBlocking {
        assertEquals(listOf("sess_1", "sess_0"), gateway.chats().map { it.id })
        val lines = gateway.chatMessages("sess_1")
        assertEquals(listOf(true to "Oi", false to "Olá!"), lines.map { it.fromUser to it.text })
    }

    @Test
    fun `intent 404 vale como conversa e intent com pergunta vira duas opcoes`() = runBlocking {
        assertEquals(IntentResult.Chat, gateway.intent("Teste"))
        intentStatus = 200
        intentBody = """{"kind":"project","idea":"controle de gastos"}"""
        assertEquals(IntentResult.Project("controle de gastos"), gateway.intent("quero um app"))
        intentBody = """{"kind":"chat","question":"Quer conversar ou reformar?","options":[{"label":"Conversar","kind":"chat"},{"label":"Reformar app","kind":"reform"}]}"""
        val ask = gateway.intent("app") as IntentResult.Ask
        assertEquals(listOf("chat", "reform"), ask.options.map { it.kind })
    }
}
