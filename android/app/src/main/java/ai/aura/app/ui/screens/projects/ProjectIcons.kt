package ai.aura.app.ui.screens.projects

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraTokens

/** Ícones de traço (24x24, 1,75) desenhados em Canvas: sem material-icons-extended. */
internal enum class PIcon { Back, Plus, Search, More, Check, Folder, Code, Box, Doc, Image, Mic, Branch, Lock, ChevronRight, Spark, Copy, Device }

@Composable
internal fun PIconView(icon: PIcon, modifier: Modifier = Modifier, tint: Color = AuraColors.Ink, size: Dp = 24.dp) {
    // Decorativo: o rótulo de acessibilidade fica no elemento clicável que o contém.
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        val s = this.size.width / 24f
        val st = Stroke(AuraTokens.ICON_STROKE * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        fun poly(vararg pts: Float, close: Boolean = false) {
            val path = Path().apply {
                moveTo(pts[0] * s, pts[1] * s)
                var i = 2
                while (i < pts.size) { lineTo(pts[i] * s, pts[i + 1] * s); i += 2 }
                if (close) close()
            }
            drawPath(path, tint, style = st)
        }
        fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
            drawRoundRect(tint, p(x, y), Size(w * s, h * s), CornerRadius(r * s), style = st)
        fun circle(cx: Float, cy: Float, r: Float, fill: Boolean = false) =
            drawCircle(tint, r * s, p(cx, cy), style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else st)
        when (icon) {
            PIcon.Back -> poly(15f, 18f, 9f, 12f, 15f, 6f)
            PIcon.ChevronRight -> poly(9f, 18f, 15f, 12f, 9f, 6f)
            PIcon.Plus -> { poly(5f, 12f, 19f, 12f); poly(12f, 5f, 12f, 19f) }
            PIcon.Search -> { circle(11f, 11f, 8f); poly(21f, 21f, 16.7f, 16.7f) }
            PIcon.More -> { circle(12f, 5f, 1.6f, true); circle(12f, 12f, 1.6f, true); circle(12f, 19f, 1.6f, true) }
            PIcon.Check -> poly(20f, 6f, 9f, 17f, 4f, 12f)
            PIcon.Folder -> { rect(2f, 5f, 20f, 15f, 2f); poly(2f, 9f, 22f, 9f) }
            PIcon.Code -> { poly(16f, 18f, 22f, 12f, 16f, 6f); poly(8f, 6f, 2f, 12f, 8f, 18f) }
            PIcon.Box -> {
                poly(12f, 2f, 21f, 7f, 21f, 17f, 12f, 22f, 3f, 17f, 3f, 7f, close = true)
                poly(3f, 7f, 12f, 12f, 21f, 7f); poly(12f, 12f, 12f, 22f)
            }
            PIcon.Doc -> { rect(5f, 2f, 14f, 20f, 2f); poly(9f, 9f, 15f, 9f); poly(9f, 13f, 15f, 13f); poly(9f, 17f, 13f, 17f) }
            PIcon.Image -> { rect(3f, 3f, 18f, 18f, 2f); circle(9f, 9f, 2f); poly(21f, 15f, 16f, 11f, 6f, 21f) }
            PIcon.Mic -> {
                rect(9f, 2f, 6f, 12f, 3f)
                val u = Path().apply { moveTo(19f * s, 10f * s); lineTo(19f * s, 12f * s); cubicTo(19f * s, 16f * s, 15.9f * s, 19f * s, 12f * s, 19f * s); cubicTo(8.1f * s, 19f * s, 5f * s, 16f * s, 5f * s, 12f * s); lineTo(5f * s, 10f * s) }
                drawPath(u, tint, style = st); poly(12f, 19f, 12f, 22f)
            }
            PIcon.Branch -> { poly(6f, 3f, 6f, 15f); circle(18f, 6f, 3f); circle(6f, 18f, 3f); poly(18f, 9f, 18f, 11f, 12f, 17f, 9f, 18f) }
            PIcon.Lock -> {
                rect(3f, 11f, 18f, 11f, 2f)
                poly(7f, 11f, 7f, 7f, 8.5f, 4f, 12f, 2.8f, 15.5f, 4f, 17f, 7f, 17f, 11f)
            }
            PIcon.Spark -> poly(12f, 2f, 14f, 9f, 21f, 12f, 14f, 15f, 12f, 22f, 10f, 15f, 3f, 12f, 10f, 9f, close = true)
            PIcon.Copy -> { rect(8f, 8f, 14f, 14f, 2f); poly(4f, 16f, 3f, 16f, 2f, 15f, 2f, 4f, 3f, 2f, 4f, 2f, 15f, 2f, 16f, 3f, 16f, 4f) }
            PIcon.Device -> { rect(5f, 2f, 14f, 20f, 2f); poly(12f, 18f, 12.01f, 18f) }
        }
    }
}
