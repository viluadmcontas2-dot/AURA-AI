package ai.aura.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraTheme

/**
 * Progresso 0..1. Gradiente periwinkle/rosa/damasco SÓ quando [alive] (AURA trabalhando agora);
 * parado/concluído usa tinta sólida (regra 2).
 */
@Composable
fun AuraProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    alive: Boolean = false,
    contentDescription: String = "Progresso",
) {
    val p = progress.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(AuraShapes.Pill)
            .background(AuraColors.SurfaceSunken)
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                progressBarRangeInfo = ProgressBarRangeInfo(p, 0f..1f)
            },
    ) {
        if (p > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(p)
                    .clip(AuraShapes.Pill)
                    .let { if (alive) it.background(AuraColors.ProgressGradient) else it.background(AuraColors.Ink2) },
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1)
@Composable
private fun ProgressPreview() {
    AuraTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AuraProgressBar(0.6f, alive = true, contentDescription = "Executando, 60 por cento")
            AuraProgressBar(1f, alive = false, contentDescription = "Concluído")
        }
    }
}
