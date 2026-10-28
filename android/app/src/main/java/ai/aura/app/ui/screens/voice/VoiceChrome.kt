package ai.aura.app.ui.screens.voice

import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraSpacing
import ai.aura.app.ui.theme.AuraType
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Botão redondo de 44 dp só com ícone; [description] é o nome lido pelo leitor de tela. */
@Composable
internal fun IconTap(description: String, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.size(AuraSpacing.TouchTarget).clip(AuraShapes.Circle)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Cabeçalho das telas claras de voz: Voltar + título. */
@Composable
internal fun VoiceHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTap("Voltar", onBack) { BackIcon(AuraColors.Ink) }
        Text(title, style = AuraType.Section, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
internal fun BackIcon(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val stroke = 2.dp.toPx()
        drawLine(color, Offset(w * 0.65f, w * 0.15f), Offset(w * 0.3f, w * 0.5f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.3f, w * 0.5f), Offset(w * 0.65f, w * 0.85f), stroke, StrokeCap.Round)
    }
}

/** Seta para baixo: minimizar. */
@Composable
internal fun DownIcon(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val stroke = 2.dp.toPx()
        drawLine(color, Offset(w * 0.15f, w * 0.35f), Offset(w * 0.5f, w * 0.7f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.5f, w * 0.7f), Offset(w * 0.85f, w * 0.35f), stroke, StrokeCap.Round)
    }
}

/** Seta para cima: abrir em tela cheia. */
@Composable
internal fun UpIcon(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val stroke = 2.dp.toPx()
        drawLine(color, Offset(w * 0.15f, w * 0.65f), Offset(w * 0.5f, w * 0.3f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.5f, w * 0.3f), Offset(w * 0.85f, w * 0.65f), stroke, StrokeCap.Round)
    }
}

/** Quadrado cheio: parar. */
@Composable
internal fun StopIcon(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        drawRoundRect(color, Offset(w * 0.2f, w * 0.2f), Size(w * 0.6f, w * 0.6f), CornerRadius(3.dp.toPx()))
    }
}
