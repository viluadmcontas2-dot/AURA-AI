package ai.aura.app.ui.screens.reform

import ai.aura.app.ui.theme.AuraTheme
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reformar 2 · Escopo: tempo, risco, garantias e meta de 90%; nada começa sem a confirmação do dono. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
class ReformScopeScreenTest {
    @get:Rule val compose = createComposeRule()

    private val owner = "Sou dono do app ou tenho permissão para modificá-lo."
    private val start = "Travar escopo e começar"
    private var plan: ReformPlan? = null
    private var back = 0

    private fun show(app: ReformApp?) = compose.setContent {
        AuraTheme { ReformScopeScreen(app = app, onBack = { back++ }, onStart = { plan = it }) }
    }

    @Test
    fun `abre com o escopo recomendado, as garantias e a meta de 90 por cento`() {
        show(ReformSamples.player)

        compose.onNodeWithText("Escopo da reforma").assertExists()
        compose.onNodeWithText("Player de música").assertExists()
        compose.onNodeWithText("Só visual").assertIsNotSelected()
            .assertTextContains("10 min").assertTextContains("Risco baixo")
        compose.onNodeWithText("Visual + fluxos").assertIsSelected()
            .assertTextContains("Recomendado").assertTextContains("25 min").assertTextContains("Risco médio")
        compose.onNodeWithText("Reconstrução completa").assertIsNotSelected()
            .assertTextContains("1 h 30").assertTextContains("Risco maior")
        listOf("O app original não é apagado", "O novo instala ao lado, como cópia", "Voltar ao original com 1 toque")
            .forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithContentDescription("Meta de 90 por cento").assertExists()
        compose.onNodeWithText("90%").assertExists()
        compose.onNodeWithText("Cerca de 25 min · usa só a sua assinatura").assertExists()
        compose.onNodeWithContentDescription("Modo carro").assertIsOn()
        compose.assertEveryTapTargetIsAtLeast44dp()

        compose.onNodeWithContentDescription("Voltar para a escolha do app").performClick()
        assertEquals(1, back)
    }

    @Test
    fun `so comeca depois de confirmar que o app e do usuario e entrega o que foi escolhido`() {
        show(ReformSamples.gps)

        compose.onNodeWithText(start).assertIsNotEnabled()
        compose.onNodeWithText(owner).assertIsOff().performClick()
        compose.onNodeWithText(owner).assertIsOn()

        compose.onNodeWithText("Reconstrução completa").performClick()
        compose.onNodeWithText("Reconstrução completa").assertIsSelected()
        compose.onNodeWithText("Visual + fluxos").assertIsNotSelected()
        compose.onNodeWithText("Cerca de 1 h 30 · usa só a sua assinatura").assertExists()

        compose.onNodeWithContentDescription("Modo carro").performClick()
        compose.onNodeWithContentDescription("Modo carro").assertIsOff()

        assertNull(plan)
        compose.onNodeWithText(start).assertIsEnabled().performClick()
        assertEquals(
            ReformPlan(
                appId = "com.exemplo.gps", appLabel = "Navegador GPS", scope = ReformScope.FullRebuild,
                carMode = false, fidelityTargetPercent = 90, ownerConfirmed = true,
            ),
            plan,
        )
    }

    @Test
    fun `sem app escolhido a tela explica e leva de volta`() {
        show(null)

        compose.onNodeWithText("Nenhum app escolhido").assertExists()
        compose.onNodeWithText(start).assertDoesNotExist()
        compose.onNodeWithText("Escolher app").performClick()
        assertEquals(1, back)
        compose.assertEveryTapTargetIsAtLeast44dp()
    }

    @Test
    fun `app protegido nunca chega ao escopo`() {
        show(ReformSamples.rearCamera)

        compose.onNodeWithText("Este app é protegido").assertExists()
        compose.onNodeWithText(start).assertDoesNotExist()
        compose.onNodeWithText("Escolher outro app").performClick()
        assertEquals(1, back)
        assertNull(plan)
    }
}
