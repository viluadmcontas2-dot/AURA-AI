package ai.aura.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val AuraLightScheme = lightColorScheme(
    primary = AuraColors.Ink,
    onPrimary = AuraColors.Surface,
    secondary = AuraColors.Link,
    onSecondary = AuraColors.Surface,
    background = AuraColors.Bg,
    onBackground = AuraColors.Ink,
    surface = AuraColors.Surface,
    onSurface = AuraColors.Ink,
    surfaceVariant = AuraColors.SurfaceSunken,
    onSurfaceVariant = AuraColors.Ink2,
    outline = AuraColors.LineStrong,
    outlineVariant = AuraColors.Line,
    error = AuraColors.RiskSolid,
    onError = AuraColors.Surface,
)

/** Tema do app. O app é claro por design; escuro existe só em Bastidores/Voz (AuraColors.Dark*). */
@Composable
fun AuraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AuraLightScheme,
        typography = AuraMaterialTypography,
        content = content,
    )
}
