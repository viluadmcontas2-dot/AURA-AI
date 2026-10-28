package ai.aura.app.voice

import androidx.compose.runtime.staticCompositionLocalOf

object SpokenText {
    /** Primeira hipótese do reconhecedor de voz, sem espaços nas pontas; null se não veio nada útil. */
    fun from(results: List<String>?): String? = results?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}

/** Gancho de voz da Activity: recebe o que fazer com o texto falado. Null = aparelho sem voz. */
val LocalVoiceInput = staticCompositionLocalOf<(((String) -> Unit) -> Unit)?> { null }
