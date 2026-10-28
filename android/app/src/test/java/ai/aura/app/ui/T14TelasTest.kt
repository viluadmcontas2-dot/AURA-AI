package ai.aura.app.ui

import ai.aura.app.ui.components.EmptyKind
import ai.aura.app.ui.components.EmptyState
import ai.aura.app.ui.engine.EngineEvent
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobRisk
import ai.aura.app.ui.engine.JobStatus
import ai.aura.app.ui.screens.activity.IncidentCard
import ai.aura.app.ui.screens.activity.IncidentCase
import ai.aura.app.ui.screens.activity.IncidentLevel
import ai.aura.app.ui.screens.backstage.BackstageScreen
import ai.aura.app.ui.screens.link.LinkSheetContent
import ai.aura.app.ui.screens.link.LinkSheetHost
import ai.aura.app.ui.screens.link.parseLink
import ai.aura.app.ui.theme.AuraTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

/**
 * T14: Vazios (UI-37), Intercorrências (UI-36, FN-19), Link-Aberto (UI-22, FN-14) e Bastidores (UI-19, FN-07).
 * Uma tela alta (h1400dp) para nada ficar fora da janela.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h1400dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class T14TelasTest {
    @get:Rule
    val rule = createComposeRule()

    private fun clicaveisTem44dp() {
        val nos = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue("nada clicável na tela", nos.isNotEmpty())
        with(rule.density) {
            for (n in nos) assertTrue("alvo menor que 44 dp: ${n.size}", n.size.width.toDp() >= 44.dp && n.size.height.toDp() >= 44.dp)
        }
    }

    @Test
    fun todoHttpsPassaPelaSheetEORestoERecusado() {
        val https = listOf(
            "https://github.com/viluadmcontas2-dot/minhas-financas/pull/12", "HTTPS://GitHub.COM/x", "  https://example.org/a?b=c#d  ",
            "https://gihub-login.com/entrar", "https://github.com@evil.com/x", "https://192.168.0.1/admin", "https://[::1]:8080/",
            "https://xn--gthub-9ra.com/", "https://gіthub.com/", "https://example.com:8443/x", "https://example.org",
            "https://example.org?x=1", "https://evil.com\\@github.com", "https://github.com%2eevil.com/", "https://exa mple.com",
            "https://example.com:99999/",
        )
        for (e in https) assertNotNull("deveria abrir a sheet: $e", parseLink(e))
        val recusados = listOf(
            "http://github.com/x", "javascript:alert(1)", "file:///sdcard/x", "intent://scan/#Intent;scheme=zxing;end",
            "content://media/1", "ftp://example.com/x", "mailto:a@b.c", "github.com/x", "https:evil.com", "", "   ", null,
        )
        for (e in recusados) assertNull("deveria recusar: $e", parseLink(e))
    }

    @Test
    fun soHostExatoEConhecidoEImitacaoEAvisada() {
        val conhecidos = listOf("https://github.com/x", "HTTPS://GITHUB.COM:443/x", "https://github.com./x", "https://www.github.com/x", "https://gist.github.com/x")
        for (e in conhecidos) assertEquals(e, "GitHub", parseLink(e)!!.knownName)
        assertEquals("Claude", parseLink("https://claude.ai/chat")!!.knownName)
        assertEquals("https://github.com/x", parseLink("HTTPS://GITHUB.COM:443/x")!!.url)

        val estranhos = listOf(
            "https://github.com@evil.com/x", "https://gihub-login.com/entrar", "https://github.com.evil.io/x", "https://github.com:8443/x",
            "https://example.org/", "https://[::1]:8080/", "https://192.168.0.1/", "https://xn--gthub-9ra.com/", "https://gіthub.com/",
            "https://github.com%2eevil.com/", "https://evil.com\\@github.com",
        )
        for (e in estranhos) assertNull("não pode ser conhecido: $e", parseLink(e)!!.knownName)

        val usuario = parseLink("https://github.com@evil.com/x")!!
        assertEquals("evil.com", usuario.host)
        assertEquals("https://evil.com/x", usuario.url)
        assertEquals("github.com", usuario.resembles)
        val parecido = parseLink("https://gihub-login.com/entrar")!!
        assertEquals("github.com", parecido.resembles)
        assertEquals("Parece com github.com, mas não é. Não digite sua senha.", parecido.warning)
        val desconhecido = parseLink("https://example.org/")!!
        assertNull(desconhecido.resembles)
        assertEquals("Não conheço este endereço. Confira antes de abrir e não digite sua senha.", desconhecido.warning)
        assertTrue(parecido.openable)
        assertFalse(parseLink("https://github.com%2eevil.com/")!!.openable)
    }

    @Test
    fun sheetDeSiteConhecidoOfereceAbrirCopiarECompartilhar() {
        val toques = mutableListOf<String>()
        rule.setContent {
            AuraTheme {
                LinkSheetContent(
                    parseLink("https://github.com/viluadmcontas2-dot/minhas-financas/pull/12")!!,
                    onOpenHere = { toques += "aqui" }, onOpenAnyway = { toques += "mesmo assim" }, onCopy = { toques += "copiar" },
                    onShare = { toques += "compartilhar" }, onClose = { toques += "fechar" },
                )
            }
        }
        rule.onNodeWithText("ABRIR LINK").assertIsDisplayed()
        rule.onNodeWithText("github.com/…/minhas-financas/pull/12").assertIsDisplayed()
        rule.onNodeWithText("Abrir este link?").assertIsDisplayed()
        rule.onNodeWithText("Links desconhecidos mostram um aviso antes de abrir.").assertIsDisplayed()
        rule.onNodeWithText("Link externo: confira o endereço").assertDoesNotExist()
        clicaveisTem44dp()
        for (rotulo in listOf("Abrir aqui", "Copiar link", "Compartilhar")) rule.onNodeWithText(rotulo).performClick()
        rule.onNodeWithContentDescription("Fechar").performClick()
        assertEquals(listOf("aqui", "copiar", "compartilhar", "fechar"), toques)
    }

    @Test
    fun sheetDeLinkParecidoAvisaEPedeConfirmacaoAntesDeAbrir() {
        val toques = mutableListOf<String>()
        rule.setContent {
            AuraTheme {
                LinkSheetContent(
                    parseLink("https://gihub-login.com/entrar")!!,
                    onOpenHere = { toques += "aqui" }, onOpenAnyway = { toques += "mesmo assim" }, onCopy = { toques += "copiar" },
                    onShare = { toques += "compartilhar" }, onClose = { toques += "fechar" },
                )
            }
        }
        rule.onNodeWithText("Link externo: confira o endereço").assertIsDisplayed()
        rule.onNodeWithText("Parece com github.com, mas não é. Não digite sua senha.").assertIsDisplayed()
        rule.onNodeWithText("https://gihub-login.com/entrar").assertIsDisplayed()
        rule.onNodeWithText("Abrir aqui").assertDoesNotExist()
        clicaveisTem44dp()
        for (rotulo in listOf("Abrir mesmo assim", "Cancelar", "Copiar link", "Compartilhar")) rule.onNodeWithText(rotulo).performClick()
        assertEquals(listOf("mesmo assim", "fechar", "copiar", "compartilhar"), toques)
    }

    @Test
    fun anfitriaoAbreASheetParaQualquerHttpsERecusaOResto() {
        val bruto = mutableStateOf<String?>(null)
        var dispensas = 0
        rule.setContent { AuraTheme { LinkSheetHost(bruto.value, onDismiss = { dispensas++ }) } }
        rule.onNodeWithText("ABRIR LINK").assertDoesNotExist()
        val casos = listOf(
            "https://github.com/u/r/pull/12" to "Abrir aqui",
            "https://example.org/a" to "Abrir mesmo assim",
            "https://gihub-login.com/entrar" to "Abrir mesmo assim",
        )
        for ((url, botao) in casos) {
            rule.runOnIdle { bruto.value = url }
            rule.waitForIdle()
            rule.onNodeWithText("ABRIR LINK").assertExists()
            rule.onNodeWithText(botao).assertExists()
        }
        rule.runOnIdle { bruto.value = "https://github.com/x" }
        rule.waitForIdle()
        rule.onNodeWithText("Copiar link").performClick()
        rule.onNodeWithText("Link copiado.").assertExists()

        rule.runOnIdle { bruto.value = "http://example.org" }
        rule.waitForIdle()
        rule.onNodeWithText("Não abro este link").assertExists()
        rule.onNodeWithText("Abrir aqui").assertDoesNotExist()
        rule.onNodeWithText("Fechar aviso").performClick()
        assertEquals(1, dispensas)
    }

    @Test
    fun cadaVazioTemIlustracaoDe148dpUmaFraseEUmBotao() {
        assertEquals(4, EmptyKind.entries.size)
        val tipo = mutableStateOf(EmptyKind.entries.first())
        var toques = 0
        rule.setContent { AuraTheme { EmptyState(tipo.value, onAction = { toques++ }) } }
        for (k in EmptyKind.entries) {
            rule.runOnIdle { tipo.value = k }
            rule.onNodeWithText(k.title).assertIsDisplayed()
            rule.onNodeWithText(k.body).assertIsDisplayed()
            rule.onNodeWithTag("empty-illustration").assertHeightIsEqualTo(148.dp)
            rule.onAllNodes(hasClickAction()).assertCountEquals(1)
            clicaveisTem44dp()
            rule.onNodeWithText(k.action).performClick()
        }
        assertEquals(4, toques)
    }

    @Test
    fun onzeIntercorrenciasEmTresNiveisCadaUmComSeuBotao() {
        assertEquals(11, IncidentCase.entries.size)
        assertEquals(
            mapOf(IncidentLevel.Info to 2, IncidentLevel.NeedsYou to 6, IncidentLevel.Failed to 3),
            IncidentCase.entries.groupingBy { it.level }.eachCount(),
        )
        val caso = mutableStateOf(IncidentCase.entries.first())
        val toques = mutableListOf<String>()
        rule.setContent { AuraTheme { IncidentCard(caso.value, onAction = { toques += it }) } }
        for (c in IncidentCase.entries) {
            rule.runOnIdle { caso.value = c }
            rule.onNodeWithText(c.title).assertIsDisplayed()
            c.body?.let { rule.onNodeWithText(it).assertIsDisplayed() }
            for (a in c.actions) rule.onNodeWithText(a).assertIsDisplayed()
            clicaveisTem44dp()
            rule.onNodeWithText(c.actions.first()).performClick()
            assertEquals(c.actions.first(), toques.last())
        }
        assertEquals(11, toques.size)
    }

    @Test
    fun bastidoresMostraOQueExisteCopiaRodaExplicaEConvidaQuandoVazio() {
        fun job(id: String, titulo: String, status: JobStatus, risco: JobRisk = JobRisk.Read, resultado: String? = null, erro: String? = null) =
            Job(id, "req_$id", titulo, "shell", status, risco, 1_700_000_000_000, resultado, erro)

        val pull = job("c1", "git pull origin main", JobStatus.Completed, resultado = "Already up to date.")
        val gradle = job("c2", "./gradlew testDebugUnitTest", JobStatus.Completed, resultado = "> Task :app:compileDebugKotlin\nBUILD SUCCESSFUL in 41s")
        val commit = job("c3", "git commit -m \"Corrige layout\"", JobStatus.Completed, JobRisk.Write)
        val watch = job("c4", "gh run watch", JobStatus.Running)
        val falha = job("c5", "gh pr create", JobStatus.Failed, erro = "adapter_error")
        val jobs = mutableStateOf(listOf(pull, gradle, commit, watch, falha))
        val eventos = listOf(
            EngineEvent(1, "job.running", "c2", emptyMap(), 1_700_000_000_000),
            EngineEvent(2, "job.completed", "c2", mapOf("segredo" to "nao-mostrar"), 1_700_000_041_000),
            EngineEvent(3, "job.running", "c4", emptyMap(), 1_700_000_050_000),
        )
        val chamadas = mutableListOf<String>()
        rule.setContent {
            AuraTheme {
                BackstageScreen(
                    jobs.value, eventos,
                    onBack = { chamadas += "voltar" }, onCopy = { chamadas += "copiar:$it" }, onRerun = { chamadas += "rodar:${it.id}" },
                    onExplain = { chamadas += "explicar:${it.id}" }, onAsk = { chamadas += "perguntar" },
                )
            }
        }
        rule.onNodeWithText("Bastidores").assertIsDisplayed()
        rule.onNodeWithText("É o que o AURA fez por baixo. Você não precisa entender, mas pode conferir tudo.").assertIsDisplayed()
        rule.onNodeWithText("O motor ainda não guarda cada comando em separado. Aqui aparecem o pedido, o resultado e os registros dele.").assertIsDisplayed()
        rule.onNodeWithText("\$ git pull origin main").assertIsDisplayed()
        rule.onAllNodesWithText("ok").assertCountEquals(3)
        rule.onAllNodesWithText("rodando").assertCountEquals(1)
        rule.onAllNodesWithText("falhou").assertCountEquals(1)
        rule.onAllNodesWithText("Só lê").assertCountEquals(4)
        rule.onAllNodesWithText("Altera arquivos").assertCountEquals(1)
        rule.onNodeWithText("Copiar").assertDoesNotExist()

        rule.onNodeWithText("\$ ./gradlew testDebugUnitTest").performClick()
        rule.onNodeWithText("> Task :app:compileDebugKotlin").assertIsDisplayed()
        rule.onNodeWithText("BUILD SUCCESSFUL in 41s").assertIsDisplayed()
        rule.onAllNodesWithText("job.running", substring = true).assertCountEquals(1)
        rule.onNodeWithText("job.completed", substring = true).assertIsDisplayed()
        rule.onNodeWithText("segredo", substring = true).assertDoesNotExist()
        clicaveisTem44dp()
        rule.onNodeWithText("Copiar").performClick()
        rule.onNodeWithText("Copiado").assertIsDisplayed()
        rule.onNodeWithText("Rodar de novo").performClick()
        rule.onNodeWithText("Explicar simples").performClick()
        rule.onNodeWithText("\$ ./gradlew testDebugUnitTest").performClick()
        rule.onNodeWithText("BUILD SUCCESSFUL in 41s").assertDoesNotExist()

        rule.onNodeWithText("\$ gh run watch").performClick()
        rule.onNodeWithText("Ainda sem saída.").assertIsDisplayed()
        rule.onNodeWithText("Rodar de novo").assertIsNotEnabled()
        rule.onNodeWithText("\$ git commit -m \"Corrige layout\"").performClick()
        rule.onNodeWithText("Este comando não imprimiu nada.").assertIsDisplayed()
        rule.onNodeWithText("\$ gh pr create").performClick()
        rule.onNodeWithText("erro: adapter_error").assertIsDisplayed()

        rule.onNodeWithText("Pergunte sobre este comando…").performClick()
        rule.onNodeWithContentDescription("Voltar para o resultado").performClick()

        rule.runOnIdle { jobs.value = emptyList() }
        rule.onNodeWithText("Nada rodando agora").assertIsDisplayed()
        rule.onNodeWithText("Pergunte sobre este comando…").assertDoesNotExist()
        rule.onAllNodes(hasClickAction()).assertCountEquals(2)
        rule.onNodeWithText("Pedir algo ao AURA").performClick()

        assertEquals(
            listOf(
                "copiar:./gradlew testDebugUnitTest\n> Task :app:compileDebugKotlin\nBUILD SUCCESSFUL in 41s",
                "rodar:c2", "explicar:c2", "perguntar", "voltar", "perguntar",
            ),
            chamadas,
        )
    }
}
