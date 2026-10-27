package ai.aura.app.ui.screens.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraTheme

private val OrbBase = Color(0xFFEADFFB)
private val OrbApricot = Color(0xFFFFA98A)
private val OrbMint = Color(0xFF79E6CF)
private val OrbPeriwinkle = Color(0xFF8C9DFF)

/**
 * Orbe do AURA (Main.dc.html): base lilás + 3 manchas radiais + brilho. [glow] desenha o halo
 * periwinkle/rosa. Respira (escala 1 -> 1.05) só se [breathing] e o sistema permitir movimento.
 * Decorativo: quem usa dá a semântica (clique/descrição).
 */
@Composable
fun AuraOrb(
    modifier: Modifier = Modifier,
    glow: Boolean = false,
    breathing: Boolean = false,
) {
    val reduced = rememberReducedMotion()
    val transition = rememberInfiniteTransition(label = "orb")
    val breath by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(3000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    val animate = breathing && !reduced
    Canvas(
        modifier
            .graphicsLayer {
                val s = if (animate) breath else 1f
                scaleX = s
                scaleY = s
            }
            .clearAndSetSemantics { },
    ) {
        val w = size.width
        val r = w / 2f
        val center = Offset(r, r)
        if (glow) {
            drawCircle(
                brush = Brush.radialGradient(
                    0f to AuraColors.Periwinkle.copy(alpha = 0.5f),
                    0.48f to AuraColors.Rose.copy(alpha = 0.28f),
                    1f to Color.Transparent,
                    center = center, radius = r * 1.4f,
                ),
                radius = r * 1.4f, center = center,
            )
        }
        drawCircle(color = OrbBase, radius = r, center = center)
        fun spot(cx: Float, cy: Float, color: Color, radiusFraction: Float, alpha: Float = 1f) {
            val c = Offset(w * cx, w * cy)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
                    center = c, radius = w * radiusFraction,
                ),
                radius = r, center = center,
            )
        }
        spot(0.82f, 0.20f, OrbPeriwinkle, 0.62f)
        spot(0.16f, 0.82f, OrbMint, 0.55f)
        spot(0.80f, 0.78f, OrbApricot, 0.58f)
        spot(0.30f, 0.24f, Color.White, 0.32f, alpha = 0.96f)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1)
@Composable
private fun AuraOrbPreview() {
    AuraTheme {
        Row(androidx.compose.ui.Modifier.padding(32.dp)) {
            AuraOrb(Modifier.size(24.dp))
            AuraOrb(Modifier.padding(start = 24.dp).size(160.dp), glow = true)
        }
    }
}
