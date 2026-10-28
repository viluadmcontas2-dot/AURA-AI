package ai.aura.app.ui.screens.voice

/**
 * Porta do reconhecimento de voz: a tela fala só com ela. O app usa o SpeechRecognizer do Android
 * (ai.aura.app.voice.AndroidSpeechRecognizer); o teste usa um falso. Sem API paga, sem motor.
 */
interface SpeechRecognizerPort {
    /** false = o aparelho não tem reconhecimento de voz. */
    val available: Boolean

    /** Começa a ouvir. Chamado só por um toque da pessoa. */
    fun start(listener: Listener)

    /** Para de ouvir e solta o microfone. Pode ser chamado sem ter começado. */
    fun stop()

    interface Listener {
        /** Texto ainda sendo falado (muda a cada palavra). */
        fun onPartial(text: String)

        /** Trecho terminado. */
        fun onFinal(text: String)

        /** A escuta caiu (ligação, outro app, Bluetooth, erro). [reason] é uma frase para mostrar. */
        fun onInterrupted(reason: String)
    }
}
