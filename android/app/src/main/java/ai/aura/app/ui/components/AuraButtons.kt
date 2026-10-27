package ai.aura.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraSpacing
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType

@Composable
private fun AuraButtonBase(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    large: Boolean,
    container: Color,
    content: Color,
    borderColor: Color?,
    weight: FontWeight,
    underline: Boolean,
    contentDescription: String?,
) {
    val source = remember { MutableInteractionSource() }
    val shape = if (large) AuraShapes.Pill else AuraShapes.Button
    Box(
        modifier = modifier
            .heightIn(min = if (large) 52.dp else AuraSpacing.TouchTarget)
            .clip(shape)
            .background(container)
            .let { if (borderColor != null) it.border(1.dp, borderColor, shape) else it }
            .auraFocusRing(source, shape)
            .clickable(
                interactionSource = source, indication = null, enabled = enabled,
                role = Role.Button, onClick = onClick,
            )
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription }
            .padding(PaddingValues(horizontal = 18.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = AuraType.Button.copy(
                color = content, fontWeight = weight,
                textDecoration = if (underline) TextDecoration.Underline else null,
            ),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Único botão principal da tela (regra 1): pílula preta, texto = verbo + objeto
 * ("Salvar alterações"), a mesma palavra do aviso. Desabilitado: #E6E3DC / #6A6772.
 */
@Composable
fun AuraButtonPrimary(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    large: Boolean = false,
    contentDescription: String? = null,
) = AuraButtonBase(
    text, onClick, modifier, enabled, large,
    container = if (enabled) AuraColors.Ink else AuraColors.Line,
    content = if (enabled) Color.White else AuraColors.Ink3,
    borderColor = null, weight = FontWeight.Bold, underline = false, contentDescription = contentDescription,
)

/** Secundário: fundo branco, borda #D5D1C7. */
@Composable
fun AuraButtonSecondary(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) = AuraButtonBase(
    text, onClick, modifier, enabled, large = false,
    container = AuraColors.Surface,
    content = if (enabled) AuraColors.Ink else AuraColors.Ink3,
    borderColor = if (enabled) AuraColors.LineStrong else AuraColors.Line,
    weight = FontWeight.SemiBold, underline = false, contentDescription = contentDescription,
)

/** Texto (ação de baixa ênfase, ex.: "Depois"): link sublinhado, alvo de 44 dp. */
@Composable
fun AuraButtonText(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) = AuraButtonBase(
    text, onClick, modifier, enabled, large = false,
    container = Color.Transparent,
    content = if (enabled) AuraColors.Link else AuraColors.Ink3,
    borderColor = null, weight = FontWeight.SemiBold, underline = enabled, contentDescription = contentDescription,
)

/** Destrutivo (vermelho sólido): só após aprovação com alvo e consequência visíveis. */
@Composable
fun AuraButtonDestructive(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) = AuraButtonBase(
    text, onClick, modifier, enabled, large = false,
    container = if (enabled) AuraColors.RiskSolid else AuraColors.Line,
    content = if (enabled) Color.White else AuraColors.Ink3,
    borderColor = null, weight = FontWeight.Bold, underline = false, contentDescription = contentDescription,
)

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1)
@Composable
private fun ButtonsPreview() {
    AuraTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AuraButtonPrimary("Salvar alterações", {}, Modifier.fillMaxWidth())
            AuraButtonPrimary("Abrir pull request", {}, Modifier.fillMaxWidth(), large = true)
            AuraButtonPrimary("Salvar alterações", {}, Modifier.fillMaxWidth(), enabled = false)
            AuraButtonSecondary("Voltar", {}, Modifier.fillMaxWidth())
            AuraButtonText("Depois", {})
            AuraButtonDestructive("Apagar projeto", {}, Modifier.fillMaxWidth())
        }
    }
}
