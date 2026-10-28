package ai.aura.app.ui.screens.voice

import ai.aura.app.ui.screens.home.AuraOrb
import ai.aura.app.ui.screens.home.OrbState
import ai.aura.app.ui.screens.projects.ProjectIdeaScreen
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.voice.LocalVoiceInput
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
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

/** Reconhecedor falso: guarda quem escuta e deixa o teste "falar". */
private class FakeRecognizer(override val available: Boolean = true) : SpeechRecognizerPort {
    var starts = 0
    var listener: SpeechRecognizerPort.Listener? = null
    override fun start(listener: SpeechRecognizerPort.Listener) {
        starts++
        this.listener = listener
    }
    override fun stop() = Unit
}

/** Uma classe, ~8 testes: cada tela de voz (Voz-1 a Voz-5), o orbe e o "Falar a ideia" de Projetos. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
class VoiceScreensTest {
    @get:Rule val compose = createComposeRule()

    private fun voiceScreen(rec: FakeRecognizer, micGranted: Boolean = true, settings: VoiceSettings = VoiceSettings(), calls: MutableList<String>) {
        compose.setContent {
            AuraTheme {
                VoiceScreen(
                    recognizer = rec, micGranted = micGranted, settings = settings,
                    onNeedMicrophone = { calls += "mic" }, onKeyboard = { calls += "kb:$it" },
                    onSend = { calls += "send:$it" }, onCancel = { calls += "cancel" }, onMinimize = { calls += "min" },
                )
            }
        }
    }

    // UI-08 Voz-1 · FN-01 (voz só abre por toque: ouve ao abrir, nunca antes)
    @Test fun ouvindo_mostra_a_fala_ao_vivo_e_os_tres_botoes_funcionam() {
        val rec = FakeRecognizer()
        val calls = mutableListOf<String>()
        voiceScreen(rec, calls = calls)
        compose.onNodeWithText("Ouvindo").assertExists()
        assertEquals(1, rec.starts)
        compose.onNodeWithText("Enviar").assertIsNotEnabled() // nada dito ainda
        compose.runOnIdle { rec.listener!!.onPartial("abre o aplicativo") }
        compose.onNodeWithText("abre o aplicativo", substring = true).assertExists()
        compose.onNodeWithText("Teclado").performClick()
        compose.onNodeWithText("Enviar").performClick()
        compose.onNodeWithText("Cancelar").performClick()
        compose.onNodeWithContentDescription("Minimizar voz").performClick()
        assertEquals(listOf("kb:abre o aplicativo", "send:abre o aplicativo", "cancel", "min"), calls)
    }

    // UI-08/FN-01: sem microfone ou sem reconhecedor = mensagem e UMA ação
    @Test fun sem_microfone_ou_sem_reconhecedor_da_mensagem_e_uma_acao() {
        val rec = FakeRecognizer()
        val noRecognizer = FakeRecognizer(available = false)
        val calls = mutableListOf<String>()
        var granted by mutableStateOf(false)
        var current by mutableStateOf<SpeechRecognizerPort>(rec)
        compose.setContent {
            AuraTheme {
                VoiceScreen(
                    recognizer = current, micGranted = granted, settings = VoiceSettings(),
                    onNeedMicrophone = { calls += "mic" }, onKeyboard = { calls += "kb:$it" },
                    onSend = { calls += "send:$it" }, onCancel = { calls += "cancel" }, onMinimize = { calls += "min" },
                )
            }
        }
        val onlyAction = hasClickAction() and !hasContentDescription("Minimizar voz")
        compose.onNodeWithText("Preciso do microfone").assertExists()
        compose.onAllNodes(onlyAction).assertCountEquals(1)
        compose.onNodeWithText("Permitir microfone").performClick()
        assertEquals(0, rec.starts) // sem permissão nem tenta ouvir
        granted = true
        compose.onNodeWithText("Ouvindo").assertExists()
        assertEquals(1, rec.starts)
        current = noRecognizer
        compose.onNodeWithText("Este aparelho não entende voz").assertExists()
        compose.onAllNodes(onlyAction).assertCountEquals(1)
        compose.onNodeWithText("Escrever o pedido").performClick()
        assertEquals(listOf("mic", "kb:"), calls)
    }

    // UI-09 Voz-2 · FN-02 (volta sozinha em 3 s; modo Conversa envia sozinho após 1,5 s de silêncio)
    @Test fun interrompida_guarda_o_texto_volta_sozinha_em_3s_e_conversa_envia_sozinha() {
        val rec = FakeRecognizer()
        val calls = mutableListOf<String>()
        voiceScreen(rec, settings = VoiceSettings(mode = VoiceMode.Conversation), calls = calls)
        compose.runOnIdle {
            rec.listener!!.onPartial("pega o aplicativo")
            rec.listener!!.onInterrupted("A ligação terminou")
        }
        compose.onNodeWithText("Voltando a ouvir…").assertExists()
        compose.onNodeWithText("A ligação terminou").assertExists()
        compose.onNodeWithText("pega o aplicativo", substring = true).assertExists()
        compose.onNodeWithText("Guardado. Nada se perde.").assertExists()
        compose.onNodeWithText("Volto sozinho em 3 s").assertExists()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("Volto sozinho em 2 s").assertExists()
        compose.mainClock.advanceTimeBy(2000)
        compose.onNodeWithText("Ouvindo").assertExists()
        assertEquals(2, rec.starts)
        compose.runOnIdle { rec.listener!!.onFinal("oi") }
        compose.mainClock.advanceTimeBy(1500)
        assertEquals(listOf("send:pega o aplicativo oi"), calls)
    }

    // UI-14 Voz-3 · FN-01 (voz compacta: fila POR VOZ, ouvir não interrompe)
    @Test fun compacta_mostra_por_voz_e_as_acoes_da_barra() {
        var expanded = 0
        var stopped = 0
        compose.setContent {
            AuraTheme {
                VoiceCompact(
                    heard = "e depois avisa quando terminar",
                    queue = listOf("Depois instala também na TV da sala"),
                    onExpand = { expanded++ }, onStop = { stopped++ },
                )
            }
        }
        compose.onNodeWithText("POR VOZ").assertExists()
        compose.onNodeWithText("Depois instala também na TV da sala").assertExists()
        compose.onNodeWithText("Na fila").assertExists()
        compose.onNodeWithText("Ouvindo").assertExists()
        compose.onNodeWithText("“…depois avisa quando terminar”").assertExists()
        compose.onNodeWithText("Ouvir não interrompe o trabalho.").assertExists()
        compose.onNodeWithContentDescription("Abrir voz em tela cheia").performClick()
        compose.onNodeWithContentDescription("Parar de ouvir").performClick()
        assertEquals(1 to 1, expanded to stopped)
    }

    // UI-15 Voz-4 · FN-04 (parte da tela: recomenda, aplica em 5 s, Mudar para, interromper só com Confirmar)
    @Test fun triagem_recomenda_fazer_depois_aplica_em_5s_e_so_interrompe_com_confirmar() {
        val applied = mutableListOf<TriageChoice>()
        var opened = 0
        var state by mutableStateOf<TriageUiState?>(
            TriageUiState("Instala também na TV da sala", "Corrigir tela de login", 3, 4, queued = listOf("Instalar na TV da sala")),
        )
        compose.setContent { AuraTheme { TriageScreen(state, { applied += it }, { }, { opened++ }) } }
        compose.onNodeWithText("Fazer depois do atual").assertIsSelected()
        compose.onNodeWithText("Recomendado").assertExists()
        compose.onNodeWithText("Rodando · Corrigir tela de login").assertExists()
        compose.onNodeWithText("Na fila · Instalar na TV da sala").assertExists()
        compose.onNodeWithText("Salvo para retomar · nenhum").assertExists()
        compose.onNodeWithText("Entra na fila em 5 s").assertExists()
        compose.mainClock.advanceTimeBy(5000)
        assertEquals(listOf(TriageChoice.AfterCurrent), applied)
        state = state!!.copy(heard = "Outro pedido") // novo pedido, nova contagem
        compose.onNodeWithText("Entra na fila em 5 s").assertExists()
        compose.onNodeWithText("Mudar").performClick()
        compose.onNodeWithText("Entra na fila em 5 s").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(10_000)
        assertEquals(1, applied.size) // Mudar parou a contagem
        compose.onNodeWithText("Parar o atual e fazer agora").performClick()
        compose.onNodeWithText("O atual fica salvo e pode ser retomado.").assertExists()
        assertEquals(1, applied.size) // escolher não interrompe; só Confirmar
        compose.onNodeWithText("Confirmar").performClick()
        assertEquals(listOf(TriageChoice.AfterCurrent, TriageChoice.StopAndDoNow), applied)
        state = null // sem pedido: mensagem e UMA ação
        compose.onNodeWithText("Falar um pedido").performClick()
        assertEquals(1, opened)
    }

    // UI-16 Voz-5 · FN-02 (modos e 4 interruptores)
    @Test fun modos_escolhe_conversa_liga_interruptor_e_salva() {
        var saved: VoiceSettings? = null
        var back = 0
        compose.setContent { AuraTheme { VoiceModesScreen(VoiceSettings(), { saved = it }, { back++ }) } }
        compose.onNodeWithText("Apertar e falar").assertIsSelected()
        compose.onNodeWithText("Padrão").assertExists()
        compose.onNodeWithText("Conversa").performClick()
        compose.onNodeWithText("Conversa").assertIsSelected()
        compose.onNodeWithContentDescription("Chamar pelo nome AURA").assertIsOff().performClick()
        compose.onNodeWithContentDescription("Chamar pelo nome AURA").assertIsOn()
        compose.onNodeWithContentDescription("Enviar sozinho quando eu parar de falar").assertIsOn()
        compose.onNodeWithContentDescription("Voltar a ouvir sozinho depois de uma interrupção").assertIsOn()
        compose.onNodeWithContentDescription("Ouvir com a tela apagada").assertIsOn()
        compose.onNodeWithText("Salvar").performClick()
        assertEquals(VoiceSettings(mode = VoiceMode.Conversation, wakeName = true), saved)
        assertEquals(1, back)
    }

    // Orbe: Parado, Ouvindo, Pensando, Erro (Parado segue decorativo, como antes)
    @Test fun orbe_tem_quatro_estados() {
        compose.setContent { AuraTheme { Row { OrbState.entries.forEach { AuraOrb(Modifier.size(48.dp), state = it) } } } }
        assertEquals(listOf("Parado", "Ouvindo", "Pensando", "Erro"), OrbState.entries.map { it.label })
        for (label in listOf("Ouvindo", "Pensando", "Erro")) {
            compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, label)).assertExists()
        }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription)).assertCountEquals(3)
    }

    // UI-12 Projeto-Ideia: onde há reconhecedor "Falar a ideia" fala; onde não há, mensagem e UMA ação
    @Test fun falar_a_ideia_usa_o_reconhecedor_ou_oferece_escrever() {
        var hook by mutableStateOf<(((String) -> Unit) -> Unit)?>(null)
        var idea by mutableStateOf("")
        compose.setContent {
            AuraTheme {
                CompositionLocalProvider(LocalVoiceInput provides hook) {
                    ProjectIdeaScreen(idea, { idea = it }, { }, { })
                }
            }
        }
        compose.onNodeWithContentDescription("Falar a ideia").performClick()
        compose.onNodeWithText("Falar ainda não está disponível neste aparelho.").assertExists()
        compose.onNodeWithText("Escrever a ideia").performClick()
        compose.onNodeWithContentDescription("Sua ideia de aplicativo").assertIsFocused()
        compose.onNodeWithText("Falar ainda não está disponível neste aparelho.").assertDoesNotExist()
        hook = { onSpoken -> onSpoken("um app de gastos") }
        compose.onNodeWithContentDescription("Falar a ideia").performClick()
        assertEquals("um app de gastos", idea)
        compose.onNodeWithText("Falar ainda não está disponível neste aparelho.").assertDoesNotExist()
    }
}
