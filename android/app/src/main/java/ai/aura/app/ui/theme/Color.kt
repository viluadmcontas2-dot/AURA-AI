package ai.aura.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

private fun c(key: String) = Color(AuraTokens.color(key))

@Immutable
class StatusColors(val bg: Color, val fg: Color, val dot: Color)

/** Cores semânticas do AURA. Cor é significado: âmbar = precisa de você; vermelho = risco; verde = prova. */
object AuraColors {
    val Bg = c("bg")
    val Surface = c("surface")
    val SurfaceSunken = c("surfaceSunken")
    val Line = c("line")
    val LineStrong = c("lineStrong")
    val Ink = c("ink")
    val Ink2 = c("ink2")
    val Ink3 = c("ink3")
    val Link = c("link")

    val Periwinkle = c("aura.periwinkle")
    val Rose = c("aura.rose")
    val Apricot = c("aura.apricot")
    val Mint = c("aura.mint")

    /** Só quando o AURA está vivo (rule 2). */
    val ProgressGradient: Brush get() = Brush.horizontalGradient(listOf(Periwinkle, Rose, Apricot))

    val Ready = StatusColors(c("status.ready.bg"), c("status.ready.fg"), c("status.ready.dot"))
    val NeedsYou = StatusColors(c("status.needsYou.bg"), c("status.needsYou.fg"), c("status.needsYou.dot"))
    val Risk = StatusColors(c("status.risk.bg"), c("status.risk.fg"), c("status.risk.dot"))
    val RiskSolid = c("status.risk.solid")
    val Info = StatusColors(c("status.info.bg"), c("status.info.fg"), c("status.info.fg"))
    val Neutral = StatusColors(c("status.neutral.bg"), c("status.neutral.fg"), Ink3)

    val CategoryEngineering = c("category.engineering")
    val CategoryClarity = c("category.clarity")
    val CategoryAndroid = c("category.android")
    val CategoryOrganization = c("category.organization")
    val CategoryLilac = c("category.lilac")
    val CategoryApricot = c("category.apricot")

    // Bastidores / voz (tema escuro)
    val DarkVoice = c("dark.voice")
    val DarkBackstage = c("dark.backstage")
    val DarkPanel = c("dark.panel")
    val DarkLine = c("dark.line")
    val DarkText = c("dark.text")
    val DarkText2 = c("dark.text2")
    val DarkFocus = c("dark.focus")
}
