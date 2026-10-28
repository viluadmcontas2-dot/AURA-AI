package ai.aura.app.ui.screens

import ai.aura.app.offline.LinkState
import ai.aura.app.offline.LocalStore
import ai.aura.app.offline.OfflineReason
import ai.aura.app.ui.engine.Capability
import ai.aura.app.ui.engine.EngineEvent
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobRequest
import ai.aura.app.ui.engine.JobRisk
import ai.aura.app.ui.engine.JobStatus
import ai.aura.app.ui.engine.ModelsDoc
import ai.aura.app.ui.engine.ModelsResult
import ai.aura.app.ui.engine.PolicyDoc
import ai.aura.app.ui.engine.PolicyResult
import ai.aura.app.ui.engine.RouteResult
import ai.aura.app.ui.screens.activity.activityBadgeCount
import ai.aura.app.ui.screens.chat.ChatController
import ai.aura.app.ui.screens.chat.ChatEntry
import ai.aura.app.ui.screens.chat.ScriptedChatGateway
import ai.aura.app.ui.screens.devices.DevicesTab
import ai.aura.app.ui.screens.projects.Project
import ai.aura.app.ui.screens.projects.ProjectsHost
import ai.aura.app.ui.screens.projects.loadProjects
import ai.aura.app.ui.screens.projects.saveProjects
import ai.aura.app.ui.screens.settings.ENGINE_DOWN_HINT
import ai.aura.app.ui.screens.settings.SettingsScreen
import ai.aura.app.ui.theme.AuraTheme
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** Motor de mentira: `down` = IOException em tudo, como o cliente HTTP real sem o Termux. */
private class Gw(
    val down: Boolean = false,
    val jobs: List<Job> = emptyList(),
    val caps: List<Capability> = emptyList(),
) : EngineGateway {
    override val events: Flow<EngineEvent> = emptyFlow()
    private fun up() { if (down) throw IOException("Termux não responde") }
    override suspend fun jobs(): List<Job> { up(); return jobs }
    override suspend fun approve(id: String): Job { up(); return jobs.first { it.id == id } }
    override suspend fun cancel(id: String): Job { up(); return jobs.first { it.id == id } }
    override suspend fun route(text: String): RouteResult { up(); return RouteResult(JobRequest("system", text), "ok", 1.0, false, JobRisk.Read) }
    override suspend fun submit(requestId: String, request: JobRequest): Job {
        up()
        return Job("job_novo", requestId, request.prompt, request.kind, JobStatus.Queued, JobRisk.Read, 1L)
    }
    override suspend fun capabilities(): List<Capability> { up(); return caps }
    override suspend fun policy(): PolicyResult { up(); return PolicyResult.Ok(PolicyDoc(1)) }
    override suspend fun models(): ModelsResult { up(); return ModelsResult.Ok(ModelsDoc(1)) }
}

private fun job(id: String, title: String, status: JobStatus) = Job(id, "req_$id", title, "claude_cli", status, JobRisk.Write, 1_000L)

private val JOBS = listOf(job("job_1", "Corrigir login", JobStatus.Running), job("job_2", "Instalar no aparelho", JobStatus.AwaitingApproval))
private val CAPS = listOf(Capability("termux", "Termux", true, "Termux ativo"), Capability("adb", "Multimídia do carro", true, "ADB sem fio"))
private val PROJETO = Project(
    "p1", "Meu app", "controlar a TV", "android", listOf("Início"), listOf("abre"), listOf("APK"),
    "meu-app", jobIds = listOf("job_1"), archived = true,
)

private fun waitFor(timeoutMs: Long = 5_000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!cond()) {
        check(System.currentTimeMillis() < end) { "tempo esgotado esperando o disco" }
        Thread.sleep(20)
    }
}

/** FN-12: matar e reabrir o app. "Reabrir" = objetos novos sobre a MESMA pasta. */
class PersistenciaTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `projetos voltam ao reabrir e arquivo com lixo nao derruba`() {
        LocalStore(tmp.root).saveProjects(listOf(PROJETO))
        assertEquals(listOf(PROJETO), LocalStore(tmp.root).loadProjects())

        // campo de tipo errado, item sem id e item que nem é objeto: o resto continua valendo
        LocalStore(tmp.root).write(
            "projects",
            JSONObject("""{"items":[{"id":"a","name":5,"screens":"x"},{"name":"sem id"},"lixo",{"id":"b","jobIds":[1,"j"]}]}"""),
        )
        assertEquals(listOf("a", "b"), LocalStore(tmp.root).loadProjects().map { it.id })

        File(tmp.root, "projects.json").writeText("{quebrado")
        assertTrue(LocalStore(tmp.root).loadProjects().isEmpty())

        // Bundle antigo ou torto não pode lançar ao restaurar o chat aberto
        assertNull(ChatEntry.Saver.restore(listOf("1")))
        assertEquals(ChatEntry(true, "a", "b", "c", "d"), ChatEntry.Saver.restore(listOf("1", "a", "b", "c", "d")))
        assertFalse(ChatEntry.Saver.restore(listOf(1, null, 3.0, true, "x"))!!.open)
    }

    @Test fun `historico e conversa do chat voltam do disco mesmo sem motor`() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val first = ChatController(ScriptedChatGateway(), scope, ChatEntry(open = true), store = LocalStore(tmp.root))
            first.send("Corrigir o login")
            waitFor { first.messages.any { !it.fromUser && it.text == "Olá!" } }
            waitFor { LocalStore(tmp.root).read("chat-session")?.optString("session_id") == "sess_1" }
            // Um arquivo POR conversa (chat-h-<id>); nada de histórico global.
            waitFor { (LocalStore(tmp.root).read("chat-h-sess_1")?.optJSONArray("items")?.length() ?: 0) == 2 }

            // Reabrir com o motor desligado: a conversa aparece do disco, sem erro e sem spinner.
            val second = ChatController(ScriptedChatGateway(down = true), scope, ChatEntry(open = true), store = LocalStore(tmp.root))
            waitFor { second.messages.size == 2 }
            assertEquals(listOf("Corrigir o login", "Olá!"), second.messages.map { it.text })
            assertNull(second.notice)
            assertFalse(second.responding)
        } finally {
            scope.cancel()
        }
    }
}

enum class Estado { Online, Offline, Vazio }

/** Regra nº 1: cada aba nos 3 estados mostra algo útil e uma ação, nunca tela em branco. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp") // tela de celular: a lista preguicosa so compoe o que cabe
class AbasTest(private val estado: Estado) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun estados(): List<Array<Any>> = Estado.entries.map { arrayOf<Any>(it) }
    }

    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private fun gateway() = when (estado) {
        Estado.Online -> Gw(jobs = JOBS, caps = CAPS)
        Estado.Offline -> Gw(down = true)
        Estado.Vazio -> Gw()
    }

    private fun ComposeContentTestRule.temTexto(t: String) = onAllNodesWithText(t).fetchSemanticsNodes().isNotEmpty()
    private fun ComposeContentTestRule.espera(t: String) = waitUntil(5_000) { temTexto(t) }
    private fun ComposeContentTestRule.semCarregando() =
        assertTrue(onAllNodesWithText("Carregando", substring = true).fetchSemanticsNodes().isEmpty())

    @Test fun projetos() {
        val store = LocalStore(tmp.root)
        if (estado != Estado.Vazio) store.saveProjects(listOf(PROJETO))
        val gw = gateway()
        rule.setContent { AuraTheme { ProjectsHost(gw, store = store) } }
        rule.espera(if (estado == Estado.Vazio) "Criar projeto" else "Meu app")
        if (estado == Estado.Offline) rule.espera("Tentar de novo")
        rule.semCarregando()
    }

    @Test fun atividade() {
        val gw = gateway()
        rule.setContent { AuraTheme { ActivityScreen(gw) } }
        rule.espera(
            when (estado) {
                Estado.Online -> "Corrigir login"
                Estado.Offline -> "Tentar de novo"
                Estado.Vazio -> "Pedir algo ao AURA"
            },
        )
        if (estado == Estado.Online) assertEquals(1, activityBadgeCount(JOBS))
        rule.semCarregando()
    }

    @Test fun dispositivos() {
        val gw = gateway()
        rule.setContent { AuraTheme { DevicesTab(gw) } }
        rule.espera("Este celular")
        rule.espera(
            when (estado) {
                Estado.Online -> "Multimídia do carro"
                Estado.Offline -> "Reconectar"
                Estado.Vazio -> "Adicionar dispositivo"
            },
        )
        rule.semCarregando()
    }

    @Test fun inicio() {
        LocalStore(tmp.root).write("drafts", JSONObject().put("home", "rascunho salvo"))
        var acao: OfflineReason? = null
        val link = if (estado == Estado.Offline) LinkState.Offline(OfflineReason.NoTermux) else LinkState.Online
        val gw = gateway()
        rule.setContent {
            AuraTheme { HomeScreen(gw, store = LocalStore(tmp.root), link = link, onEngineAction = { acao = it }) }
        }
        rule.espera("O que vamos criar hoje?")
        rule.espera("rascunho salvo") // o rascunho volta do disco
        if (estado == Estado.Offline) {
            rule.espera("Instalar o Termux") // UMA ação, a do motivo
            rule.onNodeWithText("Instalar o Termux").performClick()
            assertEquals(OfflineReason.NoTermux, acao)
        }
    }

    @Test fun ajustes() {
        val gw = gateway()
        rule.setContent { AuraTheme { SettingsScreen(gw, onClose = {}) } }
        if (estado == Estado.Offline) {
            rule.espera("Motor indisponível")
            rule.onNodeWithText("Parar tudo").performClick()
            // o aviso do botão aparece (antes só existia na barra de salvar, que some sem motor)
            rule.waitUntil(5_000) { rule.onAllNodesWithText(ENGINE_DOWN_HINT).fetchSemanticsNodes().size == 2 }
        } else {
            rule.espera("Salvar alterações")
        }
    }
}
