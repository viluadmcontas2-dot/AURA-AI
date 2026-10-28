package ai.aura.app.ui.screens.voice

import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraSwitch
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

private class ModeOption(val mode: VoiceMode, val title: String, val note: String, val badge: String? = null, val hint: String? = null)

private val ModeOptions = listOf(
    ModeOption(VoiceMode.PushToTalk, "Apertar e falar", "Só ouve enquanto você aperta.", badge = "Padrão"),
    ModeOption(VoiceMode.Conversation, "Conversa", "Ouve sem parar e envia sozinho.", hint = "Recomendado para o dia a dia"),
    ModeOption(VoiceMode.HandsFree, "Mãos livres no carro", "Como Conversa, e pode ouvir o nome AURA."),
)

/**
 * Como o microfone funciona (Voz-5-Modos): 3 modos e 4 interruptores. Nada muda até tocar em "Salvar".
 * [onSave] recebe as escolhas; depois a tela chama [onBack].
 */
@Composable
fun VoiceModesScreen(
    settings: VoiceSettings,
    onSave: (VoiceSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf(settings) }
    Column(modifier.fillMaxSize().background(AuraColors.Bg)) {
        VoiceHeader("Como o microfone funciona", onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModeOptions.forEach { option ->
                ModeRow(option, selected = draft.mode == option.mode, onSelect = { draft = draft.copy(mode = option.mode) })
            }
            Column(
                Modifier.fillMaxWidth().padding(top = 8.dp).clip(AuraShapes.Card).background(AuraColors.Surface)
                    .border(1.dp, AuraColors.Line, AuraShapes.Card).padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                SwitchRow(
                    "Enviar sozinho quando eu parar de falar", "Espera cerca de 1,5 s de silêncio.",
                    draft.autoSend,
                ) { draft = draft.copy(autoSend = it) }
                SwitchRow(
                    "Voltar a ouvir sozinho depois de uma interrupção", "Ligação, outro app ou Bluetooth.",
                    draft.autoResume,
                ) { draft = draft.copy(autoResume = it) }
                SwitchRow(
                    "Ouvir com a tela apagada", "Mostra a notificação AURA ouvindo. Você pode pausar nela.",
                    draft.screenOff,
                ) { draft = draft.copy(screenOff = it) }
                SwitchRow(
                    "Chamar pelo nome AURA", "Fica ouvindo só no aparelho. Gasta mais bateria.",
                    draft.wakeName,
                ) { draft = draft.copy(wakeName = it) }
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            AuraButtonPrimary("Salvar", { onSave(draft); onBack() }, Modifier.fillMaxWidth(), large = true)
        }
    }
}

@Composable
private fun ModeRow(option: ModeOption, selected: Boolean, onSelect: () -> Unit) {
    val shape = AuraShapes.Chip
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape)
            .background(if (selected) AuraColors.CategoryEngineering else AuraColors.Surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) AuraColors.Link else AuraColors.LineStrong, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(20.dp).clip(AuraShapes.Circle)
                .border(2.dp, if (selected) AuraColors.Link else AuraColors.Ink3, AuraShapes.Circle),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(10.dp).clip(AuraShapes.Circle).background(AuraColors.Link))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(option.title, style = AuraType.BodyStrong, modifier = Modifier.weight(1f, fill = false))
                option.badge?.let {
                    Box(Modifier.clip(AuraShapes.Pill).background(AuraColors.Neutral.bg).padding(horizontal = 8.dp, vertical = 2.dp)) {
                        Text(it, style = AuraType.Chip.copy(color = AuraColors.Neutral.fg))
                    }
                }
            }
            Text(option.note, style = AuraType.SecondarySmall)
            option.hint?.let { Text(it, style = AuraType.Chip.copy(color = AuraColors.Ready.fg)) }
        }
    }
}

@Composable
private fun SwitchRow(title: String, note: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = AuraType.BodyStrong)
            Text(note, style = AuraType.SecondarySmall)
        }
        AuraSwitch(checked, onChange, title)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, heightDp = 780)
@Composable
private fun VoiceModesPreview() {
    AuraTheme { VoiceModesScreen(VoiceSettings(), {}, {}) }
}
