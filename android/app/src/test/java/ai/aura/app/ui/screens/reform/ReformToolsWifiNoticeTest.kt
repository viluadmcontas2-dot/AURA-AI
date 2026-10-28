package ai.aura.app.ui.screens.reform

import ai.aura.app.integration.ReformDownloadNotice
import ai.aura.app.ui.theme.AuraTheme
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Ferramentas: "Aguardando Wi-Fi" (sem erro) e o aviso de download grande, pequeno, interativo e que nunca bloqueia. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h1200dp")
class ReformToolsWifiNoticeTest {
    @get:Rule val compose = createComposeRule()

    private fun waiting(notices: List<ReformDownloadNotice> = emptyList()) = ReformToolsState(
        tools = REFORM_REQUIRED_TOOLS.map {
            when (it.id) {
                "node" -> it.copy(status = ReformToolStatus.Ready)
                "jdk" -> it.copy(status = ReformToolStatus.WaitingWifi)
                else -> it.copy(status = ReformToolStatus.Missing)
            }
        },
        waitMessage = "Estou em dados móveis. Conecte ao Wi-Fi e toque em Tentar de novo.",
        downloadNotices = notices,
    )

    @Test
    fun `dados moveis mostram Aguardando Wi-Fi como estado normal, sem banner de erro, e o botao tenta de novo`() {
        var installs = 0
        compose.setContent {
            AuraTheme { ReformToolsScreen(waiting(), onBack = {}, onInstall = { installs++ }, onResume = {}, onFix = {}) }
        }
        compose.onNodeWithContentDescription("JDK 21: Aguardando Wi-Fi").assertExists()
        compose.onNodeWithText("Estou em dados móveis", substring = true).assertExists()
        compose.onNodeWithText("O motor está desligado", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Tentar de novo").assertIsEnabled().performClick()
        assertEquals(1, installs)
    }

    @Test
    fun `aviso de download grande - toque mostra o tamanho em MB e Entendi fecha, sem bloquear nada`() {
        var installs = 0
        compose.setContent {
            AuraTheme {
                ReformToolsScreen(
                    waiting(listOf(ReformDownloadNotice("jdk", 180))),
                    onBack = {}, onInstall = { installs++ }, onResume = {}, onFix = {},
                )
            }
        }
        compose.onNodeWithText("Download grande", substring = true).assertExists().assertHeightIsAtLeast(44.dp)
        compose.onNodeWithText("cerca de 180 MB", substring = true).assertDoesNotExist()
        // Não bloqueante: o botão principal responde com o aviso aberto ou fechado.
        compose.onNodeWithText("Tentar de novo").assertIsEnabled()

        compose.onNodeWithText("Download grande", substring = true).performClick()
        compose.onNodeWithText("JDK 21: cerca de 180 MB").assertExists()
        compose.onNodeWithText("Tentar de novo").assertIsEnabled().performClick()
        assertEquals(1, installs)

        compose.onNodeWithText("Entendi").performClick()
        compose.onNodeWithText("JDK 21: cerca de 180 MB").assertDoesNotExist()
        compose.onNodeWithText("Download grande", substring = true).assertDoesNotExist()
    }

    @Test
    fun `sem aviso do motor nada aparece`() {
        compose.setContent {
            AuraTheme { ReformToolsScreen(waiting(), onBack = {}, onInstall = {}, onResume = {}, onFix = {}) }
        }
        compose.onNodeWithText("Download grande", substring = true).assertDoesNotExist()
    }
}
