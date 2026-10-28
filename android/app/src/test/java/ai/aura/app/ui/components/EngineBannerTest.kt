package ai.aura.app.ui.components

import ai.aura.app.offline.LinkState
import ai.aura.app.offline.OfflineReason
import ai.aura.app.ui.theme.AuraTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Banner global do motor: texto e UMA ação por motivo, em cada LinkState. */
@RunWith(RobolectricTestRunner::class)
class EngineBannerTest {
    @get:Rule val rule = createComposeRule()

    private val acoes = mapOf(
        OfflineReason.NoTermux to "Instalar o Termux",
        OfflineReason.NoPermission to "Permitir",
        OfflineReason.NoToken to "Conectar",
        OfflineReason.EngineStopped to "Iniciar o motor",
    )
    private var estado by mutableStateOf<LinkState>(LinkState.Unknown)
    private var clicado: OfflineReason? = null

    @Before
    fun montar() {
        rule.setContent { AuraTheme { EngineBanner(estado, onAction = { clicado = it }) } }
    }

    @Test
    fun cadaMotivoMostraMotorDesligadoEUmaAcaoQueChamaOCallback() {
        for ((motivo, texto) in acoes) {
            estado = LinkState.Offline(motivo)
            clicado = null
            rule.waitForIdle()
            rule.onNodeWithText("Motor desligado").assertIsDisplayed()
            acoes.values.filter { it != texto }.forEach { rule.onNodeWithText(it).assertDoesNotExist() }
            rule.onNodeWithText(texto).assertHeightIsAtLeast(44.dp).performClick()
            assertEquals(motivo, clicado)
        }
    }

    @Test
    fun iniciandoMostraOAvisoSemBotao() {
        estado = LinkState.Starting
        rule.waitForIdle()
        rule.onNodeWithText("Motor desligado").assertIsDisplayed()
        rule.onNodeWithText("Iniciando…").assertIsDisplayed()
        rule.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun desconhecidoEOnlineNaoMostramBanner() {
        for (s in listOf(LinkState.Unknown, LinkState.Online)) {
            estado = s
            rule.waitForIdle()
            rule.onNodeWithText("Motor desligado").assertDoesNotExist()
        }
    }
}
