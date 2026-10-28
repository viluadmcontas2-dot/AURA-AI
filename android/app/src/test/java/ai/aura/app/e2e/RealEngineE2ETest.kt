package ai.aura.app.e2e

import ai.aura.app.integration.AuraEngineClient
import ai.aura.app.integration.AuraEngineException
import ai.aura.app.integration.RealEngineGateway
import ai.aura.app.ui.engine.AUTH_PROVIDER_IDS
import ai.aura.app.ui.engine.AuthState
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobStatus
import ai.aura.app.ui.engine.ModelsResult
import ai.aura.app.ui.engine.PolicyMode
import ai.aura.app.ui.engine.PolicyResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Prova de funcionamento real: sobe o motor Python de verdade (processo `python3 -m aura_engine`,
 * HOME temporário, porta livre) e usa o cliente e o gateway REAIS do app contra ele. Sem mocks.
 * Sem python3 (ex.: máquina local sem Python) os testes são pulados; na CI (ubuntu) rodam.
 */
class RealEngineE2ETest {
    private lateinit var home: File
    private var process: Process? = null
    private var port = 0
    private lateinit var token: String
    private lateinit var client: AuraEngineClient
    private lateinit var gateway: RealEngineGateway

    private fun engineDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "engine/aura_engine/__main__.py").exists()) return File(dir, "engine")
            dir = dir.parentFile
        }
        error("Pasta engine/ não encontrada a partir de " + File("").absolutePath)
    }

    private fun python(vararg args: String): ProcessBuilder =
        ProcessBuilder(listOf("python3") + args).directory(engineDir()).also {
            it.environment()["HOME"] = home.absolutePath
            it.environment()["PYTHONDONTWRITEBYTECODE"] = "1"
        }

    private fun hasPython(): Boolean = try {
        val p = ProcessBuilder("python3", "--version").redirectErrorStream(true).start()
        p.inputStream.readBytes()
        p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (_: IOException) {
        false
    }

    @Before
    fun startEngine() {
        assumeTrue("python3 ausente: teste do motor real pulado", hasPython())
        home = Files.createTempDirectory("aura-e2e-home").toFile()
        // Mesmo caminho do app: o motor emite o token em um único JSON (Termux RUN_COMMAND).
        val pair = python("-m", "aura_engine.pair", "--emit-token").start()
        val out = pair.inputStream.bufferedReader().readText()
        assertTrue(pair.waitFor(20, TimeUnit.SECONDS))
        token = JSONObject(out).getString("token")
        port = ServerSocket(0).use { it.localPort }
        process = python("-m", "aura_engine", "--port", port.toString())
            .redirectErrorStream(true).redirectOutput(File(home, "engine.log")).start()
        client = AuraEngineClient({ token }, "http://127.0.0.1:$port")
        gateway = RealEngineGateway(client)
        waitHealthy()
    }

    private fun waitHealthy() {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            try {
                runBlocking { client.health() }
                return
            } catch (_: IOException) {
                Thread.sleep(150)
            }
        }
        fail("Motor não subiu em 20s: " + File(home, "engine.log").readText().take(2000))
    }

    @After
    fun stopEngine() {
        process?.destroy()
        process?.waitFor(5, TimeUnit.SECONDS)
        process?.destroyForcibly()
        if (::home.isInitialized) home.deleteRecursively()
    }

    private suspend fun waitJob(id: String, until: (Job) -> Boolean): Job {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            gateway.jobs().firstOrNull { it.id == id }?.let { if (until(it)) return it }
            Thread.sleep(150)
        }
        fail("Job $id não chegou ao estado esperado")
        error("unreachable")
    }

    private fun echo(text: String) = JSONObject().put("kind", "shell")
        .put("argv", JSONArray(listOf("echo", text)))

    @Test
    fun health_autenticado_e_token_errado_vira_nao_autorizado() = runBlocking {
        assertEquals("aura-engine", client.health().getString("service"))

        val intruso = AuraEngineClient({ "x".repeat(64) }, "http://127.0.0.1:$port")
        try {
            intruso.health()
            fail("token errado deveria dar 401")
        } catch (e: AuraEngineException) {
            assertEquals(401, e.httpStatus)
            assertEquals("unauthorized", e.code)
        }
        // O app não quebra: a tela recebe "indisponível", nunca uma exceção.
        val bad = RealEngineGateway(intruso)
        assertTrue(bad.policy() is PolicyResult.Unavailable)
        assertTrue(bad.models() is ModelsResult.Unavailable)
    }

    @Test
    fun tarefa_shell_pede_aprovacao_e_roda_de_verdade() = runBlocking {
        val created = client.submit(echo("aura-e2e-ok"))
        val id = created.getString("id")
        // Política padrão (autônomo): shell é crítico e espera o dono aprovar.
        assertEquals("awaiting_approval", created.getString("status"))
        assertEquals(JobStatus.AwaitingApproval, gateway.jobs().first { it.id == id }.status)

        gateway.approve(id)
        val done = waitJob(id) { it.status.isTerminal }
        assertEquals(JobStatus.Completed, done.status)
        assertTrue("saída real do echo: ${done.result}", done.result.orEmpty().contains("aura-e2e-ok"))

        val types = client.events(0).getJSONArray("events").let { a ->
            List(a.length()) { a.getJSONObject(it).getString("type") }
        }
        assertTrue("eventos do job: $types", types.any { it.startsWith("job.") })
    }

    @Test
    fun rota_e_envio_pelo_gateway_de_leitura_automatica() = runBlocking {
        val route = gateway.route("listar arquivos")
        assertEquals("files", route.request.kind)
        val job = gateway.submit("e2e-" + System.nanoTime(), route.request)
        // Leitura é automática na política padrão: não pede aprovação.
        val done = waitJob(job.id) { it.status.isTerminal }
        assertEquals(JobStatus.Completed, done.status)
    }

    @Test
    fun politica_e_modelos_persistem_entre_chamadas() = runBlocking {
        val before = (gateway.policy() as PolicyResult.Ok).doc
        val changed = before.copy(
            mode = PolicyMode.Total,
            spendCap = before.spendCap.copy(perDay = 5.0, onReach = "pausar"),
        )
        val saved = gateway.savePolicy(changed)
        assertTrue("salvar: $saved", saved is PolicyResult.Ok)

        // Segunda leitura independente: o motor guardou de verdade.
        val again = (gateway.policy() as PolicyResult.Ok).doc
        assertEquals(PolicyMode.Total, again.mode)
        assertEquals(5.0, again.spendCap.perDay!!, 0.0)
        assertEquals("pausar", again.spendCap.onReach)
        assertTrue(again.version > before.version)
        assertTrue(File(home, ".aura/engine/policy.json").exists())

        // Efeito real: no modo total o shell roda sem pedir aprovação.
        val job = client.submit(echo("sem-pergunta"))
        assertNotEquals("awaiting_approval", job.getString("status"))

        // Valor fora do contrato é recusado e nada é gravado.
        val rejected = gateway.savePolicy(again.copy(notify = "sempre-sempre"))
        assertTrue("recusa: $rejected", rejected is PolicyResult.Rejected)
        assertEquals(again.version, (gateway.policy() as PolicyResult.Ok).doc.version)

        // Modelos: "perguntar antes" da API paga (contrato: never|ask) faz ida e volta.
        val models = (gateway.models() as ModelsResult.Ok).doc
        val ok = gateway.saveModels(models.copy(apiFallback = "ask"))
        assertTrue("modelos: $ok", ok is ModelsResult.Ok)
        assertEquals("ask", (gateway.models() as ModelsResult.Ok).doc.apiFallback)
    }

    @Test
    fun login_facil_lista_todas_as_contas_sem_quebrar() = runBlocking {
        val list = gateway.authStatus()
        assertEquals(AUTH_PROVIDER_IDS, list.map { it.provider })
        val raw = client.authStatus().getJSONArray("providers")
        // Cada linha do motor chega ao app com o mesmo estado (google do motor = gdrive do app).
        for (i in 0 until raw.length()) {
            val row = raw.getJSONObject(i)
            val id = row.getString("provider").let { if (it == "google") "gdrive" else it }
            val app = list.first { it.provider == id }
            assertEquals("estado de $id", AuthState.fromWire(row.getString("state")), app.state)
            // CLI ausente => indisponível (nunca "conectado" inventado).
            if (row.getString("state") == "indisponivel") assertEquals(AuthState.Unavailable, app.state)
        }
    }

    @Test
    fun motor_parado_da_erro_tratavel_e_o_app_continua() = runBlocking {
        process!!.destroy()
        process!!.waitFor(10, TimeUnit.SECONDS)
        try {
            client.health()
            fail("motor parado deveria falhar")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().isNotBlank())
        }
        // Telas locais seguem de pé: estados "indisponível" com mensagem, nenhuma exceção.
        val policy = gateway.policy()
        assertTrue(policy is PolicyResult.Unavailable)
        assertNotNull((policy as PolicyResult.Unavailable).reason)
        assertEquals(AUTH_PROVIDER_IDS, gateway.authStatus().map { it.provider })
        assertTrue(gateway.authStatus().all { it.state == AuthState.Unavailable })
    }
}
