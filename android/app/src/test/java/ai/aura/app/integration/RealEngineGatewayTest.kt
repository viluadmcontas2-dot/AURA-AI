package ai.aura.app.integration

import ai.aura.app.offline.EngineUnreachableException
import ai.aura.app.offline.OfflineReason
import ai.aura.app.ui.engine.AuthStartResult
import ai.aura.app.ui.engine.AuthState
import ai.aura.app.ui.engine.EngineEvent
import ai.aura.app.ui.engine.ToolActionResult
import ai.aura.app.ui.engine.EngineHealth
import ai.aura.app.ui.engine.EngineResult
import ai.aura.app.ui.engine.EngineUsage
import ai.aura.app.ui.engine.JobRequest
import ai.aura.app.ui.engine.JobRisk
import ai.aura.app.ui.engine.JobStatus
import ai.aura.app.ui.engine.MemoryEntry
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Motor de mentira: cada chamada é registrada e respondida por [handler] (que também pode lançar o erro). */
private class FakeApi(val handler: (String, List<Any?>) -> JSONObject = { _, _ -> JSONObject() }) : EngineApi {
    val calls = mutableListOf<Pair<String, List<Any?>>>()
    private fun call(name: String, vararg args: Any?): JSONObject {
        calls += name to args.toList()
        return handler(name, args.toList())
    }

    override suspend fun health() = call("health")
    override suspend fun capabilities() = call("capabilities")
    override suspend fun jobs() = call("jobs")
    override suspend fun events(since: Long) = call("events", since)
    override suspend fun route(text: String, workspace: String) = call("route", text, workspace)
    override suspend fun submit(command: JSONObject, requestId: String) = call("submit", command, requestId)
    override suspend fun approve(id: String) = call("approve", id)
    override suspend fun cancel(id: String) = call("cancel", id)
    override suspend fun authStatus() = call("authStatus")
    override suspend fun startAuth(provider: String) = call("startAuth", provider)
    override suspend fun installTool(provider: String) = call("installTool", provider)
    override suspend fun authCode(provider: String, code: String) = call("authCode", provider, code)
    override suspend fun authToken(provider: String, token: String) = call("authToken", provider, token)
    override suspend fun policy() = call("policy")
    override suspend fun putPolicy(doc: JSONObject) = call("putPolicy", doc)
    override suspend fun models() = call("models")
    override suspend fun putModels(doc: JSONObject) = call("putModels", doc)
    override suspend fun spend() = call("spend")
    override suspend fun skills() = call("skills")
    override suspend fun usage() = call("usage")
    override suspend fun memory(workspace: String) = call("memory", workspace)
    override suspend fun forgetMemory(id: String) = call("forgetMemory", id)
    override suspend fun forgetAllMemory(workspace: String) = call("forgetAllMemory", workspace)
}

private fun job(id: String, status: String, risk: String) = JSONObject()
    .put("id", id).put("request_id", "r-$id").put("status", status).put("risk", risk).put("created_at", 1.5)
    .put("request", JSONObject().put("kind", "system").put("prompt", "oi"))

private fun jobs(vararg items: Any) = JSONObject().put("jobs", JSONArray(items.toList()))

private fun event(id: Long, type: String) = JSONObject().put("id", id).put("type", type)
    .put("payload", JSONObject().put("id", "job_$id")).put("created_at", 2.0)

/** O motor de verdade só devolve ids maiores que `since`, em ordem. */
private fun page(events: List<JSONObject>, since: Long) =
    JSONObject().put("events", JSONArray(events.filter { it.optLong("id") > since }))

class RealEngineGatewayTest {
    /** Assina os eventos do gateway e deixa o relógio virtual andar com [advance]. */
    private class Subscriber(scope: TestScope, gateway: RealEngineGateway) {
        val got = mutableListOf<EngineEvent>()

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                gateway.events.collect { got += it }
            }
            scope.runCurrent()
        }
    }

    @Test fun `status e risco desconhecidos viram Falhou e Critico`() = runTest {
        val api = FakeApi { _, _ -> jobs(job("job_1", "levitando", "read"), job("job_2", "queued", "nuclear")) }

        val list = RealEngineGateway(api, backgroundScope).jobs()

        assertEquals(JobStatus.Failed, list[0].status)
        assertEquals("unknown_status:levitando", list[0].errorCode)
        assertEquals(JobRisk.Critical, list[1].risk)
    }

    @Test fun `job malformado e descartado sem derrubar a lista`() = runTest {
        val api = FakeApi { _, _ ->
            jobs(job("job_1", "queued", "read"), "lixo", JSONObject().put("status", "queued"), job("job_2", "running", "write"))
        }

        val list = RealEngineGateway(api, backgroundScope).jobs()

        assertEquals(listOf("job_1", "job_2"), list.map { it.id })
    }

    @Test fun `evento ruim nao trava o polling e o cursor avanca`() = runTest {
        val server = mutableListOf<JSONObject>()
        val api = FakeApi { name, args -> if (name == "events") page(server, args[0] as Long) else JSONObject() }
        val sub = Subscriber(this, RealEngineGateway(api, backgroundScope))

        server += event(1, "job.created")
        server += JSONObject().put("id", 2) // sem type
        server += event(3, "job.completed")
        advanceTimeBy(EventBackoff.POLL_MS * 3)

        assertEquals(listOf(1L, 3L), sub.got.map { it.id })
        assertTrue((api.calls.last { it.first == "events" }.second[0] as Long) >= 2L)
    }

    @Test fun `motor que recomeca do zero refaz o cursor`() = runTest {
        val server = mutableListOf(event(1, "a"), event(2, "b"), event(3, "c"), event(4, "d"), event(5, "e"))
        val api = FakeApi { name, args -> if (name == "events") page(server, args[0] as Long) else JSONObject() }
        val sub = Subscriber(this, RealEngineGateway(api, backgroundScope))

        server.clear() // banco novo: os ids voltam a 1
        server += event(1, "job.created")
        server += event(2, "job.completed")
        advanceTimeBy(EventBackoff.POLL_MS * 3)
        server += event(3, "job.created")
        advanceTimeBy(EventBackoff.POLL_MS * 2)

        assertEquals(listOf(1L, 2L, 3L), sub.got.map { it.id })
    }

    @Test fun `queda e volta do motor viram connection lost e restored com ids negativos unicos`() = runTest {
        var down = false
        val api = FakeApi { name, _ ->
            if (name == "events" && down) throw EngineUnreachableException("fora", OfflineReason.EngineStopped)
            JSONObject().put("events", JSONArray())
        }
        val sub = Subscriber(this, RealEngineGateway(api, backgroundScope))

        down = true
        advanceTimeBy(EventBackoff.POLL_MS + 1)
        down = false
        advanceTimeBy(EventBackoff.delayMillis(1) + 1)
        down = true
        advanceTimeBy(EventBackoff.POLL_MS + 1)

        assertEquals(listOf("connection.lost", "connection.restored", "connection.lost"), sub.got.map { it.type })
        assertTrue(sub.got.all { it.id < 0 })
        assertEquals(3, sub.got.map { it.id }.toSet().size)
        assertEquals(OfflineReason.EngineStopped.name, sub.got[0].payload["reason"])
    }

    @Test fun `erros do motor chegam com a mensagem dele`() = runTest {
        val api = FakeApi { name, _ ->
            when (name) {
                "route" -> JSONObject().put("request", JSONObject().put("kind", "system"))
                "submit" -> throw AuraEngineException(409, "Esse pedido já foi enviado com outro conteúdo.", "conflict")
                else -> throw AuraEngineException(404, "job not found")
            }
        }
        val gateway = RealEngineGateway(api, backgroundScope)

        val conflict = runCatching { gateway.submit("r-1", JobRequest("system", "oi")) }.exceptionOrNull() as AuraEngineException
        val missing = runCatching { gateway.approve("job_9") }.exceptionOrNull() as AuraEngineException

        assertEquals(409 to "Esse pedido já foi enviado com outro conteúdo.", conflict.httpStatus to conflict.message)
        assertEquals(404 to "job not found", missing.httpStatus to missing.message)
    }

    @Test fun `comandos interpretados guardados ficam limitados a 200`() = runTest {
        val api = FakeApi { name, _ ->
            when (name) {
                "route" -> JSONObject().put("request", JSONObject().put("kind", "system").put("action", "pwd"))
                "submit" -> job("job_1", "queued", "read")
                else -> JSONObject()
            }
        }
        val gateway = RealEngineGateway(api, backgroundScope)
        repeat(250) { gateway.route("pedido $it") }
        val routed = api.calls.count { it.first == "route" }

        gateway.submit("r-249", JobRequest("system", "pedido 249")) // o mais novo está guardado
        assertEquals(routed, api.calls.count { it.first == "route" })
        gateway.submit("r-0", JobRequest("system", "pedido 0")) // o mais velho já saiu: interpreta de novo
        assertEquals(routed + 1, api.calls.count { it.first == "route" })
    }

    @Test fun `memoria skills saude e uso chegam prontos para a tela`() = runTest {
        val api = FakeApi { name, _ ->
            when (name) {
                "memory" -> JSONObject(
                    """{"entries":[{"id":"m1","kind":"rule","text":"Pedir antes de apagar","source":"quando eu disse","createdAt":1700000000},
                        {"kind":"rule","text":"sem id"}]}""",
                )
                "skills" -> JSONObject(
                    """{"skills":[{"id":"s1","name":"Revisar","description":"d","path":"/x","origin":"bundled"},
                        {"id":"s2","name":"Minha","description":"","path":"/y","origin":"user"}]}""",
                )
                "health" -> JSONObject("""{"service":"aura-engine","version":1}""")
                "usage" -> JSONObject(
                    """{"input_tokens":10,"output_tokens":20,"cache_read_tokens":5,"reported_jobs":2,"unreported_jobs":1}""",
                )
                else -> JSONObject()
            }
        }
        val gateway = RealEngineGateway(api, backgroundScope)

        assertEquals(
            EngineResult.Ok(listOf(MemoryEntry("m1", "rule", "Pedir antes de apagar", "quando eu disse", 1_700_000_000_000L))),
            gateway.memory(),
        )
        assertEquals(listOf(true, false), (gateway.skills() as EngineResult.Ok).value.map { it.bundled })
        assertEquals(EngineResult.Ok(EngineHealth("aura-engine", 1)), gateway.health())
        assertEquals(EngineResult.Ok(EngineUsage(10, 20, 5, 2, 1)), gateway.usage())
    }

    @Test fun `esquecer recusa id vazio aceita resposta sem corpo e so apaga o workspace pedido`() = runTest {
        val api = FakeApi { name, args ->
            when {
                name == "forgetAllMemory" -> JSONObject().put("deleted", 3)
                name == "forgetMemory" && args[0] == "sumiu" ->
                    throw AuraEngineException(404, "Lembrança não encontrada. Talvez já tenha sido esquecida.", "not_found")
                name == "memory" -> throw EngineUnreachableException("fora", OfflineReason.EngineStopped)
                else -> JSONObject() // 204: o cliente entrega um objeto vazio
            }
        }
        val gateway = RealEngineGateway(api, backgroundScope)

        assertTrue(gateway.forgetMemory("") is EngineResult.Rejected)
        assertTrue(gateway.forgetMemory("../x") is EngineResult.Rejected)
        assertTrue(api.calls.none { it.first == "forgetMemory" })
        assertEquals(EngineResult.Ok(Unit), gateway.forgetMemory("m1"))
        assertEquals(
            EngineResult.Rejected("not_found", "Lembrança não encontrada. Talvez já tenha sido esquecida."),
            gateway.forgetMemory("sumiu"),
        )
        assertEquals(EngineResult.Ok(3), gateway.forgetAllMemory())
        assertEquals(listOf<Any?>("default"), api.calls.last { it.first == "forgetAllMemory" }.second)
        assertTrue(gateway.memory() is EngineResult.Unavailable)
    }

    @Test fun `login - o Drive do motor aparece como gdrive e o estado vem do contrato`() = runTest {
        val api = FakeApi { name, _ ->
            if (name == "authStatus") JSONObject().put("providers", JSONArray(listOf(
                JSONObject().put("provider", "claude").put("state", "conectado"),
                JSONObject().put("provider", "google").put("state", "indisponivel").put("installable", true),
            ))) else JSONObject()
        }

        val list = RealEngineGateway(api, backgroundScope).authStatus()

        assertEquals(AuthState.Connected, list.first { it.provider == "claude" }.state)
        val drive = list.first { it.provider == "gdrive" }
        assertEquals(AuthState.Unavailable, drive.state)
        assertTrue(drive.installable)
    }

    @Test fun `login - start devolve o prompt ja emitido e trata programa ausente`() = runTest {
        val api = FakeApi { name, args ->
            when {
                name != "startAuth" -> JSONObject()
                args[0] == "codex" -> JSONObject().put("provider", "codex").put("state", "em_andamento")
                    .put("prompt", JSONObject().put("url", "https://example.com/device").put("code", "AB-12"))
                args[0] == "agy" -> JSONObject().put("provider", "agy").put("state", "indisponivel").put("fix", "Instalar")
                else -> JSONObject().put("provider", args[0]).put("state", "em_andamento").put("prompt", JSONObject.NULL)
            }
        }
        val gateway = RealEngineGateway(api, backgroundScope)

        assertEquals(AuthStartResult.Prompted("https://example.com/device", "AB-12"), gateway.startAuth("codex"))
        assertEquals(AuthStartResult.Started, gateway.startAuth("claude"))
        assertTrue(gateway.startAuth("agy") is AuthStartResult.Failed)
    }

    @Test fun `instalar usa o id do contrato e erro do Termux vira acao`() = runTest {
        val api = FakeApi { name, args ->
            when {
                name != "installTool" -> JSONObject()
                args[0] == "codex" -> throw AuraEngineException(409, "x", "indisponivel_no_termux")
                else -> JSONObject().put("state", "em_andamento").put("already_installed", false)
                    .put("download_notices", JSONArray(listOf("grande")))
            }
        }
        val gateway = RealEngineGateway(api, backgroundScope)

        val ok = gateway.installTool("github") as ToolActionResult.Ok
        assertTrue("100 MB" in ok.message)
        assertEquals(listOf<Any?>("gh"), api.calls.last { it.first == "installTool" }.second)
        gateway.installTool("gdrive")
        assertEquals(listOf<Any?>("rclone"), api.calls.last { it.first == "installTool" }.second)
        val no = gateway.installTool("codex") as ToolActionResult.Failed
        assertTrue("Termux" in no.reason)
    }

    @Test fun `token so conecta quando o motor confirma conectado`() = runTest {
        val api = FakeApi { name, _ -> if (name == "authToken") JSONObject().put("state", "conectado") else JSONObject() }
        val gateway = RealEngineGateway(api, backgroundScope)
        assertEquals(ToolActionResult.Ok("Conectado."), gateway.submitAuthToken("claude", " abc "))
        assertEquals(listOf<Any?>("claude", "abc"), api.calls.last { it.first == "authToken" }.second)
    }
}
