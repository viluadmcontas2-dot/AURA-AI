package ai.aura.app.ui.screens.reform

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.EmptyState
import ai.aura.app.ui.components.auraFocusRing
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraType
import java.time.Year

/**
 * Reformar 1 · Escolher o app. Recebe os apps do aparelho alvo por [state] (o motor entrega, esta tela não
 * procura nada). Sem apps ou com erro, mostra UMA ação em [onFix]; protegido (câmera de ré, sistema) nunca seleciona.
 */
@Composable
fun ReformAppsScreen(
    state: ReformAppsState,
    onBack: () -> Unit,
    onNext: (ReformApp) -> Unit,
    onFix: (ReformFix) -> Unit,
    modifier: Modifier = Modifier,
    deviceLabel: String = "Multimídia do carro",
    currentYear: Int = Year.now().value,
) {
    var chosenId by rememberSaveable(state.selectedId) { mutableStateOf(state.selectedId) }
    val chosen = state.apps.firstOrNull { it.id == chosenId && it.protectedReason == null }
        ?: state.apps.firstOrNull { it.protectedReason == null }
    val problem = state.problem
    val listing = problem == null && state.apps.isNotEmpty()

    Column(modifier.fillMaxSize().background(AuraColors.Bg).statusBarsPadding()) {
        ReformKit.Header(
            onBack = onBack, backDescription = "Voltar para o aparelho",
            title = deviceLabel, subtitle = "Passo 1 de 2 · Escolher app",
        )
        LazyColumn(
            Modifier.weight(1f).selectableGroup(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column {
                    Text("Reformar apps", style = AuraType.TabTitle, modifier = Modifier.semantics { heading() })
                    Text(
                        "Escolha um app antigo da multimídia. O AURA estuda, refaz a tela e testa.",
                        style = AuraType.Secondary, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            when {
                problem != null -> item {
                    EmptyState(problem.title, problem.body, problem.fix.label, { onFix(problem.fix) }, glyph = "!")
                }
                state.apps.isEmpty() -> item {
                    EmptyState(
                        "Nenhum app para mostrar",
                        "Toque em Procurar apps para ver o que está instalado na multimídia.",
                        ReformFix.Search.label, { onFix(ReformFix.Search) },
                    )
                }
                else -> {
                    item { DeviceChip("$deviceLabel · ${if (state.apps.size == 1) "1 app" else "${state.apps.size} apps"}") }
                    items(state.apps, key = { it.id }) { app ->
                        AppCard(app, selected = app.id == chosen?.id, currentYear = currentYear, onSelect = { chosenId = app.id })
                    }
                    if (chosen == null) {
                        item {
                            Text(
                                "Todos os apps desta lista são protegidos. Não há nada para reformar aqui.",
                                style = AuraType.Secondary,
                            )
                        }
                    }
                }
            }
        }
        if (listing) {
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp)) {
                AuraButtonPrimary(
                    "Escolher escopo", { chosen?.let(onNext) },
                    Modifier.fillMaxWidth(), enabled = chosen != null, large = true,
                )
            }
        }
    }
}

@Composable
private fun DeviceChip(text: String) {
    Row(
        Modifier.heightIn(min = 32.dp).clip(AuraShapes.Chip).background(AuraColors.CategoryAndroid).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ReformKit.GlyphView(ReformKit.Glyph.Monitor, size = 14.dp)
        Text(text, style = AuraType.SecondarySmall.copy(color = AuraColors.Ink, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun AppCard(app: ReformApp, selected: Boolean, currentYear: Int, onSelect: () -> Unit) {
    val reason = app.protectedReason
    if (reason != null) ProtectedCard(app, reason) else SelectableCard(app, selected, currentYear, onSelect)
}

/** Protegido: sem clique, e o motivo está escrito no cartão (nada de cartão cinza mudo). */
@Composable
private fun ProtectedCard(app: ReformApp, reason: String) {
    val tag = AuraColors.Neutral
    Row(
        Modifier.fillMaxWidth().clip(AuraShapes.Card).background(AuraColors.SurfaceSunken)
            .border(1.dp, AuraColors.Line, AuraShapes.Card).padding(14.dp)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(app.kind, tile = AuraColors.Surface, tint = AuraColors.Ink2)
        Column(Modifier.weight(1f)) {
            Text(app.label, style = ReformKit.CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(reason, style = AuraType.SecondarySmall)
        }
        Row(
            Modifier.heightIn(min = 28.dp).clip(AuraShapes.Chip).background(tag.bg).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ReformKit.GlyphView(ReformKit.Glyph.Lock, tint = tag.fg, size = 14.dp)
            Text("Protegido", style = AuraType.Chip.copy(fontSize = 12.sp, color = tag.fg))
        }
    }
}

@Composable
private fun SelectableCard(app: ReformApp, selected: Boolean, currentYear: Int, onSelect: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().clip(AuraShapes.Card).background(AuraColors.Surface)
                .border(if (selected) 2.dp else 1.dp, if (selected) AuraColors.Ink else AuraColors.Line, AuraShapes.Card)
                .auraFocusRing(source, AuraShapes.Card)
                .selectable(
                    selected = selected, interactionSource = source, indication = null,
                    role = Role.RadioButton, onClick = onSelect,
                )
                .padding(if (selected) 13.dp else 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppIcon(app.kind, tile = tileOf(app.kind))
                Column(Modifier.weight(1f)) {
                    Text(app.label, style = ReformKit.CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    app.details?.let { Text(it, style = AuraType.SecondarySmall) }
                }
                app.ageLabel(currentYear)?.let { ReformKit.Tag(it, AuraColors.Neutral) }
            }
            app.potential?.let { potential ->
                Row(
                    Modifier.padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Potencial de melhoria", style = SMALL)
                    ReformKit.Bar(potential, Modifier.weight(1f))
                    Text(
                        reformPotentialLabel(potential),
                        style = SMALL.copy(fontWeight = FontWeight.Bold, color = AuraColors.Ink),
                        textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 40.dp),
                    )
                }
            }
        }
        if (selected) {
            // Marca de "escolhido" no canto, com anel da cor do fundo (como no design); o estado vai no selectable.
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = (-14).dp, y = (-10).dp)
                    .size(24.dp).clip(AuraShapes.Circle).background(AuraColors.Bg),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(20.dp).clip(AuraShapes.Circle).background(AuraColors.Ink),
                    contentAlignment = Alignment.Center,
                ) { ReformKit.GlyphView(ReformKit.Glyph.Check, tint = Color.White, size = 14.dp) }
            }
        }
    }
}

private val SMALL = AuraType.Secondary.copy(fontSize = 13.sp, lineHeight = 16.sp)

@Composable
private fun AppIcon(kind: ReformAppKind, tile: Color, tint: Color = AuraColors.Ink) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(tile), contentAlignment = Alignment.Center) {
        ReformKit.GlyphView(glyphOf(kind), tint = tint, size = 22.dp)
    }
}

private fun glyphOf(kind: ReformAppKind) = when (kind) {
    ReformAppKind.Music -> ReformKit.Glyph.Music
    ReformAppKind.Navigation -> ReformKit.Glyph.Navigation
    ReformAppKind.Radio -> ReformKit.Glyph.Radio
    ReformAppKind.Camera -> ReformKit.Glyph.Camera
    ReformAppKind.Generic -> ReformKit.Glyph.Monitor
}

/** Cor do ícone por tipo: só enfeite, o significado vem do texto. */
private fun tileOf(kind: ReformAppKind) = when (kind) {
    ReformAppKind.Music -> AuraColors.CategoryLilac
    ReformAppKind.Navigation -> AuraColors.CategoryAndroid
    ReformAppKind.Radio -> AuraColors.CategoryApricot
    ReformAppKind.Camera, ReformAppKind.Generic -> AuraColors.CategoryEngineering
}
