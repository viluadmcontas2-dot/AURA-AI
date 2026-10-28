package ai.aura.app.ui.screens.reform

import ai.aura.app.ui.theme.AuraTheme
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reformar 1 · Escolher o app: lista, vazio e erro abrem sem motor; o app de ré nunca é escolhido. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
class ReformAppsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val all = listOf(ReformSamples.player, ReformSamples.gps, ReformSamples.radio, ReformSamples.rearCamera)
    private var next: ReformApp? = null
    private var back = 0
    private val fixes = mutableListOf<ReformFix>()

    private fun show(state: ReformAppsState) = compose.setContent {
        AuraTheme {
            ReformAppsScreen(
                state = state, onBack = { back++ }, onNext = { next = it },
                onFix = { fixes += it }, currentYear = 2026,
            )
        }
    }

    @Test
    fun `lista cada app com seus dados, marca o primeiro e tranca a camera de re`() {
        show(ReformAppsState(apps = all))

        compose.onNodeWithText("Multimídia do carro · 4 apps").assertExists()
        compose.onNodeWithText("Passo 1 de 2 · Escolher app").assertExists()
        compose.onNodeWithText("Player de música").assertIsSelected()
            .assertTextContains("v2.1 · 2016").assertTextContains("Muito antigo")
            .assertTextContains("Potencial de melhoria").assertTextContains("Alto")
        compose.onNodeWithText("Navegador GPS").assertIsNotSelected().assertTextContains("Antigo")
        compose.onNodeWithText("Rádio FM").assertTextContains("Médio")
        compose.onNodeWithText("Câmera de ré").assertHasNoClickAction()
            .assertTextContains("Segurança do carro: o AURA não mexe").assertTextContains("Protegido")
        compose.assertEveryTapTargetIsAtLeast44dp()
    }

    @Test
    fun `escolher outro app e tocar no botao entrega esse app, e voltar avisa a navegacao`() {
        show(ReformAppsState(apps = all))

        compose.onNodeWithText("Navegador GPS").performClick()
        compose.onNodeWithText("Navegador GPS").assertIsSelected()
        compose.onNodeWithText("Player de música").assertIsNotSelected()
        compose.onNodeWithText("Escolher escopo").performClick()
        assertEquals(ReformSamples.gps, next)

        compose.onNodeWithContentDescription("Voltar para o aparelho").performClick()
        assertEquals(1, back)
    }

    @Test
    fun `quando todos os apps sao protegidos nao ha o que escolher`() {
        show(ReformAppsState(apps = listOf(ReformSamples.rearCamera)))

        compose.onNodeWithText("Escolher escopo").assertIsNotEnabled()
        compose.onNodeWithText("Todos os apps desta lista são protegidos. Não há nada para reformar aqui.").assertExists()
    }

    @Test
    fun `sem apps o estado vazio tem uma unica acao, procurar apps`() {
        show(ReformAppsState())

        compose.onNodeWithText("Nenhum app para mostrar").assertExists()
        compose.onNodeWithText("Escolher escopo").assertDoesNotExist()
        compose.onNodeWithText("Procurar apps").assertIsEnabled().performClick()
        assertEquals(listOf(ReformFix.Search), fixes)
        compose.assertEveryTapTargetIsAtLeast44dp()
    }

    @Test
    fun `erro mostra o que houve e uma unica acao no lugar da lista`() {
        show(
            ReformAppsState(
                apps = all,
                problem = ReformProblem(
                    "O motor está desligado",
                    "Ligue o motor para procurar os apps da multimídia.",
                    ReformFix.StartEngine,
                ),
            ),
        )

        compose.onNodeWithText("O motor está desligado", substring = true).assertExists()
        compose.onNodeWithText("Player de música").assertDoesNotExist()
        compose.onNodeWithText("Escolher escopo").assertDoesNotExist()
        compose.onNodeWithText("Iniciar o motor").performClick()
        assertEquals(listOf(ReformFix.StartEngine), fixes)
        compose.assertEveryTapTargetIsAtLeast44dp()
    }
}
