package ai.aura.app.ui.screens.voice

import ai.aura.app.ui.screens.home.AuraOrb
import ai.aura.app.ui.screens.home.OrbState
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Últimas palavras ditas, para caber na barra: “…depois avisa quando terminar”. */
internal fun compactTail(heard: String): String? {
    val words = heard.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> null
        words.size > TAIL_WORDS -> "“…${words.takeLast(TAIL_WORDS).joinToString(" ")}”"
        else -> "“${words.joinToString(" ")}”"
    }
}

private const val TAIL_WORDS = 4

/**
 * Voz compacta (Voz-3-Compacta): barra no pé da tela enquanto o AURA trabalha. Ouvir NÃO interrompe o trabalho:
 * o que a pessoa fala entra na fila "POR VOZ". Dois botões: abrir a voz em tela cheia e parar de ouvir.
 */
@Composable
fun VoiceCompact(
    heard: String,
    queue: List<String>,
    onExpand: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().clip(AuraShapes.Card).background(AuraColors.Surface)
            .border(1.dp, AuraColors.Line, AuraShapes.Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (queue.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("POR VOZ", style = AuraType.Caption)
                queue.forEach { item ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(item, style = AuraType.Body, modifier = Modifier.weight(1f))
                        Box(Modifier.clip(AuraShapes.Pill).background(AuraColors.Neutral.bg).padding(horizontal = 10.dp, vertical = 4.dp)) {
                            Text("Na fila", style = AuraType.Chip.copy(color = AuraColors.Neutral.fg))
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AuraOrb(Modifier.size(32.dp), state = OrbState.Ouvindo)
            Column(Modifier.weight(1f)) {
                Text("Ouvindo", style = AuraType.BodyStrong)
                compactTail(heard)?.let { Text(it, style = AuraType.SecondarySmall) }
            }
            IconTap("Abrir voz em tela cheia", onExpand) { UpIcon(AuraColors.Ink) }
            IconTap("Parar de ouvir", onStop) { StopIcon(AuraColors.Ink) }
        }
        Text("Ouvir não interrompe o trabalho.", style = AuraType.SecondarySmall)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1)
@Composable
private fun VoiceCompactPreview() {
    AuraTheme {
        VoiceCompact(
            heard = "e depois avisa quando terminar",
            queue = listOf("Depois instala também na TV da sala"),
            onExpand = {}, onStop = {}, modifier = Modifier.padding(16.dp),
        )
    }
}
