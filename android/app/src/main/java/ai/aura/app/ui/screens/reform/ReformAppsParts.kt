package ai.aura.app.ui.screens.reform

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.aura.app.ui.components.auraFocusRing
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraSpacing
import ai.aura.app.ui.theme.AuraTokens
import ai.aura.app.ui.theme.AuraType
import ai.aura.app.ui.theme.StatusColors

/**
 * Peças pequenas das telas Reformar 1, 2 e 5 (ícones de traço, cabeçalho, etiqueta, barra, aviso).
 * Ficam dentro de um objeto para não colidir com as peças das telas 3, 4 e 6 no mesmo pacote.
 */
internal object ReformKit {
    /** 17/22, 700: título de linha e de cabeçalho. */
    val CardTitle: TextStyle = AuraType.Section.copy(fontSize = 17.sp, lineHeight = 22.sp)

    enum class Glyph { Back, Check, Lock, Clock, Warning, Monitor, Music, Navigation, Radio, Camera }

    /** Ícone de traço 24x24 (1,75) em Canvas; decorativo, o nome fica no elemento que o contém. */
    @Composable
    fun GlyphView(
        glyph: Glyph,
        modifier: Modifier = Modifier,
        tint: Color = AuraColors.Ink,
        size: Dp = 24.dp,
        stroke: Float = AuraTokens.ICON_STROKE,
    ) {
        Canvas(modifier.size(size)) {
            val s = this.size.width / 24f
            val st = Stroke(stroke * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun at(x: Float, y: Float) = Offset(x * s, y * s)
            fun poly(vararg pts: Float, closed: Boolean = false) {
                val path = Path().apply {
                    moveTo(pts[0] * s, pts[1] * s)
                    var i = 2
                    while (i < pts.size) { lineTo(pts[i] * s, pts[i + 1] * s); i += 2 }
                    if (closed) close()
                }
                drawPath(path, tint, style = st)
            }
            fun curve(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
                val path = Path().apply {
                    moveTo(x0 * s, y0 * s)
                    cubicTo(x1 * s, y1 * s, x2 * s, y2 * s, x3 * s, y3 * s)
                }
                drawPath(path, tint, style = st)
            }
            fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
                drawRoundRect(tint, at(x, y), Size(w * s, h * s), CornerRadius(r * s), style = st)
            fun circle(cx: Float, cy: Float, r: Float) = drawCircle(tint, r * s, at(cx, cy), style = st)
            when (glyph) {
                Glyph.Back -> poly(15f, 18f, 9f, 12f, 15f, 6f)
                Glyph.Check -> poly(20f, 6f, 9f, 17f, 4f, 12f)
                Glyph.Lock -> {
                    rect(3f, 11f, 18f, 11f, 2f)
                    val shackle = Path().apply {
                        moveTo(7f * s, 11f * s)
                        lineTo(7f * s, 7f * s)
                        arcTo(Rect(7f * s, 2f * s, 17f * s, 12f * s), 180f, 180f, false)
                        lineTo(17f * s, 11f * s)
                    }
                    drawPath(shackle, tint, style = st)
                }
                Glyph.Clock -> { circle(12f, 12f, 10f); poly(12f, 6f, 12f, 12f, 16f, 14f) }
                Glyph.Warning -> {
                    poly(12f, 3.5f, 21.2f, 19.5f, 2.8f, 19.5f, closed = true)
                    poly(12f, 9f, 12f, 13f); poly(12f, 17f, 12.01f, 17f)
                }
                Glyph.Monitor -> { rect(2f, 3f, 20f, 14f, 2f); poly(8f, 21f, 16f, 21f); poly(12f, 17f, 12f, 21f) }
                Glyph.Music -> { poly(9f, 18f, 9f, 5f, 21f, 3f, 21f, 16f); circle(6f, 18f, 3f); circle(18f, 16f, 3f) }
                Glyph.Navigation -> poly(3f, 11f, 22f, 2f, 13f, 21f, 11f, 13f, closed = true)
                Glyph.Radio -> {
                    curve(4.9f, 19.1f, 1f, 15.2f, 1f, 8.8f, 4.9f, 4.9f)
                    curve(7.8f, 16.2f, 5.5f, 13.9f, 5.5f, 10.1f, 7.8f, 7.7f)
                    circle(12f, 12f, 2f)
                    curve(16.2f, 7.8f, 18.5f, 10.1f, 18.5f, 13.9f, 16.2f, 16.3f)
                    curve(19.1f, 4.9f, 23f, 8.8f, 23f, 15.1f, 19.1f, 19f)
                }
                Glyph.Camera -> { rect(2f, 7f, 20f, 13f, 2f); poly(7.5f, 7f, 9f, 4f, 15f, 4f, 16.5f, 7f); circle(12f, 13.5f, 3f) }
            }
        }
    }

    /** Voltar: 44 dp, o nome diz para onde volta. */
    @Composable
    fun BackButton(description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
        val source = remember { MutableInteractionSource() }
        Box(
            modifier.size(AuraSpacing.TouchTarget).clip(AuraShapes.Circle)
                .auraFocusRing(source, AuraShapes.Circle)
                .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) { GlyphView(Glyph.Back) }
    }

    /** Cabeçalho das telas internas: voltar, título (opcional) e apoio de 13 sp. */
    @Composable
    fun Header(
        onBack: () -> Unit,
        backDescription: String,
        modifier: Modifier = Modifier,
        title: String? = null,
        subtitle: String? = null,
        titleStyle: TextStyle = CardTitle,
    ) {
        Row(
            modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 8.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BackButton(backDescription, onBack)
            Column(Modifier.weight(1f)) {
                if (title != null) {
                    Text(
                        title, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                if (subtitle != null) {
                    Text(
                        subtitle, style = AuraType.SecondarySmall.copy(fontSize = 13.sp, lineHeight = 16.sp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    /** Etiqueta de 24 dp (12/16, 700): idade, tempo, risco, "Recomendado". Cor + texto, nunca só cor. */
    @Composable
    fun Tag(text: String, colors: StatusColors, modifier: Modifier = Modifier) {
        Box(
            modifier.heightIn(min = 24.dp).clip(AuraShapes.Chip).background(colors.bg).padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) { Text(text, style = AuraType.Chip.copy(fontSize = 12.sp, color = colors.fg)) }
    }

    /** Barra decorativa 0..1 (8 dp) com o degradê do design; quem a usa diz o valor em texto. */
    @Composable
    fun Bar(fraction: Float, modifier: Modifier = Modifier) {
        Box(modifier.height(8.dp).clip(AuraShapes.Pill).background(AuraColors.SurfaceSunken)) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .clip(AuraShapes.Pill).background(AuraColors.ProgressGradient),
            )
        }
    }

    /** Aviso âmbar em linha (sinal + texto), igual ao do design. */
    @Composable
    fun Note(text: String, modifier: Modifier = Modifier) {
        val c = AuraColors.NeedsYou
        Row(
            modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.bg)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlyphView(Glyph.Warning, tint = c.fg, size = 20.dp, modifier = Modifier.padding(top = 1.dp))
            Text(text, style = AuraType.SecondarySmall.copy(color = c.fg))
        }
    }
}
