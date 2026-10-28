package ai.aura.app.ui.screens.reform

import ai.aura.app.ui.components.meterSpoken
import ai.aura.app.ui.engine.EngineEvent
import ai.aura.app.ui.theme.AuraTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val AGORA = 10_000_000L
private const val PLANO = """[{"title":"Pegar o APK do aparelho","proof":"arquivo copiado"},
{"title":"Inspecionar com JADX","proof":"classes lidas"},
{"title":"Mapear telas e fluxos","proof":"mapa de telas salvo"},
{"title":"Extrair ícones e textos","proof":"lista de ícones e textos salva"}]"""

private fun ev(id: Long, type: String, vararg p: Pair<String, String>, at: Long = AGORA - 600_000 + id * 1_000) =
    EngineEvent(id, type, "job-1", p.toMap(), at)

/** Reforma rodando: 2 passos feitos, o 3º a 64%, checkpoint há 2 min, fidelidade ainda não medida. */
private fun rodando() = reformProgressFrom(
    listOf(
        ev(1, "reform.scope.locked", "app" to "Player de música", "mode" to "Visual + fluxos", "fidelityGoal" to "90"),
        ev(2, "plan.proposed", "steps" to PLANO),
        ev(3, "step.started"), ev(4, "step.done", "proof" to "arquivo player-musica.apk copiado, 6,2 MB"),
        ev(5, "step.started"), ev(6, "step.done", "proof" to "1.284 classes lidas, 0 erros"),
        ev(7, "step.started"), ev(8, "step.progress", "pct" to "64", "logTail" to "linhas do log"),
        ev(
            9, "reform.progress", "reversalPct" to "68", "rebuildPct" to "35", "fidelityPct" to "null",
            "checkpointId" to "cp-3", at = AGORA - 120_000,
        ),
    ),
)

/** Lógica pura (sem Robolectric): texto do medidor e leitura dos eventos do contrato. */
class ReformProgressLogicTest {
    @Test fun `medidor corta o valor em 0 a 100 e fala nome, valor e legenda para o leitor de tela`() {
        assertEquals(
            "Reversão: 68 por cento, quanto do app foi entendido",
            meterSpoken("Reversão", 68, "quanto do app foi entendido"),
        )
        assertEquals("Reconstrução: 100 por cento", meterSpoken("Reconstrução", 140))
        assertEquals(
            "Fidelidade: ainda não medida, medida no teste real",
            meterSpoken("Fidelidade", null, "medida no teste real"),
        )
        assertEquals("Reversão: o motor ainda não informa", meterSpoken("Reversão", null, unknown = "O motor ainda não informa"))
    }

    @Test fun `eventos viram medidores, passos com prova e checkpoint e fidelidade null segue nao medida`() {
        val p = rodando()
        assertTrue(p.locked)
        assertEquals("Player de música", p.app)
        assertEquals("Visual + fluxos", p.mode)
        assertEquals(68, p.reversalPct)
        assertEquals(35, p.rebuildPct)
        assertNull(p.fidelityPct)
        assertEquals(AGORA - 120_000, p.checkpointAtMillis)
        assertEquals(
            listOf(ReformProgressStepState.Done, ReformProgressStepState.Done, ReformProgressStepState.Running, ReformProgressStepState.Pending),
            p.steps.map { it.state },
        )
        assertEquals("arquivo player-musica.apk copiado, 6,2 MB", p.steps[0].proof)
        assertEquals(64, p.steps[2].pct)
        assertEquals("mapa de telas salvo", p.steps[2].promisedProof)
        assertNull(p.steps[3].proof)
    }

    @Test fun `sem evento nada e inventado, numero ruim vira nao informado e pausa so vem de interrupcao`() {
        val vazio = reformProgressFrom(emptyList())
        assertFalse(vazio.started)
        assertNull(vazio.reversalPct)
        assertTrue(vazio.steps.isEmpty())

        fun lido(v: String) = reformProgressFrom(listOf(ev(1, "reform.progress", "reversalPct" to v))).reversalPct
        assertNull(lido("abc"))
        assertNull(lido("-3"))
        assertNull(lido("101"))
        assertEquals(73, lido("72.6"))

        val ate = listOf(ev(1, "plan.proposed", "steps" to PLANO), ev(2, "step.started"))
        val pausada = reformProgressFrom(ate + ev(3, "task.interrupted", "checkpointId" to "cp-9", "resumable" to "true"))
        assertTrue(pausada.paused)
        assertFalse(reformProgressFrom(ate + ev(3, "task.interrupted") + ev(4, "step.progress", "pct" to "10")).paused)

        val semProva = reformProgressFrom(ate + ev(3, "step.done"))
        assertEquals(ReformProgressStepState.Done, semProva.steps[0].state)
        assertNull(semProva.steps[0].proof)
    }
}

/** Telas na JVM (Robolectric): o que aparece, o que o leitor de tela lê e se cada botão chama a sua ação. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
class ReformProgressScreensTest {
    @get:Rule val compose = createComposeRule()
    private val cliques = mutableListOf<String>()
    private fun on(nome: String): () -> Unit = { cliques += nome }

    private fun faixa(atual: Float) = SemanticsMatcher.expectValue(
        SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(atual, 0f..100f),
    )

    @Test fun `reversao mostra medidores, passos com prova e checkpoint e chama as acoes`() {
        var p by mutableStateOf(rodando())
        compose.setContent {
            AuraTheme {
                ReformReversionScreen(
                    progress = p, nowMillis = AGORA, onBack = on("voltar"), onPause = on("pausar"), onResume = on("retomar"),
                    onOpenBackstage = on("bastidores"), onOpenProject = on("projeto"), onChooseApp = on("escolher"), onRetry = on("tentar"),
                )
            }
        }
        compose.onNodeWithText("Reformando Player de música").assertIsDisplayed()
        compose.onNodeWithText("Escopo travado · Visual + fluxos").assertIsDisplayed()
        compose.onNodeWithContentDescription("Reversão: 68 por cento, quanto do app foi entendido").assert(faixa(68f))
        compose.onNodeWithContentDescription("Reconstrução: 35 por cento, quanto da tela nova está pronta").assert(faixa(35f))
        compose.onNodeWithContentDescription("Fidelidade: ainda não medida, medida no teste real").assertExists()
        compose.onNodeWithText("Prova: arquivo player-musica.apk copiado, 6,2 MB").assertExists()
        compose.onNodeWithText("Prova: lista de ícones e textos salva").assertExists()
        compose.onNode(hasText("3 · Mapear telas e fluxos"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Em andamento"))
        compose.onNodeWithText("Checkpoint salvo há 2 min. Se o Android pausar, eu retomo daqui.").assertExists()

        compose.onNodeWithContentDescription("Voltar para o escopo").assertWidthIsAtLeast(44.dp).performClick()
        compose.onNodeWithText("Pausar próximas etapas").assertHeightIsAtLeast(44.dp).performClick()
        compose.onNodeWithText("A etapa já iniciada pode continuar no motor.").assertExists()
        compose.onNodeWithText("Ver bastidores").assertHeightIsAtLeast(44.dp).performClick()
        compose.onNodeWithText("Ver o projeto").assertHeightIsAtLeast(44.dp).performClick()

        compose.runOnIdle { p = p.copy(paused = true) }
        compose.onNodeWithText("Pausar próximas etapas").assertDoesNotExist()
        // Pausar só impede etapas novas: o texto não finge que o motor parou.
        compose.onNodeWithText("Próximas etapas pausadas. A etapa que já começou pode continuar no motor.").assertExists()
        compose.onNodeWithText("Retomar etapas").performClick()
        assertEquals(listOf("voltar", "pausar", "bastidores", "projeto", "retomar"), cliques)
    }

    @Test fun `depois da analise o limite atual e dito e o escopo segue no chat sem prometer APK`() {
        var p by mutableStateOf(rodando().copy(analysisDone = true))
        compose.setContent {
            AuraTheme {
                ReformReversionScreen(
                    progress = p, nowMillis = AGORA, onBack = on("voltar"), onPause = on("pausar"), onResume = on("retomar"),
                    onOpenBackstage = on("bastidores"), onOpenProject = on("projeto"), onChooseApp = on("escolher"), onRetry = on("tentar"),
                    onContinueInChat = on("chat"),
                )
            }
        }
        compose.onNodeWithText("Análise concluída. A reconstrução ainda não está disponível neste fluxo.").assertIsDisplayed()
        compose.onNodeWithText("Continuar no chat").assertHeightIsAtLeast(44.dp).performClick()
        compose.onNodeWithText("Ver o projeto").performClick()
        assertEquals(listOf("chat", "projeto"), cliques)

        // Etapa travada por falta de decisão sobre instalar: aviso com a ação, sem fingir erro.
        compose.runOnIdle { p = p.copy(analysisDone = false, blocked = "Faltam as ferramentas de análise. Nada foi instalado; toque em Retomar etapas para ver o pedido.") }
        compose.onNodeWithText("Faltam as ferramentas de análise. Nada foi instalado; toque em Retomar etapas para ver o pedido.").assertExists()
        compose.onNodeWithText("Continuar no chat").assertDoesNotExist()
    }

    @Test fun `fidelidade sem teste diz ainda nao medida e depois do teste mostra numero e comparador`() {
        var p by mutableStateOf(rodando())
        val detalhes = ReformFidelityDetails(
            visualPct = 94, flowPct = 88, functionsPct = 91, flowsPassed = 9, flowsTotal = 11,
            shots = listOf(ReformFidelityShot("Tocando agora"), ReformFidelityShot("Lista")),
            differences = listOf(ReformFidelityDiff("Capa do álbum maior"), ReformFidelityDiff("Equalizador ainda não refeito", missing = true)),
        )
        compose.setContent {
            AuraTheme {
                ReformFidelityScreen(
                    progress = p, details = detalhes, onBack = on("voltar"), onInstall = on("instalar"),
                    onFixDifferences = on("corrigir"), onChooseApp = on("escolher"), onRetry = on("tentar"),
                )
            }
        }
        compose.onNodeWithText("Ainda não medida").assertIsDisplayed()
        compose.onNodeWithText("Ainda não foi testado neste aparelho.").assertIsDisplayed()
        compose.onNodeWithText("Instalar ao lado do original").assertDoesNotExist()
        compose.onNodeWithText("Testar no aparelho").assertDoesNotExist() // "Testar" que só relê o motor não existe mais
        compose.onNodeWithText("Ver como testar").assertHeightIsAtLeast(44.dp).performClick()
        compose.onNodeWithText("O AURA ainda não executa esse teste sozinho", substring = true).assertExists()
        assertEquals(emptyList<String>(), cliques) // explicar o teste não chama o motor nem muda nada

        compose.runOnIdle { p = p.copy(fidelityPct = 91) }
        compose.onNodeWithContentDescription("Fidelidade: 91 por cento, medida no teste real no aparelho").assert(faixa(91f))
        compose.onNodeWithContentDescription("Visual: 94 por cento").assertExists()
        compose.onNodeWithText("Teste automático: 9 de 11 fluxos passaram").assertExists()
        compose.onNodeWithText("Equalizador ainda não refeito").assertExists()
        compose.onNodeWithText("Original").assertExists()
        compose.onNodeWithText("Reformado").assertExists()
        compose.onNodeWithText("Tocando agora").assertIsSelected()
        compose.onNodeWithText("Lista").performClick()
        compose.onNodeWithText("Lista").assertIsSelected()
        compose.onNodeWithText("Instalar ao lado do original").performClick()
        compose.onNodeWithText("Corrigir diferenças").performClick()
        assertEquals(listOf("instalar", "corrigir"), cliques)
    }

    @Test fun `projeto mostra medidores, artefatos e pecas e chama as acoes`() {
        val artefatos = listOf(
            ReformProjectArtifact("a1", "Relatório da engenharia reversa", "PDF"),
            ReformProjectArtifact("a3", "APK reformado", "Em andamento", badge = "Gerando"),
        )
        compose.setContent {
            AuraTheme {
                ReformProjectScreen(
                    progress = rodando(), artifacts = artefatos, pieces = "12 ícones · paleta · tipografia",
                    piecesUsedBy = "Usadas também em Navegador GPS", style = "AURA Premium · modo carro",
                    onBack = on("voltar"), onOpenFidelity = on("fidelidade"), onOpenArtifact = { cliques += "artefato:$it" },
                    onChangeStyle = on("estilo"), onOpenTools = on("ferramentas"), onContinue = on("continuar"),
                    onChooseApp = on("escolher"), onRetry = on("tentar"),
                )
            }
        }
        compose.onNodeWithText("Player de música · Reforma").assertIsDisplayed()
        compose.onNodeWithText("Rodando").assertExists()
        compose.onNodeWithContentDescription("Reversão: 68 por cento").assert(faixa(68f))
        compose.onNodeWithText("12 ícones · paleta · tipografia").assertExists()
        compose.onNodeWithText("Gerando").assertExists()

        compose.onNodeWithContentDescription("Fidelidade: ainda não medida").performClick()
        compose.onNodeWithText("Relatório da engenharia reversa").performScrollTo().performClick()
        compose.onNodeWithText("Mudar estilo").performScrollTo().assertHeightIsAtLeast(44.dp).performClick()
        compose.onNodeWithText("Ferramentas").assertHeightIsAtLeast(44.dp).performClick()
        compose.onNodeWithText("Continuar reforma").performClick()
        assertEquals(listOf("fidelidade", "artefato:a1", "estilo", "ferramentas", "continuar"), cliques)
    }

    @Test fun `vazio e erro das tres telas tem uma acao so e nenhum botao mudo`() {
        var p by mutableStateOf(ReformProgress())
        var tela by mutableStateOf(0)
        compose.setContent {
            AuraTheme {
                when (tela) {
                    0 -> ReformReversionScreen(
                        progress = p, nowMillis = AGORA, onBack = on("voltar"), onPause = on("pausar"), onResume = on("retomar"),
                        onOpenBackstage = on("bastidores"), onOpenProject = on("projeto"), onChooseApp = on("escolher"), onRetry = on("tentar"),
                    )
                    1 -> ReformFidelityScreen(
                        progress = p, details = ReformFidelityDetails(), onBack = on("voltar"),
                        onInstall = on("instalar"), onFixDifferences = on("corrigir"), onChooseApp = on("escolher"), onRetry = on("tentar"),
                    )
                    else -> ReformProjectScreen(
                        progress = p, artifacts = emptyList(), pieces = null, piecesUsedBy = null, style = null,
                        onBack = on("voltar"), onOpenFidelity = on("fidelidade"), onOpenArtifact = { cliques += "artefato" },
                        onChangeStyle = on("estilo"), onOpenTools = on("ferramentas"), onContinue = on("continuar"),
                        onChooseApp = on("escolher"), onRetry = on("tentar"),
                    )
                }
            }
        }
        for (t in 0..2) {
            compose.runOnIdle { tela = t; p = ReformProgress() }
            compose.onNodeWithText("Escolher um app").performClick()
            compose.runOnIdle { p = ReformProgress(error = "O motor parou. O que foi feito está salvo.") }
            compose.onNodeWithText("Escolher um app").assertDoesNotExist()
            // O aviso junta os filhos num nó só; o toque tem de cair no botão, então se procura na árvore sem junção.
            compose.onNodeWithText("Tentar de novo", useUnmergedTree = true).performClick()
        }
        assertEquals(List(3) { listOf("escolher", "tentar") }.flatten(), cliques)
    }
}
