package ai.aura.app.ui

import ai.aura.app.integration.ApkSharer
import ai.aura.app.integration.FakeReformGateway
import ai.aura.app.integration.sharedApkName
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
import ai.aura.app.ui.screens.ProjectsScreen
import ai.aura.app.ui.screens.reform.LocalReformController
import ai.aura.app.ui.screens.reform.MemoryReformIdStore
import ai.aura.app.ui.screens.reform.ReformApp
import ai.aura.app.ui.screens.reform.ReformController
import ai.aura.app.ui.screens.reform.sharedApkFrom
import ai.aura.app.ui.theme.AuraTheme
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** Motor de mentira: `down` = IOException em tudo, como o cliente HTTP real sem o Termux. */
private class MotorFalso(val down: Boolean = false) : EngineGateway {
    override val events: Flow<EngineEvent> = emptyFlow()
    private fun up() { if (down) throw IOException("Termux não responde") }
    override suspend fun jobs(): List<Job> { up(); return emptyList() }
    override suspend fun approve(id: String): Job { up(); error("sem jobs") }
    override suspend fun cancel(id: String): Job { up(); error("sem jobs") }
    override suspend fun route(text: String): RouteResult { up(); return RouteResult(JobRequest("system", text), "ok", 1.0, false, JobRisk.Read) }
    override suspend fun submit(requestId: String, request: JobRequest): Job {
        up()
        return Job("job_novo", requestId, request.prompt, request.kind, JobStatus.Queued, JobRisk.Read, 1L)
    }
    override suspend fun capabilities(): List<Capability> { up(); return listOf(Capability("termux", "Termux", true, "Termux ativo")) }
    override suspend fun policy(): PolicyResult { up(); return PolicyResult.Ok(PolicyDoc(1)) }
    override suspend fun models(): ModelsResult { up(); return ModelsResult.Ok(ModelsDoc(1)) }
}

/** Seletor de arquivos de mentira: `terminar` entrega o APK escolhido como o Android entregaria. */
private class SeletorFalso : ActivityResultRegistry() {
    private var codigo: Int? = null
    var abriu = 0

    override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
        abriu++
        codigo = requestCode
    }

    fun terminar(uri: Uri) = dispatchResult(checkNotNull(codigo), Activity.RESULT_OK, Intent().setData(uri))
}

/**
 * Descoberta da Reforma de apps, clicando de verdade (Robolectric na JVM, sem emulador):
 * (a) do Início, um toque em "Reformar apps" chega em "Escolher APK";
 * (b) escolher APK -> Escopo -> Reversão -> Projeto -> Ferramentas -> Fidelidade, cada botão leva ao próximo estado;
 * (c) sem motor a tela abre e oferece "Iniciar o motor".
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h1200dp")
class ReformDescobertaTest {
    @get:Rule val rule = createComposeRule()

    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val motorDaReforma = FakeReformGateway()
    private val copiados = mutableListOf<String>()
    private val copiador = object : ApkSharer {
        override suspend fun share(uri: String, displayName: String): String {
            copiados += uri
            return "/storage/emulated/0/Download/AURA/" + sharedApkName(displayName)
        }
    }
    private val controlador = ReformController(motorDaReforma, copiador, MemoryReformIdStore(), escopo, clockLabel = { "10:00" })

    @After fun fechar() = escopo.cancel()

    private fun ComposeContentTestRule.tem(t: String) = onAllNodesWithText(t).fetchSemanticsNodes().isNotEmpty()
    private fun ComposeContentTestRule.espera(t: String) {
        waitUntil(5_000) { tem(t) }
    }
    private fun ComposeContentTestRule.toca(t: String) {
        espera(t)
        onNodeWithText(t).performClick()
    }

    /** Para telas que rolam: o botão pode estar abaixo da dobra e um clique fora da janela se perde. */
    private fun ComposeContentTestRule.tocaRolando(t: String) {
        espera(t)
        onNodeWithText(t).performScrollTo().performClick()
    }

    private fun abrirApp(motor: EngineGateway, seletor: SeletorFalso = SeletorFalso(), onOpenTermux: () -> Unit = {}) {
        val dono = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = seletor
        }
        rule.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides dono,
                LocalReformController provides controlador,
            ) {
                AuraApp(motor, onboardingDone = true, onOpenTermux = onOpenTermux)
            }
        }
    }

    @Test
    fun `do Inicio um toque em Reformar apps chega na escolha do APK`() {
        abrirApp(MotorFalso())
        rule.toca("Reformar apps")
        rule.espera("Escolher APK")
        rule.espera("Passo 1 de 5 · Escolher app")
    }

    @Test
    fun `percorre escolher APK, escopo, reversao, projeto, ferramentas e fidelidade e cada botao leva ao proximo estado`() {
        val seletor = SeletorFalso()
        abrirApp(MotorFalso(), seletor)

        // 1. Início -> Escolher APK -> seletor de arquivos
        rule.toca("Reformar apps")
        rule.toca("Escolher APK")
        assertEquals(1, seletor.abriu)
        rule.runOnIdle { seletor.terminar(Uri.parse("content://arquivos/document/meu-app.apk")) }

        // 2. Escopo com o APK escolhido; travar copia o APK e cria a reforma no motor
        rule.espera("Escopo da reforma")
        rule.espera("meu-app.apk")
        rule.toca("Travar escopo e começar")

        // 3. Reversão: o estado vem do motor (summary), e o passo "tools" já foi pedido ao começar
        rule.espera("Ver o projeto")
        rule.waitForIdle()
        assertEquals(listOf("content://arquivos/document/meu-app.apk"), copiados)
        assertTrue(motorDaReforma.calls.toString(), motorDaReforma.calls.any { it.startsWith("create:") && it.contains("meu-app.apk") })
        assertTrue(motorDaReforma.calls.toString(), motorDaReforma.calls.contains("step:a1b2c3d4e5f6:tools"))
        assertTrue(motorDaReforma.calls.any { it.startsWith("summary:") })
        // Sem número do motor, nada é inventado.
        rule.espera("O motor ainda não informa")

        // 4. Projeto -> Ferramentas (tudo pronto) -> volta ao Projeto
        rule.toca("Ver o projeto")
        rule.espera("Continuar reforma")
        rule.toca("Ferramentas")
        rule.espera("Ferramentas da reforma")
        rule.toca("Voltar ao projeto")
        rule.espera("Continuar reforma")

        // 5. Projeto -> Fidelidade: sem teste real, "Ainda não foi testado neste aparelho."; "Ver como testar" só explica
        //    (não relê o motor nem chama verify). O número só aparece quando o motor trouxer a fidelidade medida.
        rule.toca("Ver fidelidade")
        rule.espera("Ainda não foi testado neste aparelho.")
        rule.waitForIdle()
        val leituras = motorDaReforma.calls.count { it.startsWith("summary:") }
        rule.toca("Ver como testar")
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("O AURA ainda não executa esse teste sozinho", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(leituras, motorDaReforma.calls.count { it.startsWith("summary:") })
        assertTrue(motorDaReforma.calls.none { it.startsWith("verify:") })
        assertTrue(!rule.tem("Instalar ao lado do original"))

        // 6. Com a fidelidade medida pelo motor, "Instalar ao lado do original" libera e vira pedido no chat do Início
        motorDaReforma.onSummary = { FakeReformGateway.summary(fidelity = 95, stepsDone = listOf("tools")) }
        rule.onNodeWithContentDescription("Voltar para a reforma").performClick()
        rule.toca("Ver fidelidade")
        rule.espera("Instalar ao lado do original")
        rule.toca("Instalar ao lado do original")
        rule.espera("Instalar o app reformado ao lado do original")
    }

    @Test
    fun `sem motor a tela abre, o seletor segue valendo e Iniciar o motor chama o Termux`() {
        var termux = 0
        abrirApp(MotorFalso(down = true), onOpenTermux = { termux++ })
        rule.toca("Reformar apps")
        rule.espera("Escolher APK")
        rule.espera("O motor está desligado")
        rule.toca("Iniciar o motor")
        assertEquals(1, termux)
        rule.espera("Escolher APK")
    }

    @Test
    fun `sem motor travar o escopo explica e oferece Iniciar o motor`() {
        val seletor = SeletorFalso()
        motorDaReforma.onCreate = { _, _, _, _ -> throw ai.aura.app.offline.EngineUnreachableException("O motor não responde. Abra o Termux e inicie o motor.", ai.aura.app.offline.OfflineReason.EngineStopped) }
        var termux = 0
        abrirApp(MotorFalso(down = true), seletor, onOpenTermux = { termux++ })
        rule.toca("Reformar apps")
        rule.toca("Escolher APK")
        rule.runOnIdle { seletor.terminar(Uri.parse("content://arquivos/document/outro.apk")) }
        rule.toca("Travar escopo e começar")
        rule.espera("Não consegui travar o escopo")
        rule.toca("Iniciar o motor")
        assertEquals(1, termux)
        rule.espera("Tentar de novo")
    }

    @Test
    fun `em Projetos o atalho e a opcao ao criar projeto levam a Reformar apps`() {
        var aberto = 0
        rule.setContent { AuraTheme { ProjectsScreen(MotorFalso(), onReformApp = { aberto++ }) } }
        // Lista vazia: o convite principal continua sendo criar projeto, e reformar está à vista.
        rule.toca("Reformar um app que já existe")
        assertEquals(1, aberto)
        // Dentro de "Criar projeto" a mesma opção continua à mão.
        rule.toca("Criar projeto")
        rule.toca("Reformar um app que já existe")
        assertEquals(2, aberto)
    }

    @Test
    fun `Ajustes abre Check-up, Notificacoes, Voz e Bastidores mesmo sem motor`() {
        val abertas = mutableListOf<String>()
        rule.setContent { AuraTheme { ai.aura.app.ui.screens.settings.SettingsScreen(MotorFalso(down = true), onClose = {}, onOpenScreen = { abertas += it }) } }
        rule.tocaRolando("Abrir check-up")
        rule.tocaRolando("Ajustar avisos")
        rule.tocaRolando("Escolher modo de voz")
        rule.tocaRolando("Ver bastidores")
        assertEquals(listOf("checkup", "notificacoes", "voz-modos", "bastidores"), abertas)
    }

    @Test
    fun `APK compartilhado vira app da reforma e o resto e recusado`() {
        val apk = sharedApkFrom(Intent.ACTION_SEND, "application/vnd.android.package-archive", "content://x/document/Radio%20FM.apk", "Radio FM.apk")
        assertEquals(ReformApp(id = "content://x/document/Radio%20FM.apk", label = "Radio FM", apkUri = "content://x/document/Radio%20FM.apk"), apk)
        // Tipo genérico mas nome .apk: aceita (gerenciadores de arquivo costumam mandar octet-stream).
        assertEquals("app", sharedApkFrom(Intent.ACTION_SEND, "application/octet-stream", "content://x/1", "app.apk")?.label)
        assertNull(sharedApkFrom(Intent.ACTION_SEND, "image/png", "content://x/foto", "foto.png"))
        assertNull(sharedApkFrom(Intent.ACTION_SEND, "application/vnd.android.package-archive", null, "a.apk"))
        assertNull(sharedApkFrom(Intent.ACTION_VIEW, "application/vnd.android.package-archive", "content://x/1", "a.apk"))
    }

    @Test
    fun `o manifesto aceita Compartilhar para o AURA so para APK e continua com uma unica porta exportada`() {
        val manifesto = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifesto.contains("android.intent.action.SEND"))
        assertTrue(manifesto.contains("""android:mimeType="application/vnd.android.package-archive""""))
        assertEquals(1, Regex("""android:exported="true"""").findAll(manifesto).count())
    }
}
