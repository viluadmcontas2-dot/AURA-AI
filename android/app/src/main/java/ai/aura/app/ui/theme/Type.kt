package ai.aura.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * DESVIO vs design: o design usa Bricolage Grotesque 600 (display), Atkinson Hyperlegible Next
 * (corpo) e JetBrains Mono (código). Fontes baixáveis/empacotadas não estão disponíveis (sem
 * dependência nova), então usamos FontFamily.SansSerif e FontFamily.Monospace do sistema.
 * Trocar depois só exige mudar estas três constantes.
 */
object AuraFonts {
    val Display: FontFamily = FontFamily.SansSerif // Bricolage Grotesque 600
    val Body: FontFamily = FontFamily.SansSerif    // Atkinson Hyperlegible Next
    val Mono: FontFamily = FontFamily.Monospace    // JetBrains Mono
}

/** Escala do tokens.json ("type"): tamanho/altura de linha. Nunca abaixo de 12 sp. */
object AuraType {
    val Display = TextStyle(fontFamily = AuraFonts.Display, fontWeight = FontWeight.SemiBold,
        fontSize = 38.sp, lineHeight = 42.sp, letterSpacing = (-0.03).em, color = AuraColors.Ink)
    val TabTitle = TextStyle(fontFamily = AuraFonts.Display, fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.02).em, color = AuraColors.Ink)
    val Section = TextStyle(fontFamily = AuraFonts.Body, fontWeight = FontWeight.Bold,
        fontSize = 18.sp, lineHeight = 24.sp, color = AuraColors.Ink)
    val Body = TextStyle(fontFamily = AuraFonts.Body, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp, color = AuraColors.Ink)
    val BodyStrong = Body.copy(fontWeight = FontWeight.Bold)
    val Secondary = TextStyle(fontFamily = AuraFonts.Body, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 21.sp, color = AuraColors.Ink2)
    val SecondarySmall = Secondary.copy(fontSize = 14.sp, lineHeight = 20.sp)
    val Button = TextStyle(fontFamily = AuraFonts.Body, fontWeight = FontWeight.Bold,
        fontSize = 15.sp, lineHeight = 20.sp)
    val Chip = TextStyle(fontFamily = AuraFonts.Body, fontWeight = FontWeight.Bold,
        fontSize = 13.sp, lineHeight = 16.sp)
    /** 12/16, 700, +.03em */
    val Caption = TextStyle(fontFamily = AuraFonts.Body, fontWeight = FontWeight.Bold,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.03.em, color = AuraColors.Ink2)
    val Mono = TextStyle(fontFamily = AuraFonts.Mono, fontSize = 12.sp, lineHeight = 16.sp,
        color = AuraColors.Ink2)
}

internal val AuraMaterialTypography = Typography(
    displayLarge = AuraType.Display,
    headlineMedium = AuraType.TabTitle,
    titleMedium = AuraType.Section,
    bodyLarge = AuraType.Body,
    bodyMedium = AuraType.Secondary,
    bodySmall = AuraType.SecondarySmall,
    labelLarge = AuraType.Button,
    labelMedium = AuraType.Chip,
    labelSmall = AuraType.Caption,
)
