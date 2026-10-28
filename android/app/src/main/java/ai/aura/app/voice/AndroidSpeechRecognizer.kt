package ai.aura.app.voice

import ai.aura.app.ui.screens.voice.SpeechRecognizerPort
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Reconhecimento de voz do próprio Android (SpeechRecognizer), em português do Brasil, preferindo o modo no aparelho.
 * Não usa motor, Termux nem API paga. Fica ouvindo em frases seguidas até [stop]; erro que não é "não entendi"
 * vira [SpeechRecognizerPort.Listener.onInterrupted] com uma frase para mostrar. Chamar sempre na thread principal.
 */
class AndroidSpeechRecognizer(private val context: Context) : SpeechRecognizerPort {
    private val main = Handler(Looper.getMainLooper())
    private var engine: SpeechRecognizer? = null
    private var listener: SpeechRecognizerPort.Listener? = null
    private var active = false

    /** Mesmo teste do botão de voz antigo: existe uma tela de reconhecimento de voz no aparelho. */
    override val available: Boolean
        get() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(context.packageManager) != null

    override fun start(listener: SpeechRecognizerPort.Listener) {
        stop()
        this.listener = listener
        active = true
        listen()
    }

    override fun stop() {
        active = false
        main.removeCallbacksAndMessages(null)
        engine?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        engine = null
    }

    private fun listen() {
        if (!active) return
        val sr = engine ?: runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        if (sr == null) {
            interrupt("Não consegui abrir o reconhecimento de voz.")
            return
        }
        if (engine == null) {
            sr.setRecognitionListener(Callbacks())
            engine = sr
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        runCatching { sr.startListening(intent) }.onFailure { interrupt("O reconhecimento de voz parou. Toque para voltar a ouvir.") }
    }

    /** Frase seguinte: o SpeechRecognizer entrega uma frase por vez, então recomeça sozinho. */
    private fun again() {
        if (active) main.postDelayed({ listen() }, RESTART_DELAY_MS)
    }

    private fun interrupt(reason: String) {
        active = false
        listener?.onInterrupted(reason)
    }

    private inner class Callbacks : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val text = SpokenText.from(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)) ?: return
            if (active) listener?.onPartial(text)
        }

        override fun onResults(results: Bundle?) {
            val text = SpokenText.from(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
            if (active && text != null) listener?.onFinal(text)
            again()
        }

        override fun onError(error: Int) {
            if (!active) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> again()
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> interrupt("O microfone não está liberado para o AURA.")
                SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    interrupt("O microfone ficou ocupado (ligação ou outro app).")
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                    interrupt("Sem conexão para entender a fala.")
                else -> interrupt("A escuta parou.")
            }
        }
    }

    private companion object {
        const val RESTART_DELAY_MS = 250L
    }
}
