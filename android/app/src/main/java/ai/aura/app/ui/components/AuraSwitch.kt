package ai.aura.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraSpacing

/**
 * Interruptor do design: trilho 48x28, tinta quando ligado, #EEECE7 com borda #6A6772 desligado.
 * O toque ocupa 52x44 dp. [label] é o nome lido pelo leitor de tela ("Modo carro"); o estado vai no papel de switch.
 */
@Composable
fun AuraSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val track = if (checked) AuraColors.Ink else AuraColors.SurfaceSunken
    val edge = if (checked) AuraColors.Ink else AuraColors.Ink3
    val thumb = if (checked) Color.White else AuraColors.Ink3
    Box(
        modifier
            .size(width = 52.dp, height = AuraSpacing.TouchTarget)
            .auraFocusRing(source, AuraShapes.Pill)
            .toggleable(
                value = checked, enabled = enabled, interactionSource = source, indication = null,
                role = Role.Switch, onValueChange = onCheckedChange,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
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
