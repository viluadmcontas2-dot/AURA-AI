package ai.aura.app.ui.screens.reform

import ai.aura.app.ui.theme.AuraTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/** Fluxo 1 → 2 → plano e 1 → 5 → 1, ligado só por callbacks (a rota de verdade é do controlador). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
class ReformFlowTest {
    @get:Rule val compose = createComposeRule()

    private enum class Step { Apps, Scope, Tools }

    private var plan: ReformPlan? = null

    private fun show(problem: ReformProblem? = null) = compose.setContent {
        var step by remember { mutableStateOf(Step.Apps) }
        var app by remember { mutableStateOf<ReformApp?>(null) }
        AuraTheme {
            when (step) {
                Step.Apps -> ReformAppsScreen(
                    state = ReformAppsState(
                        apps = listOf(ReformSamples.player, ReformSamples.gps, ReformSamples.rearCamera),
                        problem = problem,
                    ),
                    onBack = {},
                    onNext = { app = it; step = Step.Scope },
                    onFix = { if (it == ReformFix.OpenTools) step = Step.Tools },
                    currentYear = 2026,
                )
                Step.Scope -> ReformScopeScreen(app = app, onBack = { step = Step.Apps }, onStart = { plan = it })
                Step.Tools -> ReformToolsScreen(
                    state = ReformToolsState(), onBack = { step = Step.Apps },
                    onInstall = {}, onResume = {}, onFix = {},
                )
            }
        }
    }

    @Test
    fun `escolher app, definir escopo e travar entrega o plano e voltar reabre a lista`() {
        show()

        compose.onNodeWithText("Navegador GPS").performClick()
        compose.onNodeWithText("Escolher escopo").performClick()
        compose.onNodeWithText("Escopo da reforma").assertExists()
        compose.onNodeWithText("Reformar apps").assertDoesNotExist()

        compose.onNodeWithContentDescription("Voltar para a escolha do app").performClick()
        compose.onNodeWithText("Reformar apps").assertExists()

        compose.onNodeWithText("Navegador GPS").performClick()
        compose.onNodeWithText("Escolher escopo").performClick()
        compose.onNodeWithText("Sou dono do app ou tenho permissão para modificá-lo.").performClick()
        compose.onNodeWithText("Só visual").performClick()
        compose.onNodeWithText("Travar escopo e começar").performClick()

        assertEquals(
            ReformPlan("com.exemplo.gps", "Navegador GPS", ReformScope.VisualOnly, true, 90, true),
            plan,
        )
    }

    @Test
    fun `da lista vai as ferramentas pela acao do erro e volta`() {
        show(ReformProblem("Faltam ferramentas", "Instale o adb e o JADX antes de procurar os apps.", ReformFix.OpenTools))

        compose.onNodeWithText("Instalar ferramentas").performClick()
        compose.onNodeWithText("Ferramentas da reforma").assertExists()

        compose.onNodeWithContentDescription("Voltar ao projeto").performClick()
        compose.onNodeWithText("Reformar apps").assertExists()
    }
}
