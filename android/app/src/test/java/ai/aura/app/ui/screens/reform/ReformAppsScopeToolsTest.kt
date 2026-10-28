package ai.aura.app.ui.screens.reform

import ai.aura.app.ui.theme.AuraTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regras puras das telas Reformar 1 (Apps), 2 (Escopo) e 5 (Ferramentas): JVM simples, sem Compose.
 * Cobre UI-40, UI-41 e UI-44 (a parte que não é tela).
 */
class ReformAppsScopeToolsLogicTest {

    @Test
    fun `protegido, idade, potencial, tipo e escopos seguem as regras e nada e inventado sem dado`() {
        val protegidos = listOf(
            ReformApp("com.exemplo.cam", "Câmera de ré"),
            ReformApp("com.syu.camera360", "Visão 360"),
            ReformApp("com.exemplo.avm", "Panorâmica"),
            ReformApp("com.exemplo.x", "Marcha à ré"),
            ReformApp("com.android.settings", "Configurações", system = true),
        )
        protegidos.forEach { assertNotNull(it.label, it.protectedReason) }
        assertEquals("Segurança do carro: o AURA não mexe", protegidos[0].protectedReason)
        assertEquals("App do sistema: o AURA não mexe", protegidos.last().protectedReason)
        listOf(
            ReformApp("com.exemplo.player", "Player de música"),
            ReformApp("com.exemplo.camada", "Camadas"),
            ReformApp("com.exemplo.resumo", "Resumo do dia"),
            ReformApp("com.exemplo.radio", "Rádio FM"),
        ).forEach { assertNull(it.label, it.protectedReason) }

        assertEquals("Muito antigo", ReformApp("a", "A", year = 2016).ageLabel(2026))
        assertEquals("Antigo", ReformApp("a", "A", year = 2017).ageLabel(2026))
        assertNull(ReformApp("a", "A", year = 2024).ageLabel(2026))
        assertNull(ReformApp("a", "A").ageLabel(2026))
        assertEquals(listOf("Alto", "Alto", "Médio", "Baixo"), listOf(0.88f, 0.78f, 0.52f, 0.2f).map(::reformPotentialLabel))
        assertEquals("v2.1 · 2016", ReformApp("a", "A", version = "2.1", year = 2016).details)
        assertEquals("v3", ReformApp("a", "A", version = "v3").details)
        assertNull(ReformApp("a", "A").details)
        assertEquals(ReformAppKind.Music, ReformApp("a", "Player de música").kind)
        assertEquals(ReformAppKind.Navigation, ReformApp("a", "Navegador GPS").kind)
        assertEquals(ReformAppKind.Radio, ReformApp("a", "Rádio FM").kind)
        assertEquals(ReformAppKind.Generic, ReformApp("a", "Agenda").kind)

        assertEquals(listOf("10 min", "25 min", "1 h 30"), ReformScope.entries.map { it.durationLabel })
        assertEquals(listOf("Risco baixo", "Risco médio", "Risco maior"), ReformScope.entries.map { it.risk.label })
        assertEquals(listOf(ReformScope.VisualAndFlows), ReformScope.entries.filter { it.recommended })
        assertEquals(ReformScope.VisualAndFlows, ReformScope.default)
        assertEquals("Cerca de 1 h 30 · usa só a sua assinatura", ReformScope.FullRebuild.estimate)
        assertEquals(listOf("1 h", "1 h 05"), listOf(60, 65).map(::reformDurationLabel))
        assertEquals(90, REFORM_FIDELITY_TARGET_PERCENT)
    }

    @Test
    fun `ferramentas sempre listam as seis exigidas e o botao principal segue o estado`() {
        val vazio = ReformToolsState().rows
        assertEquals(
            listOf("Node.js 22", "JDK 21", "JADX sem tela", "Apktool", "adb", "REA, agentes e servidor MCP"),
            vazio.map { it.label },
        )
        assertTrue(vazio.all { it.status == ReformToolStatus.Unknown && it.chipText == "Não verificado" })
        assertEquals(ReformToolsAction.Install, ReformToolsState().action)

        val rows = ReformToolsState(
            tools = listOf(
                ReformTool("jadx", "JADX sem tela", ReformToolStatus.Installing, percent = 63),
                ReformTool("node", "Node.js 22", ReformToolStatus.Ready),
                ReformTool("extra", "Outra", ReformToolStatus.Queued),
            ),
        ).rows
        assertEquals(7, rows.size)
        assertEquals(ReformToolStatus.Ready, rows[0].status)
        assertEquals(ReformToolStatus.Unknown, rows[1].status)
        assertEquals("Instalando 63%", rows[2].chipText)
        assertEquals("Outra", rows.last().label)

        fun action(vararg s: ReformToolStatus) = ReformToolsState(
            tools = s.mapIndexed { i, st -> ReformTool(REFORM_REQUIRED_TOOLS[i].id, "t$i", st) },
        ).action
        assertEquals(ReformToolsAction.Install, action(ReformToolStatus.Ready, ReformToolStatus.Failed))
        assertEquals(ReformToolsAction.Install, action(ReformToolStatus.Missing))
        assertEquals(ReformToolsAction.Busy, action(ReformToolStatus.Ready, ReformToolStatus.Installing))
        assertEquals(ReformToolsAction.Busy, action(ReformToolStatus.Queued))
        val prontas = REFORM_REQUIRED_TOOLS.map { it.copy(status = ReformToolStatus.Ready) }
        assertEquals(ReformToolsAction.Done, ReformToolsState(tools = prontas).action)
    }
}

/**
 * As três telas e o fluxo, clicando de verdade na JVM (Robolectric), sem emulador e sem motor.
 * Cobre UI-40 (Apps), UI-41 (Escopo), UI-44 (Ferramentas) e, em parte, FN-15 (só a promessa "instala ao lado, como cópia").
 * A altura é grande de propósito: tudo cabe sem rolar, porque o teste clica e não rola.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h1200dp")
class ReformAppsScopeToolsScreensTest {
    @get:Rule val compose = createComposeRule()

    private val player = ReformApp("com.exemplo.player", "Player de música", version = "2.1", year = 2016, potential = 0.88f)
    private val gps = ReformApp("com.exemplo.gps", "Navegador GPS", version = "3.4", year = 2017, potential = 0.78f)
    private val radio = ReformApp("com.exemplo.radio", "Rádio FM", version = "1.8", year = 2015, potential = 0.52f)
    private val rearCamera = ReformApp("com.syu.camera360", "Câmera de ré")
    private val apps = listOf(player, gps, radio, rearCamera)
    private val motorOff = ReformProblem("O motor está desligado", "Ligue o motor para continuar.", ReformFix.StartEngine)
    private val owner = "Sou dono do app ou tenho permissão para modificá-lo."
    private val start = "Travar escopo e começar"

    /** Regra do design e da CONSTRAINTS: todo clicável tem pelo menos 44 dp de alvo de toque. */
    private fun assertTapTargetsAtLeast44dp() {
        val taps = compose.onAllNodes(hasClickAction())
        val count = taps.fetchSemanticsNodes().size
        check(count > 0) { "a tela não tem nenhum alvo de toque" }
        for (i in 0 until count) taps[i].assertHeightIsAtLeast(44.dp).assertWidthIsAtLeast(44.dp)
    }

    @Test
    fun `apps - lista com dados, primeiro marcado, camera de re trancada, escolher e seguir`() {
        var next: ReformApp? = null
        var back = 0
        compose.setContent {
            AuraTheme {
                ReformAppsScreen(
                    ReformAppsState(apps = apps), onBack = { back++ }, onNext = { next = it },
                    onFix = {}, currentYear = 2026,
                )
            }
        }

        compose.onNodeWithText("Multimídia do carro · 4 apps").assertExists()
        compose.onNodeWithText("Passo 1 de 2 · Escolher app").assertExists()
        compose.onNodeWithText("Player de música").assertIsSelected()
            .assertTextContains("v2.1 · 2016").assertTextContains("Muito antigo")
            .assertTextContains("Potencial de melhoria").assertTextContains("Alto")
        compose.onNodeWithText("Navegador GPS").assertIsNotSelected().assertTextContains("Antigo")
        compose.onNodeWithText("Rádio FM").assertTextContains("Médio")
        compose.onNodeWithText("Câmera de ré").assertHasNoClickAction()
            .assertTextContains("Segurança do carro: o AURA não mexe").assertTextContains("Protegido")
        assertTapTargetsAtLeast44dp()

        compose.onNodeWithText("Navegador GPS").performClick()
        compose.onNodeWithText("Navegador GPS").assertIsSelected()
        compose.onNodeWithText("Player de música").assertIsNotSelected()
        compose.onNodeWithText("Escolher escopo").performClick()
        assertEquals(gps, next)
        compose.onNodeWithContentDescription("Voltar para o aparelho").performClick()
        assertEquals(1, back)
    }

    @Test
    fun `apps - vazio, erro e so protegidos mostram o que fazer`() {
        val state = mutableStateOf(ReformAppsState())
        val fixes = mutableListOf<ReformFix>()
        compose.setContent {
            AuraTheme { ReformAppsScreen(state.value, onBack = {}, onNext = {}, onFix = { fixes += it }, currentYear = 2026) }
        }

        compose.onNodeWithText("Nenhum app para mostrar").assertExists()
        compose.onNodeWithText("Escolher escopo").assertDoesNotExist()
        assertTapTargetsAtLeast44dp()
        compose.onNodeWithText("Procurar apps").assertIsEnabled().performClick()

        compose.runOnIdle { state.value = ReformAppsState(apps = apps, problem = motorOff) }
        compose.onNodeWithText("O motor está desligado").assertExists()
        compose.onNodeWithText("Player de música").assertDoesNotExist()
        compose.onNodeWithText("Escolher escopo").assertDoesNotExist()
        assertTapTargetsAtLeast44dp()
        compose.onNodeWithText("Iniciar o motor").performClick()
        assertEquals(listOf(ReformFix.Search, ReformFix.StartEngine), fixes)

        compose.runOnIdle { state.value = ReformAppsState(apps = listOf(rearCamera)) }
        compose.onNodeWithText("Todos os apps desta lista são protegidos. Não há nada para reformar aqui.").assertExists()
        compose.onNodeWithText("Escolher escopo").assertIsNotEnabled()
    }

    @Test
    fun `escopo - recomendado ao abrir, garantias e meta, so comeca com o dono confirmado e entrega o plano`() {
        var plan: ReformPlan? = null
        var back = 0
        compose.setContent { AuraTheme { ReformScopeScreen(gps, onBack = { back++ }, onStart = { plan = it }) } }

        compose.onNodeWithText("Escopo da reforma").assertExists()
        compose.onNodeWithText("Navegador GPS").assertExists()
        compose.onNodeWithText("Só visual").assertIsNotSelected().assertTextContains("10 min").assertTextContains("Risco baixo")
        compose.onNodeWithText("Visual + fluxos").assertIsSelected()
            .assertTextContains("Recomendado").assertTextContains("25 min").assertTextContains("Risco médio")
        compose.onNodeWithText("Reconstrução completa").assertIsNotSelected()
            .assertTextContains("1 h 30").assertTextContains("Risco maior")
        listOf("O app original não é apagado", "O novo instala ao lado, como cópia", "Voltar ao original com 1 toque")
            .forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithContentDescription("Meta de 90 por cento").assertExists()
        compose.onNodeWithText("90%").assertExists()
        compose.onNodeWithText("Cerca de 25 min · usa só a sua assinatura").assertExists()
        assertTapTargetsAtLeast44dp()

        compose.onNodeWithText(start).assertIsNotEnabled()
        compose.onNodeWithText(owner).assertIsOff().performClick()
        compose.onNodeWithText(owner).assertIsOn()
        compose.onNodeWithText("Reconstrução completa").performClick()
        compose.onNodeWithText("Reconstrução completa").assertIsSelected()
        compose.onNodeWithText("Visual + fluxos").assertIsNotSelected()
        compose.onNodeWithText("Cerca de 1 h 30 · usa só a sua assinatura").assertExists()
        compose.onNodeWithContentDescription("Modo carro").assertIsOn().performClick()
        compose.onNodeWithContentDescription("Modo carro").assertIsOff()

        assertNull(plan)
        compose.onNodeWithText(start).assertIsEnabled().performClick()
        assertEquals(
            ReformPlan(
                "com.exemplo.gps", "Navegador GPS", ReformScope.FullRebuild,
                carMode = false, fidelityTargetPercent = 90, ownerConfirmed = true,
            ),
            plan,
        )
        compose.onNodeWithContentDescription("Voltar para a escolha do app").performClick()
        assertEquals(1, back)
    }

    @Test
    fun `escopo - sem app ou com app protegido explica e volta`() {
        val app = mutableStateOf<ReformApp?>(null)
        var back = 0
        compose.setContent { AuraTheme { ReformScopeScreen(app.value, onBack = { back++ }, onStart = {}) } }

        compose.onNodeWithText("Nenhum app escolhido").assertExists()
        compose.onNodeWithText(start).assertDoesNotExist()
        assertTapTargetsAtLeast44dp()
        compose.onNodeWithText("Escolher app").performClick()

        compose.runOnIdle { app.value = rearCamera }
        compose.onNodeWithText("Este app é protegido").assertExists()
        compose.onNodeWithText(start).assertDoesNotExist()
        compose.onNodeWithText("Escolher outro app").performClick()
        assertEquals(2, back)
    }

    @Test
    fun `ferramentas - estado de cada uma, progresso, aviso, sessao salva e retomar`() {
        var back = 0
        var resumes = 0
        val tools = REFORM_REQUIRED_TOOLS.map {
            when (it.id) {
                "jadx" -> it.copy(status = ReformToolStatus.Installing, percent = 63)
                "apktool", "rea" -> it.copy(status = ReformToolStatus.Queued)
                else -> it.copy(status = ReformToolStatus.Ready)
            }
        }
        val session = listOf(
            ReformSessionEntry("14:02", "Node.js e JDK prontos"),
            ReformSessionEntry("14:09", "adb pareado com a multimídia"),
            ReformSessionEntry("14:21", "JADX baixado, instalando", active = true),
        )
        compose.setContent {
            AuraTheme {
                ReformToolsScreen(
                    ReformToolsState(tools, session), onBack = { back++ }, onInstall = {},
                    onResume = { resumes++ }, onFix = {},
                )
            }
        }

        compose.onNodeWithText("Ferramentas da reforma").assertExists()
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
        assertTapTargetsAtLeast44dp()

        compose.onNodeWithText("Retomar do último").performClick()
        assertEquals(1, resumes)
        compose.onNodeWithContentDescription("Voltar ao projeto").performClick()
        assertEquals(1, back)
    }

    @Test
    fun `ferramentas - vazio oferece instalar, tudo pronto volta ao projeto, erro mostra a correcao`() {
        val state = mutableStateOf(ReformToolsState())
        var back = 0
        var installs = 0
        val fixes = mutableListOf<ReformFix>()
        compose.setContent {
            AuraTheme {
                ReformToolsScreen(
                    state.value, onBack = { back++ }, onInstall = { installs++ }, onResume = {}, onFix = { fixes += it },
                )
            }
        }

        REFORM_REQUIRED_TOOLS.forEach { compose.onNodeWithContentDescription("${it.label}: Não verificado").assertExists() }
        compose.onNodeWithText("Ainda não há sessão salva", substring = true).assertExists()
        compose.onNodeWithText("Retomar do último").assertDoesNotExist()
        assertTapTargetsAtLeast44dp()
        compose.onNodeWithText("Instalar o que falta").assertIsEnabled().performClick()
        assertEquals(1, installs)

        compose.runOnIdle {
            state.value = ReformToolsState(tools = REFORM_REQUIRED_TOOLS.map { it.copy(status = ReformToolStatus.Ready) })
        }
        compose.onNodeWithText("Instalar o que falta").assertDoesNotExist()
        compose.onNodeWithText("Voltar ao projeto").assertIsEnabled().performClick()
        assertEquals(1, back)

        compose.runOnIdle { state.value = ReformToolsState(problem = motorOff) }
        compose.onNodeWithText("O motor está desligado", substring = true).assertExists()
        compose.onNodeWithText("Instalar o que falta").assertDoesNotExist()
        assertTapTargetsAtLeast44dp()
        compose.onNodeWithText("Iniciar o motor").performClick()
        assertEquals(listOf(ReformFix.StartEngine), fixes)
        assertEquals(1, installs)
    }

    @Test
    fun `fluxo - apps, escopo e plano, depois apps, ferramentas e volta`() {
        var plan: ReformPlan? = null
        val step = mutableStateOf(0) // 0 apps, 1 escopo, 2 ferramentas (a rota de verdade é do controlador)
        val chosen = mutableStateOf<ReformApp?>(null)
        val problem = mutableStateOf<ReformProblem?>(null)
        compose.setContent {
            AuraTheme {
                when (step.value) {
                    0 -> ReformAppsScreen(
                        ReformAppsState(apps = apps, problem = problem.value), onBack = {},
                        onNext = { chosen.value = it; step.value = 1 },
                        onFix = { if (it == ReformFix.OpenTools) step.value = 2 }, currentYear = 2026,
                    )
                    1 -> ReformScopeScreen(chosen.value, onBack = { step.value = 0 }, onStart = { plan = it })
                    else -> ReformToolsScreen(
                        ReformToolsState(), onBack = { step.value = 0 }, onInstall = {}, onResume = {}, onFix = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Navegador GPS").performClick()
        compose.onNodeWithText("Escolher escopo").performClick()
        compose.onNodeWithText("Escopo da reforma").assertExists()
        compose.onNodeWithText("Reformar apps").assertDoesNotExist()
        compose.onNodeWithContentDescription("Voltar para a escolha do app").performClick()
        compose.onNodeWithText("Reformar apps").assertExists()

        compose.onNodeWithText("Navegador GPS").performClick()
        compose.onNodeWithText("Escolher escopo").performClick()
        compose.onNodeWithText(owner).performClick()
        compose.onNodeWithText("Só visual").performClick()
        compose.onNodeWithText(start).performClick()
        assertEquals(ReformPlan("com.exemplo.gps", "Navegador GPS", ReformScope.VisualOnly, true, 90, true), plan)

        compose.onNodeWithContentDescription("Voltar para a escolha do app").performClick()
        compose.runOnIdle {
            problem.value = ReformProblem(
                "Faltam ferramentas", "Instale o adb e o JADX antes de procurar os apps.", ReformFix.OpenTools,
            )
        }
        compose.onNodeWithText("Instalar ferramentas").performClick()
        compose.onNodeWithText("Ferramentas da reforma").assertExists()
        compose.onNodeWithContentDescription("Voltar ao projeto").performClick()
        compose.onNodeWithText("Reformar apps").assertExists()
    }
}
