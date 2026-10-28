package ai.aura.app.ui.screens.voice

import android.content.SharedPreferences

/** Como o microfone se comporta (Voz-5-Modos.dc.html). */
enum class VoiceMode { PushToTalk, Conversation, HandsFree }

/**
 * Escolhas da tela "Como o microfone funciona". Só preferência de UI (nunca segredo).
 * [autoSend], [autoResume] e [screenOff] começam ligados; [wakeName] (chamar pelo nome AURA) começa desligado.
 */
data class VoiceSettings(
    val mode: VoiceMode = VoiceMode.PushToTalk,
    val autoSend: Boolean = true,
    val autoResume: Boolean = true,
    val screenOff: Boolean = true,
    val wakeName: Boolean = false,
) {
    /** Envia sozinho após ~1,5 s de silêncio, mas só nos modos que ouvem sem parar. */
    val sendsOnSilence: Boolean get() = autoSend && mode != VoiceMode.PushToTalk
}

/** Guarda e lê [VoiceSettings] nas preferências do app (aura_prefs). Valor estranho volta ao padrão. */
class VoiceSettingsStore(private val prefs: SharedPreferences) {
    fun load(): VoiceSettings {
        val d = VoiceSettings()
        val mode = runCatching { VoiceMode.valueOf(prefs.getString(KEY_MODE, d.mode.name) ?: d.mode.name) }.getOrDefault(d.mode)
        return VoiceSettings(
            mode = mode,
            autoSend = prefs.getBoolean(KEY_AUTO_SEND, d.autoSend),
            autoResume = prefs.getBoolean(KEY_AUTO_RESUME, d.autoResume),
            screenOff = prefs.getBoolean(KEY_SCREEN_OFF, d.screenOff),
            wakeName = prefs.getBoolean(KEY_WAKE_NAME, d.wakeName),
        )
    }

    fun save(settings: VoiceSettings) {
        prefs.edit()
            .putString(KEY_MODE, settings.mode.name)
            .putBoolean(KEY_AUTO_SEND, settings.autoSend)
            .putBoolean(KEY_AUTO_RESUME, settings.autoResume)
            .putBoolean(KEY_SCREEN_OFF, settings.screenOff)
            .putBoolean(KEY_WAKE_NAME, settings.wakeName)
            .apply()
    }

    private companion object {
        const val KEY_MODE = "voice_mode"
        const val KEY_AUTO_SEND = "voice_auto_send"
        const val KEY_AUTO_RESUME = "voice_auto_resume"
        const val KEY_SCREEN_OFF = "voice_screen_off"
        const val KEY_WAKE_NAME = "voice_wake_name"
    }
}
