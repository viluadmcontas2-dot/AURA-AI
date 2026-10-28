package ai.aura.app.ui.screens.checkup

import ai.aura.app.checks.Check
import ai.aura.app.checks.CheckResult
import ai.aura.app.checks.CheckState
import ai.aura.app.checks.RETRY
import ai.aura.app.checks.safeRun
import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.components.AuraButtonText
import ai.aura.app.ui.components.AuraCard
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraSpacing
import ai.aura.app.ui.theme.AuraType
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Checkup do ambiente (UI-34). Roda local e abre sem motor: cada verificação aparece quando termina (no máximo 5 s),
 * cada problema tem UM botão. [onFix] recebe o id da verificação; quem controla a navegação leva à tela certa.
 */
@Composable
fun CheckupScreen(checks: List<Check>, onFix: (checkId: String) -> Unit, onBack: () -> Unit) {
    var round by remember { mutableIntStateOf(0) }
    val results = remember(checks, round) { mutableStateMapOf<String, CheckResult>() }
    LaunchedEffect(checks, round) {
        coroutineScope { checks.forEach { c -> launch { results[c.id] = c.safeRun() } } }
    }

    val ok = checks.filter { results[it.id]?.state == CheckState.Ok }
    val problems = checks.filter { c -> results[c.id]?.let { it.state != CheckState.Ok } == true }
    val waiting = checks.filter { it.id !in results }

    Column(Modifier.fillMaxSize().background(AuraColors.Bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = AuraSpacing.S2), verticalAlignment = Alignment.CenterVertically) {
            AuraButtonText("Voltar", onBack)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = AuraSpacing.ScreenGutter, vertical = AuraSpacing.S2),
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.S3),
        ) {
            Text("Checkup do ambiente", style = AuraType.TabTitle, modifier = Modifier.semantics { heading() })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AuraSpacing.S4)) {
                Ring(ok.size, checks.size)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AuraSpacing.S1)) {
                    Text("${ok.size} de ${checks.size} ok", style = AuraType.Section)
                    Text(
                        when {
                            waiting.isNotEmpty() -> "Verificando…"
                            problems.isEmpty() -> "Tudo certo para o AURA trabalhar sem pausas."
                            else -> "Resolva ${problems.size} ${if (problems.size == 1) "alerta" else "alertas"} para o AURA trabalhar sem pausas."
                        },
                        style = AuraType.Secondary,
                    )
                }
            }

            problems.forEachIndexed { i, c ->
                val r = results[c.id] ?: return@forEachIndexed
                ProblemCard(r, primary = i == 0) { if (r.fix == RETRY) round++ else onFix(c.id) }
            }

            if (waiting.isNotEmpty()) {
                Section("VERIFICANDO") { waiting.forEach { Item("…", AuraColors.Neutral.bg, AuraColors.Neutral.fg, "${it.name}…") } }
            }
            if (ok.isNotEmpty()) {
                Section("TUDO CERTO") {
                    ok.forEach { c -> Item("✓", AuraColors.Ready.bg, AuraColors.Ready.fg, results[c.id]?.message ?: c.name) }
                }
            }
            AuraButtonSecondary(RETRY, { round++ }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ProblemCard(r: CheckResult, primary: Boolean, onAct: () -> Unit) {
    val tone = if (r.state == CheckState.Failed) AuraColors.Risk else AuraColors.NeedsYou
    val clipboard = LocalClipboardManager.current
    var copied by remember(r) { mutableStateOf(false) }
    AuraCard(Modifier.fillMaxWidth(), tint = tone.bg) {
        Row(horizontalArrangement = Arrangement.spacedBy(AuraSpacing.S3), verticalAlignment = Alignment.Top) {
            Glyph("!", tone.dot, Color.White)
            Text(r.message, style = AuraType.BodyStrong.copy(color = tone.fg), modifier = Modifier.weight(1f))
        }
        val label = r.fix ?: return@AuraCard // sem ação não há botão (nunca um botão mudo)
        val click = {
            if (r.command != null) {
                clipboard.setText(AnnotatedString(r.command))
                copied = true
            } else onAct()
        }
        val shown = if (copied) "Copiado" else label
        if (primary) AuraButtonPrimary(shown, click, Modifier.fillMaxWidth()) else AuraButtonSecondary(shown, click, Modifier.fillMaxWidth())
    }
}

@Composable
private fun Section(title: String, rows: @Composable () -> Unit) {
    Text(title, style = AuraType.Caption.copy(color = AuraColors.Ink3), modifier = Modifier.semantics { heading() })
    AuraCard(Modifier.fillMaxWidth()) { rows() }
}

@Composable
private fun Item(glyph: String, bg: Color, fg: Color, text: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = AuraSpacing.TouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AuraSpacing.S3),
    ) {
        Glyph(glyph, bg, fg)
        Text(text, style = AuraType.Body, modifier = Modifier.weight(1f))
    }
}

/** Marca decorativa: o estado já está dito no texto da linha. */
@Composable
private fun Glyph(glyph: String, bg: Color, fg: Color) {
    Box(Modifier.size(24.dp).clip(AuraShapes.Circle).background(bg).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Text(glyph, style = AuraType.Caption.copy(color = fg))
    }
}

@Composable
private fun Ring(ok: Int, total: Int) {
    val track = AuraColors.Line
    val fill = AuraColors.Ready.dot
    Box(Modifier.size(84.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 8.dp.toPx()
            val corner = Offset(stroke / 2, stroke / 2)
            val box = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, corner, box, style = Stroke(stroke))
            if (ok > 0 && total > 0) drawArc(fill, -90f, 360f * ok / total, false, corner, box, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text("$ok/$total", style = AuraType.Section)
    }
}
