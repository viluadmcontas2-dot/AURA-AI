package ai.aura.app.permissions

import ai.aura.app.MainActivity
import ai.aura.app.ui.screens.onboarding.OnboardingFlow
import ai.aura.app.ui.screens.onboarding.TermuxSetupState
import ai.aura.app.ui.screens.onboarding.defaultAccounts
import ai.aura.app.ui.theme.AuraTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Primeiro uso: o Android pergunta no passo certo da abertura, em sequência (Termux, depois avisos) e sem o dono
 * ter que achar botão escondido. Negar nunca trava: o app segue e a ação "Permitir" continua nas telas.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
class FirstUsePermissionsTest {
    @get:Rule val rule = createComposeRule()

    private val order = mutableListOf<String>()

    private fun show(termux: TermuxSetupState) {
        rule.setContent {
            AuraTheme {
                OnboardingFlow(
                    termux = termux, accounts = defaultAccounts(),
                    onRequestTermuxPermission = { order += "termux" },
                    onOpenTermux = {},
                    onSignIn = {}, onInstallAccount = {},
                    onRequestNotifications = { order += "avisos" },
                    onFinish = {},
                )
            }
        }
    }

    @Test
    fun termuxEncontradoPedeAPermissaoAoChegarNoPassoDoTermuxEOsAvisosAoChegarEmContas() {
        show(TermuxSetupState(termuxFound = true, permissionGranted = false))
        rule.onNodeWithText("Começar").performClick()
        // Sem tocar em nada: o diálogo do sistema abre ao chegar no passo do Termux.
        rule.waitForIdle()
        assertEquals(listOf("termux"), order)

        // Negar não prende o dono: "Pular por agora" segue, e só então vêm os avisos.
        rule.onNodeWithText("Pular por agora").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Conectar suas contas").assertIsDisplayed()
        assertEquals(listOf("termux", "avisos"), order)

        // O botão continua ali para tentar de novo, sem pedir sozinho outra vez.
        rule.onNodeWithText("Entrar no AURA").assertIsDisplayed()
    }

    @Test
    fun semTermuxNaoPedeNadaDoTermuxEOBotaoDeInstalarAparece() {
        show(TermuxSetupState(termuxFound = false))
        rule.onNodeWithText("Começar").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Instalar Termux").assertIsDisplayed()
        assertTrue("sem Termux não há o que perguntar", order.isEmpty())
    }

    @Test
    fun permissaoJaConcedidaNaoPerguntaDeNovo() {
        show(TermuxSetupState(termuxFound = true, permissionGranted = true))
        rule.onNodeWithText("Começar").performClick()
        rule.waitForIdle()
        assertTrue(order.isEmpty())
    }
}

/** Com o app de verdade: chegar ao passo Contas dispara o diálogo de avisos do Android (13 ou mais novo). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
class FirstUseNotificationsRealAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun chegarEmContasPedeOsAvisosAoSistema() {
        val activity = shadowOf(rule.activity)
        assertNull("nada é pedido na Boas-vindas", activity.lastRequestedPermission)
        rule.onNodeWithText("Começar").performClick()
        rule.onNodeWithText("Pular por agora").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Conectar suas contas").assertIsDisplayed()
        val asked = activity.lastRequestedPermission?.requestedPermissions?.toList().orEmpty()
        assertEquals(listOf("android.permission.POST_NOTIFICATIONS"), asked)
    }
}
