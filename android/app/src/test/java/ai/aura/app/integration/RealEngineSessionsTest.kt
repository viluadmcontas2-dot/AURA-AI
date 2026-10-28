package ai.aura.app.integration

import ai.aura.app.ui.engine.ChatAction
import ai.aura.app.ui.engine.EngineResult
import ai.aura.app.ui.engine.ImproveResult
import ai.aura.app.ui.engine.SessionLineRole
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * O app (AuraEngineClient + RealEngineGateway REAIS) contra um servidor HTTP de verdade que fala o contrato de
 * "Sessões do Claude Code, processos e melhorar prompt" (docs/ENGINE_CONTRACT.md).
 */
class RealEngineSessionsTest {
    private lateinit var server: MiniHttpServer
    private lateinit var gateway: RealEngineGateway

    /** (método, caminho, corpo) de cada chamada, na ordem. */
    private val calls = CopyOnWriteArrayList<Triple<String, String, String>>()

    @Volatile private var improveStatus = 200
    @Volatile private var improveBody = """{"improved":"Pedido claro e completo."}"""
    @Volatile private var keyConfigured = false

    private fun reply(ex: MiniHttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    @Before
    fun start() {
        server = MiniHttpServer.create()
        server.createContext("/v1/sessions") { ex ->
            val path = ex.requestURI.path
            val body = String(ex.requestBody.readBytes(), Charsets.UTF_8)
            calls += Triple(ex.requestMethod, path, body)
            when {
                path == "/v1/sessions" -> reply(
                    ex, 200,
                    """{"sessions":[
                        {"id":"11111111-aaaa","title":"Arrumar o login","project":"meu/app","updated_at":1700000000.5,"messages":4,"source":"aura","running":true},
                        {"id":"22222222-bbbb","title":"Ideia no terminal","project":"","updated_at":1699990000,"messages":2,"source":"terminal","running":false},
                        {"title":"sem id","source":"aura"}
                    ]}""",
                )
                path.endsWith("/messages") -> reply(
                    ex, 200,
                    """{"messages":[
                        {"id":"m1","role":"user","text":"Oi","created_at":1.0},
                        {"id":"m2","role":"tool","text":"Lendo arquivos…","created_at":null},
                        {"id":"m3","role":"assistant","text":"Pronto."},
                        {"id":"m4","role":"system","text":"ignorar"},
                        {"id":"m5","role":"assistant","text":"  "}
                    ]}""",
                )
                path == "/v1/sessions/22222222-bbbb/rename" -> reply(ex, 200, """{"id":"22222222-bbbb","title":"Novo nome"}""")
                path == "/v1/sessions/22222222-bbbb/stop" -> reply(ex, 200, """{"id":"22222222-bbbb","stopped":true}""")
                path == "/v1/sessions/22222222-bbbb" && ex.requestMethod == "DELETE" -> reply(ex, 200, """{"id":"22222222-bbbb","deleted":true}""")
                else -> reply(ex, 404, """{"error":{"code":"not_found","message":"Não achei essa sessão."}}""")
            }
        }
        server.createContext("/v1/processes") { ex ->
            val path = ex.requestURI.path
            calls += Triple(ex.requestMethod, path, "")
            when {
                path == "/v1/processes" -> reply(
                    ex, 200,
                    """{"processes":[
                        {"pid":4242,"kind":"claude","session_id":"11111111-aaaa","started_at":1700000000,"cmd":"claude --resume"},
                        {"pid":1,"kind":"engine","started_at":null,"cmd":"python"}
                    ]}""",
                )
                path == "/v1/processes/4242/stop" -> reply(ex, 200, """{"pid":4242,"stopped":true}""")
                else -> reply(ex, 404, """{"error":{"code":"not_found","message":"Esse processo não é do motor."}}""")
            }
        }
        server.createContext("/v1/prompt/improve") { ex ->
            calls += Triple(ex.requestMethod, ex.requestURI.path, String(ex.requestBody.readBytes(), Charsets.UTF_8))
            reply(ex, improveStatus, improveBody)
        }
        server.createContext("/v1/settings/gemini_key") { ex ->
            val body = String(ex.requestBody.readBytes(), Charsets.UTF_8)
            calls += Triple(ex.requestMethod, ex.requestURI.path, body)
            when (ex.requestMethod) {
                "PUT" -> { keyConfigured = true; reply(ex, 200, """{"configured":true}""") }
                "DELETE" -> { keyConfigured = false; reply(ex, 200, """{"configured":false}""") }
                else -> reply(ex, 200, """{"configured":$keyConfigured}""")
            }
        }
        server.start()
        gateway = RealEngineGateway(AuraEngineClient({ "token-de-teste" }, "http://127.0.0.1:${server.address.port}"))
    }

    @After
    fun stop() = server.stop(0)

    @Test
    fun `lista sessoes com origem, pasta, tempo em milissegundos e quem esta respondendo`() = runBlocking {
        val result = gateway.sessions() as EngineResult.Ok
        val list = result.value
        assertEquals("o item sem id é descartado", listOf("11111111-aaaa", "22222222-bbbb"), list.map { it.id })
        val aura = list[0]
        assertEquals("Arrumar o login", aura.title)
        assertEquals("meu/app", aura.project)
        assertEquals(1_700_000_000_500L, aura.updatedAtMillis)
        assertEquals(4, aura.messages)
        assertFalse(aura.fromTerminal)
        assertTrue(aura.running)
        assertTrue(list[1].fromTerminal)
        assertFalse(list[1].running)
        assertEquals("GET", calls[0].first)
        assertEquals("/v1/sessions", calls[0].second)
    }

    @Test
    fun `mensagens da sessao trazem a linha de ferramenta e ignoram o que nao e conversa`() = runBlocking {
        val lines = (gateway.sessionMessages("11111111-aaaa") as EngineResult.Ok).value
        assertEquals(
            listOf(SessionLineRole.User to "Oi", SessionLineRole.Tool to "Lendo arquivos…", SessionLineRole.Assistant to "Pronto."),
            lines.map { it.role to it.text },
        )
        assertEquals("/v1/sessions/11111111-aaaa/messages", calls[0].second)
    }

    @Test
    fun `renomear, esconder e parar chamam as rotas certas`() = runBlocking {
        assertTrue(gateway.renameSession("22222222-bbbb", "Novo nome") is EngineResult.Ok)
        assertTrue(gateway.hideSession("22222222-bbbb") is EngineResult.Ok)
        assertTrue(gateway.stopSession("22222222-bbbb") is EngineResult.Ok)

        assertEquals("POST", calls[0].first)
        assertEquals("/v1/sessions/22222222-bbbb/rename", calls[0].second)
        assertTrue(calls[0].third.contains("\"title\":\"Novo nome\""))
        assertEquals("DELETE" to "/v1/sessions/22222222-bbbb", calls[1].first to calls[1].second)
        assertEquals("POST" to "/v1/sessions/22222222-bbbb/stop", calls[2].first to calls[2].second)
    }

    @Test
    fun `sessao desconhecida volta como recusa com a mensagem do motor`() = runBlocking {
        val r = gateway.hideSession("zzzzzzzz") as EngineResult.Rejected
        assertEquals("not_found", r.code)
        assertEquals("Não achei essa sessão.", r.message)
    }

    @Test
    fun `processos do motor e parar um deles`() = runBlocking {
        val list = (gateway.processes() as EngineResult.Ok).value
        assertEquals(listOf(4242L to "claude", 1L to "engine"), list.map { it.pid to it.kind })
        assertEquals("11111111-aaaa", list[0].sessionId)
        assertEquals(1_700_000_000_000L, list[0].startedAtMillis)
        assertNull(list[1].sessionId)
        assertNull(list[1].startedAtMillis)

        assertTrue(gateway.stopProcess(4242) is EngineResult.Ok)
        assertEquals("POST" to "/v1/processes/4242/stop", calls.last().first to calls.last().second)
    }

    @Test
    fun `sem motor as chamadas viram indisponivel e nunca excecao`() = runBlocking {
        server.stop(0)
        assertTrue(gateway.sessions() is EngineResult.Unavailable)
        assertTrue(gateway.processes() is EngineResult.Unavailable)
        assertTrue(gateway.stopSession("22222222-bbbb") is EngineResult.Unavailable)
        assertTrue(gateway.improvePrompt("texto") is ImproveResult.Failed)
    }

    @Test
    fun `melhorar pedido devolve o texto melhorado`() = runBlocking {
        val r = gateway.improvePrompt("faça um app") as ImproveResult.Improved
        assertEquals("Pedido claro e completo.", r.text)
        assertEquals("POST" to "/v1/prompt/improve", calls[0].first to calls[0].second)
        assertTrue(calls[0].third.contains("\"text\":\"faça um app\""))
    }

    @Test
    fun `sem chave do Gemini o app manda abrir Conexoes`() = runBlocking {
        improveStatus = 400
        improveBody = """{"error":{"code":"gemini_key_missing","message":"Falta a chave","action_pt":"Colar a chave do Gemini em Conexões"}}"""
        val r = gateway.improvePrompt("x") as ImproveResult.Failed
        assertEquals("gemini_key_missing", r.code)
        assertEquals("Cole a chave do Gemini em Conexões para melhorar pedidos.", r.message)
        assertEquals(ChatAction.OpenConnections, r.action)
    }

    @Test
    fun `limite e rede pedem tentar de novo`() = runBlocking {
        improveStatus = 503
        improveBody = """{"error":{"code":"rate_limited","message":"O Gemini pediu para esperar um pouco.","action_pt":"Tentar de novo"}}"""
        val r = gateway.improvePrompt("x") as ImproveResult.Failed
        assertEquals(ChatAction.Retry, r.action)
        assertEquals("O Gemini pediu para esperar um pouco.", r.message)
    }

    @Test
    fun `salvar a chave manda so o campo key, consulta diz apenas se existe e remover apaga`() = runBlocking {
        assertEquals(false, (gateway.geminiKeyConfigured() as EngineResult.Ok).value)
        assertTrue(gateway.saveGeminiKey("  AIza-segredo-123  ") is EngineResult.Ok)
        assertEquals(true, (gateway.geminiKeyConfigured() as EngineResult.Ok).value)
        assertTrue(gateway.removeGeminiKey() is EngineResult.Ok)
        assertEquals(false, (gateway.geminiKeyConfigured() as EngineResult.Ok).value)

        val put = calls.first { it.first == "PUT" }
        assertEquals("/v1/settings/gemini_key", put.second)
        assertEquals("AIza-segredo-123", org.json.JSONObject(put.third).getString("key"))
        assertEquals(listOf("GET", "PUT", "GET", "DELETE", "GET"), calls.map { it.first })
    }
}
