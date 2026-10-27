package ai.aura.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import ai.aura.app.ui.components.AuraCard
import ai.aura.app.ui.components.auraFocusRing
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraSpacing
import ai.aura.app.ui.theme.AuraType

/** Título de seção: 12/16, 700, caixa alta (igual ao design: "SEGURANÇA E CUSTO"). */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = AuraType.Caption,
        modifier = modifier.padding(start = 4.dp, bottom = 0.dp).semantics { heading() },
    )
}

/** Cartão de seção: reutiliza AuraCard; as linhas internas trazem os próprios espaços e divisórias. */
@Composable
internal fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    AuraCard(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
internal fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(AuraColors.Line))
}

/** Linha com título, apoio opcional e um controle à direita. */
@Composable
internal fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AuraType.Body)
            if (subtitle != null) Text(subtitle, style = AuraType.SecondarySmall)
        }
        trailing()
    }
}

/** Interruptor do design: 48x28, trilho #EEECE7 + borda #6A6772 desligado; tinta quando ligado. */
@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val source = remember { MutableInteractionSource() }
    val shape = AuraShapes.Button
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .auraFocusRing(source, shape)
            .toggleable(
                value = checked, interactionSource = source, indication = null,
                role = Role.Switch, onValueChange = onChange,
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AuraType.Body)
            if (subtitle != null) Text(subtitle, style = AuraType.SecondarySmall)
        }
        val track = if (checked) AuraColors.Ink else AuraColors.SurfaceSunken
        val edge = if (checked) AuraColors.Ink else AuraColors.Ink3
        val thumb = if (checked) Color.White else AuraColors.Ink3
        Box(
            Modifier.size(width = 48.dp, height = 28.dp).clip(AuraShapes.Pill)
                .background(track).border(2.dp, edge, AuraShapes.Pill),
        ) {
            Box(
                Modifier.offset(x = if (checked) 22.dp else 2.dp, y = 2.dp)
                    .size(20.dp).clip(AuraShapes.Circle).background(thumb),
            )
        }
    }
}

/** Grupo segmentado (padrão "Aparência" do design): trilho #EEECE7, 44 dp, escolhido em tinta. */
@Composable
internal fun <T> Segmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    groupLabel: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(AuraShapes.Pill)
            .background(AuraColors.SurfaceSunken)
            .padding(2.dp)
            .selectableGroup()
            .semantics { contentDescription = groupLabel },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            val source = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = AuraSpacing.TouchTarget)
                    .clip(AuraShapes.Pill)
                    .background(if (on) AuraColors.Ink else Color.Transparent)
                    .auraFocusRing(source, AuraShapes.Pill)
                    .selectable(selected = on, interactionSource = source, indication = null, role = Role.RadioButton) {
                        onSelect(value)
                    }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = AuraType.SecondarySmall.copy(
                        color = if (on) Color.White else AuraColors.Ink2,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
                    ),
                )
            }
        }
    }
}

/** Linha de escolha única com marca de selecionado (modos). */
@Composable
internal fun ChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(AuraShapes.Button)
            .auraFocusRing(source, AuraShapes.Button)
            .selectable(selected = selected, interactionSource = source, indication = null, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = if (selected) AuraType.BodyStrong else AuraType.Body)
            Text(subtitle, style = AuraType.SecondarySmall)
        }
        Box(
            Modifier.size(24.dp).clip(AuraShapes.Circle)
                .background(if (selected) AuraColors.Ink else Color.Transparent)
                .border(2.dp, if (selected) AuraColors.Ink else AuraColors.Ink3, AuraShapes.Circle),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Canvas(Modifier.size(14.dp)) {
                val p = Path().apply {
                    moveTo(size.width * 0.15f, size.height * 0.55f)
                    lineTo(size.width * 0.42f, size.height * 0.80f)
                    lineTo(size.width * 0.85f, size.height * 0.25f)
                }
                drawPath(p, Color.White, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}

/**
 * Campo de texto do rascunho. Guarda o texto digitado localmente (vírgula e espaço no fim não
 * somem) e só devolve o que o motor entenderia. [resetKey] muda quando o motor devolve outra versão.
 */
@Composable
internal fun DraftField(
    label: String,
    initial: String,
    resetKey: Any?,
    onText: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    prefix: String? = null,
    suffix: String? = null,
    numeric: Boolean = false,
) {
    var text by remember(resetKey) { mutableStateOf(initial) }
    val source = remember { MutableInteractionSource() }
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = AuraType.SecondarySmall)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = AuraSpacing.TouchTarget)
                .clip(AuraShapes.Button)
                .background(AuraColors.SurfaceSunken)
                .auraFocusRing(source, AuraShapes.Button)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (prefix != null) Text(prefix, style = AuraType.SecondarySmall)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, style = AuraType.Body.copy(color = AuraColors.Ink3))
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it; onText(it) },
                    singleLine = true,
                    textStyle = AuraType.Body,
                    cursorBrush = SolidColor(AuraColors.Ink),
                    interactionSource = source,
                    keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                )
            }
            if (suffix != null) Text(suffix, style = AuraType.SecondarySmall)
        }
    }
}

/** Botão de texto pequeno com alvo de 44 dp e descrição própria (ex.: remover item da lista). */
@Composable
internal fun TinyAction(text: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .heightIn(min = AuraSpacing.TouchTarget)
            .clip(AuraShapes.Pill)
            .auraFocusRing(source, AuraShapes.Pill)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = AuraType.SecondarySmall.copy(
                color = if (danger) AuraColors.Risk.fg else AuraColors.Link,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

internal val ContentPadding = PaddingValues(horizontal = AuraSpacing.ScreenGutter)
