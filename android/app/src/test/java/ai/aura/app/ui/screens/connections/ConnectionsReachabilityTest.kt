package ai.aura.app.ui.screens.connections

import ai.aura.app.offline.LinkState
import ai.aura.app.offline.OfflineReason
import ai.aura.app.ui.AuraApp
import ai.aura.app.ui.components.EngineBanner
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.FakeEngineGateway
import ai.aura.app.ui.engine.Capability
import ai.aura.app.ui.engine.EngineEvent
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobRequest
import ai.aura.app.ui.engine.RouteResult
import ai.aura.app.ui.screens.DevicesScreen
import ai.aura.app.ui.screens.settings.SettingsScreen
import ai.aura.app.ui.theme.AuraTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** Motor que não responde a nada: a tela precisa abrir mesmo assim (regra 1 do projeto). */
private class DeadGateway : EngineGateway {
    override val events: Flow<EngineEvent> = emptyFlow()
    override suspend fun jobs(): List<Job> = throw IOException("Termux não responde")
    override suspend fun approve(id: String): Job = throw IOException("Termux não responde")
    override suspend fun cancel(id: String): Job = throw IOException("Termux não responde")
    override suspend fun route(text: String): RouteResult = throw IOException("Termux não responde")
    override suspend fun submit(requestId: String, request: JobRequest): Job = throw IOException("Termux não responde")
    override suspend fun capabilities(): List<Capability> = throw IOException("Termux não responde")
}

/**
 * "Conecte o Termux em Conexões" só serve se Conexões estiver a um toque: do aviso "Motor desligado" (em qualquer
 * tela), de Ajustes (mesmo sem motor) e de Dispositivos.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
class ConnectionsReachabilityTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun avisoMotorDesligadoTemBotaoQueAbreConexoes() {
        var abriu = 0
        rule.setContent {
            AuraTheme {
                EngineBanner(LinkState.Offline(OfflineReason.NoToken), onAction = {}, onOpenConnections = { abriu++ })
            }
        }
        rule.onNodeWithText("Motor desligado").assertIsDisplayed()
        rule.onNodeWithText("Abrir Conexões").assertHeightIsAtLeast(44.dp).performClick()
        assertEquals(1, abriu)
    }

    @Test
    fun noAppTodoOAvisoLevaATelaConexoesComAAcaoDoMotivo() {
        val link = MutableStateFlow<LinkState>(LinkState.Offline(OfflineReason.NoToken))
        rule.setContent {
            AuraTheme {
                AuraApp(
                    FakeEngineGateway(), onboardingDone = true, linkState = link,
                )
            }
        }
        rule.onAllNodesWithText("Motor desligado").onFirst().assertIsDisplayed()
        rule.onNodeWithText("Abrir Conexões").performClick()

        // Conexões abriu: assistente com o estado e a ação certa; conta do Claude e permissões na mesma tela.
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Assistente neste celular").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Falta conectar o Termux ao AURA.").assertIsDisplayed()
        rule.onNodeWithText("Sem vínculo").assertIsDisplayed()
        rule.onNodeWithText("Conectar Termux").assertIsDisplayed()
        rule.onNodeWithText("Conta do Claude").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Permissões").performScrollTo().assertIsDisplayed()
        // Na própria tela de Conexões o aviso não repete o atalho para ela.
        rule.onAllNodesWithText("Abrir Conexões").assertCountEquals(0)
        rule.onNodeWithContentDescription("Voltar").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Assistente neste celular").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun ajustesAbreConexoesMesmoSemMotor() {
        var abriu = 0
        rule.setContent {
            AuraTheme { SettingsScreen(DeadGateway(), onClose = {}, onOpenConnections = { abriu++ }) }
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Motor indisponível").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Abrir Conexões").performClick()
        assertEquals(1, abriu)
    }

    @Test
    fun dispositivosTemAtalhoParaConexoes() {
        var abriu = 0
        rule.setContent {
            AuraTheme { DevicesScreen(FakeEngineGateway(), onOpenConnections = { abriu++ }) }
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Abrir Conexões").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Abrir Conexões").performScrollTo().performClick()
        assertEquals(1, abriu)
    }

    @Test
    fun conexoesAbreSemMotorEMostraACaoParaLigar() {
        val acoes = mutableListOf<OfflineReason>()
        rule.setContent {
            AuraTheme {
                ConnectionsScreen(
                    DeadGateway(), LinkState.Offline(OfflineReason.EngineStopped),
                    onEngineAction = { acoes += it }, onOpenUrl = {}, onBack = {},
                )
            }
        }
        rule.onNodeWithText("Parado").assertIsDisplayed()
        rule.onNodeWithText("Iniciar o motor").performClick()
        assertEquals(listOf(OfflineReason.EngineStopped), acoes)
        // As contas dizem a verdade (indisponível) em vez de ficarem em branco.
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Indisponível").fetchSemanticsNodes().isNotEmpty() }
    }
}
