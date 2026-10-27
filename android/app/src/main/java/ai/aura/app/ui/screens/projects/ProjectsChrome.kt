package ai.aura.app.ui.screens.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.aura.app.ui.components.StatusKind
import ai.aura.app.ui.components.StatusChip
import ai.aura.app.ui.components.auraFocusRing
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraSpacing
import ai.aura.app.ui.theme.AuraType
import ai.aura.app.ui.theme.StatusColors

/** Título de linha de cartão: 17/22, 700 (usado nos cartões de Projetos). */
internal val CardTitle = AuraType.Body.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)

/** Botão de ícone de 44 dp (alvo de toque mínimo), opcionalmente escuro (ex.: "Novo projeto"). */
@Composable
internal fun IconTap(icon: PIcon, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, dark: Boolean = false) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(AuraSpacing.TouchTarget)
            .clip(AuraShapes.Circle)
            .let { if (dark) it.background(AuraColors.Ink) else it }
            .auraFocusRing(source, AuraShapes.Circle)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { PIconView(icon, tint = if (dark) Color.White else AuraColors.Ink, size = 22.dp) }
}

/** Cabeçalho das telas internas: voltar (44 dp), título 17/700, subtítulo 13, ações à direita. */
@Composable
internal fun ProjHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backDescription: String = "Voltar",
    subtitle: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp).heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconTap(PIcon.Back, backDescription, onBack)
        Column(Modifier.weight(1f)) {
            Text(title, style = CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = AuraType.SecondarySmall.copy(fontSize = 13.sp, lineHeight = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** Rótulo de seção em caixa alta (12/16, 700, +.03em). */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = AuraColors.Ink2) {
    Text(text.uppercase(), style = AuraType.Caption.copy(color = color), modifier = modifier)
}

/** "Rodando": cápsula escura com ponto em gradiente (AURA vivo, regra 2). */
@Composable
internal fun RunningChip(text: String = "Rodando", modifier: Modifier = Modifier) {
    Row(
        modifier
            .heightIn(min = 28.dp)
            .clip(AuraShapes.Chip)
            .background(AuraColors.Ink)
            .padding(horizontal = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Executando: $text" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(AuraShapes.Circle).background(Brush.linearGradient(listOf(AuraColors.Periwinkle, AuraColors.Rose))))
        Text(text, style = AuraType.Chip.copy(color = Color.White))
    }
}

@Composable
internal fun PhaseChip(phase: ProjectPhase, modifier: Modifier = Modifier) = when (phase.kind) {
    PhaseKind.Running -> RunningChip(phase.label, modifier)
    PhaseKind.NeedsYou -> StatusChip(StatusKind.NeedsYou, phase.label, modifier)
    PhaseKind.Ready -> StatusChip(StatusKind.Ready, phase.label, modifier)
    PhaseKind.Failed -> StatusChip(StatusKind.Risk, phase.label, modifier)
    PhaseKind.Queued -> StatusChip(StatusKind.Neutral, phase.label, modifier)
    PhaseKind.Idle -> StatusChip(StatusKind.Neutral, phase.label, modifier)
    PhaseKind.Stopped -> StatusChip(StatusKind.Neutral, phase.label, modifier)
}

/** Aviso em linha (âmbar = precisa de você; vermelho = erro com o que fazer). */
@Composable
internal fun NoteBanner(text: String, colors: StatusColors, icon: PIcon, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(AuraShapes.Chip).background(colors.bg).padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        PIconView(icon, tint = colors.dot, size = 18.dp, modifier = Modifier.padding(top = 1.dp))
        Text(text, style = AuraType.SecondarySmall.copy(color = colors.fg, fontWeight = FontWeight.SemiBold))
    }
}

/** Texto de vazio dentro de uma aba; a ação é a caixa "Peça ao AURA" logo abaixo. */
@Composable
internal fun InlineEmpty(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = AuraType.Section)
        Text(body, style = AuraType.Secondary)
    }
}

/** Ícone + cor da categoria do projeto (estável por nome; só enfeite, não carrega significado). */
internal fun categoryOf(name: String): Pair<Color, PIcon> = when (name.length % 3) {
    0 -> AuraColors.CategoryEngineering to PIcon.Code
    1 -> AuraColors.CategoryAndroid to PIcon.Box
    else -> AuraColors.CategoryLilac to PIcon.Spark
}
