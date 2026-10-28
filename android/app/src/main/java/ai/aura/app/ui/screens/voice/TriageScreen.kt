package ai.aura.app.ui.screens.voice

import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraButtonText
import ai.aura.app.ui.components.EmptyState
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** O que fazer com um pedido novo que chegou por voz enquanto o AURA já trabalha. */
enum class TriageChoice { AfterCurrent, MergeIntoCurrent, StopAndDoNow }

/**
 * O pedido novo e o trabalho em andamento. [step]/[total] = passo do atual; [queued] = o que já espera;
 * [saved] = trabalhos parados que podem ser retomados.
 */
data class TriageUiState(
    val heard: String,
    val currentTitle: String,
    /** Passo do atual; [total] 0 = o motor não informa (a linha some). */
    val step: Int,
    val total: Int,
    val queued: List<String> = emptyList(),
    val saved: List<String> = emptyList(),
)

private const val AUTO_SECONDS = 5

private class TriageOption(val choice: TriageChoice, val title: String, val badge: String? = null, val note: String? = null)

private val TriageOptions = listOf(
    TriageOption(TriageChoice.AfterCurrent, "Fazer depois do atual", badge = "Recomendado"),
    TriageOption(TriageChoice.MergeIntoCurrent, "Juntar no trabalho atual"),
    TriageOption(TriageChoice.StopAndDoNow, "Parar o atual e fazer agora", note = "O atual fica salvo e pode ser retomado."),
)

/**
 * Triagem do pedido (Voz-4-Triagem). Recomenda "Fazer depois do atual" e aplica sozinho em 5 s; "Mudar" para a
 * contagem. Só interrompe o trabalho atual com "Confirmar". Sem pedido ([state] nulo): mensagem e UMA ação.
 */
@Composable
fun TriageScreen(
    state: TriageUiState?,
    onApply: (TriageChoice) -> Unit,
    onBack: () -> Unit,
    onSpeak: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(AuraColors.Bg)) {
        VoiceHeader("Triagem do pedido", onBack)
        if (state == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(
                    "Nenhum pedido para triar",
                    "Fale um pedido. Eu digo se ele entra na fila ou muda o trabalho atual.",
                    "Falar um pedido", onSpeak,
                )
            }
        } else {
            TriageBody(state, onApply)
        }
    }
}

@Composable
private fun ColumnScope.TriageBody(state: TriageUiState, onApply: (TriageChoice) -> Unit) {
    // Cada pedido novo (texto novo) começa uma contagem nova com a recomendada selecionada.
    var choice by remember(state.heard) { mutableStateOf(TriageChoice.AfterCurrent) }
    var counting by remember(state.heard) { mutableStateOf(true) }
    var seconds by remember(state.heard) { mutableIntStateOf(AUTO_SECONDS) }
    var applied by remember(state.heard) { mutableStateOf<TriageChoice?>(null) }
    val applyChoice by rememberUpdatedState(onApply)

    LaunchedEffect(state.heard, counting) {
        if (counting) {
            while (seconds > 0) {
                delay(1_000)
                seconds -= 1
            }
            counting = false
            applied = choice
            applyChoice(choice)
        }
    }

    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().clip(AuraShapes.Card).background(AuraColors.Surface)
                .border(1.dp, AuraColors.Line, AuraShapes.Card).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("AURA OUVIU", style = AuraType.Caption)
            Text("Entendi um pedido novo", style = AuraType.Section)
            Text("“${state.heard}”", style = AuraType.Body)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TriageOptions.forEach { option ->
                val selected = choice == option.choice
                ChoiceRow(
                    option, selected, enabled = applied == null,
                    onSelect = { choice = option.choice; counting = false },
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().clip(AuraShapes.Card).background(AuraColors.Surface)
                .border(1.dp, AuraColors.Line, AuraShapes.Card).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("FILA", style = AuraType.Caption)
            Text("Rodando · ${state.currentTitle}", style = AuraType.BodyStrong)
            if (state.total > 0) Text("Passo ${state.step} de ${state.total}", style = AuraType.SecondarySmall)
            state.queued.forEach { Text("Na fila · $it", style = AuraType.Body) }
            Text("Salvo para retomar · ${state.saved.joinToString(", ").ifEmpty { "nenhum" }}", style = AuraType.Body)
        }
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        when {
            applied != null -> Text(appliedMessage(applied), style = AuraType.BodyStrong)
            counting -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Entra na fila em $seconds s", style = AuraType.Body)
                AuraButtonText("Mudar", { counting = false })
            }
            else -> AuraButtonPrimary(
                "Confirmar",
                { counting = false; applied = choice; applyChoice(choice) },
                Modifier.fillMaxWidth(), large = true,
            )
        }
    }
}

private fun appliedMessage(choice: TriageChoice?): String = when (choice) {
    TriageChoice.AfterCurrent -> "Entrou na fila."
    TriageChoice.MergeIntoCurrent -> "Juntado ao trabalho atual."
    TriageChoice.StopAndDoNow -> "Trocado. O atual ficou salvo."
    null -> ""
}

@Composable
private fun ChoiceRow(option: TriageOption, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val shape = AuraShapes.Chip
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape)
            .background(if (selected) AuraColors.CategoryEngineering else AuraColors.Surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) AuraColors.Link else AuraColors.LineStrong, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
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
                    Box(Modifier.clip(AuraShapes.Pill).background(AuraColors.Ready.bg).padding(horizontal = 8.dp, vertical = 2.dp)) {
                        Text(it, style = AuraType.Chip.copy(color = AuraColors.Ready.fg))
                    }
                }
            }
            if (selected) option.note?.let { Text(it, style = AuraType.SecondarySmall) }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, heightDp = 780)
@Composable
private fun TriagePreview() {
    AuraTheme {
        TriageScreen(
            TriageUiState("Instala também na TV da sala", "Corrigir tela de login", 3, 4, queued = listOf("Instalar na TV da sala")),
            {}, {}, {},
        )
    }
}
