package ai.aura.app.ui.screens.reform

import ai.aura.app.ui.theme.AuraTheme
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertRangeInfoEquals
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

/** Reformar 5 · Ferramentas: o que o motor contou aparece como veio; sem dado, mostra "não verificado". */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
class ReformToolsScreenTest {
    @get:Rule val compose = createComposeRule()

    private var back = 0
    private var installs = 0
    private var resumes = 0
    private val fixes = mutableListOf<ReformFix>()

    private fun show(state: ReformToolsState) = compose.setContent {
        AuraTheme {
            ReformToolsScreen(
                state = state, onBack = { back++ }, onInstall = { installs++ },
                onResume = { resumes++ }, onFix = { fixes += it },
            )
        }
    }

    private fun ready(vararg ids: String) = REFORM_REQUIRED_TOOLS.map {
        if (it.id in ids) it.copy(status = ReformToolStatus.Ready) else it
    }

    @Test
    fun `mostra estado, progresso, aviso e sessao salva e deixa retomar do ultimo`() {
        show(
            ReformToolsState(
                tools = ready("node", "jdk", "adb").map {
                    when (it.id) {
                        "jadx" -> it.copy(status = ReformToolStatus.Installing, percent = 63)
                        "apktool", "rea" -> it.copy(status = ReformToolStatus.Queued)
                        else -> it
                    }
                },
                session = listOf(
                    ReformSessionEntry("14:02", "Node.js e JDK prontos"),
                    ReformSessionEntry("14:09", "adb pareado com a multimídia"),
                    ReformSessionEntry("14:21", "JADX baixado, instalando", active = true),
                ),
            ),
        )

        compose.onNodeWithText("Ferramentas da reforma").assertExists()
        compose.onNodeWithText("O AURA instala sozinho no Termux e guarda o estado.").assertExists()
        compose.onNodeWithContentDescription("Node.js 22: Pronto").assertExists()
        compose.onNodeWithContentDescription("JADX sem tela: Instalando 63%").assertExists()
        compose.onNodeWithContentDescription("Apktool: Na fila").assertExists()
        compose.onNodeWithContentDescription("Instalação de JADX sem tela")
            .assertRangeInfoEquals(ProgressBarRangeInfo(0.63f, 0f..1f))
        compose.onNodeWithText("O JADX usa muita memória. Se faltar, o AURA divide o trabalho em partes menores.").assertExists()

        compose.onNodeWithText("Sessão salva").assertExists()
        compose.onNodeWithText("14:21").assertExists()
        compose.onNodeWithText("JADX baixado, instalando").assertExists()
        compose.onNodeWithText("Se o Android encerrar o Termux, nada se perde.").assertExists()
        compose.onNodeWithText("Instalando ferramentas").assertIsNotEnabled()
        compose.assertEveryTapTargetIsAtLeast44dp()

        compose.onNodeWithText("Retomar do último").performClick()
        assertEquals(1, resumes)
        compose.onNodeWithContentDescription("Voltar ao projeto").performClick()
        assertEquals(1, back)
    }

    @Test
    fun `sem dado do motor lista as seis ferramentas como nao verificadas e oferece instalar`() {
        show(ReformToolsState())

        REFORM_REQUIRED_TOOLS.forEach { compose.onNodeWithContentDescription("${it.label}: Não verificado").assertExists() }
        compose.onNodeWithText("Ainda não há sessão salva", substring = true).assertExists()
        compose.onNodeWithText("Retomar do último").assertDoesNotExist()
        compose.assertEveryTapTargetIsAtLeast44dp()

        compose.onNodeWithText("Instalar o que falta").assertIsEnabled().performClick()
        assertEquals(1, installs)
    }

    @Test
    fun `com tudo pronto o botao principal volta ao projeto`() {
        show(ReformToolsState(tools = ready(*REFORM_REQUIRED_TOOLS.map { it.id }.toTypedArray())))

        compose.onNodeWithText("Instalar o que falta").assertDoesNotExist()
        compose.onNodeWithText("Voltar ao projeto").assertIsEnabled().performClick()
        assertEquals(1, back)
        assertEquals(0, installs)
    }

    @Test
    fun `erro explica o que houve e a unica acao e a correcao`() {
        show(
            ReformToolsState(
                problem = ReformProblem(
                    "O motor está desligado",
                    "Ligue o motor para instalar as ferramentas.",
                    ReformFix.StartEngine,
                ),
            ),
        )

        compose.onNodeWithText("O motor está desligado", substring = true).assertExists()
        compose.onNodeWithText("Instalar o que falta").assertDoesNotExist()
        compose.assertEveryTapTargetIsAtLeast44dp()
        compose.onNodeWithText("Iniciar o motor").performClick()
        assertEquals(listOf(ReformFix.StartEngine), fixes)
        assertEquals(0, installs)
    }
}
