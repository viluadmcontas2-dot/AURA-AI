package ai.aura.app.ui.screens.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val SILENCE_SEND_MS = 1_500L
internal const val RESUME_SECONDS = 3

internal enum class VoicePhase { Listening, Interrupted }

/** Junta trechos falados com um espaço, sem sobrar espaço nas pontas. */
internal fun joinSpeech(a: String, b: String): String =
    listOf(a.trim(), b.trim()).filter { it.isNotEmpty() }.joinToString(" ")

/**
 * Estado de uma conversa por voz, sem desenho: o que já foi dito, a interrupção e a volta sozinha em 3 s.
 * Interrompeu: o texto fica guardado ("nada se perde"). Modo que envia sozinho: 1,5 s de silêncio envia.
 * Os tempos usam [scope] (na tela, o escopo da composição), então o teste controla o relógio.
 */
internal class VoiceSession(
    private val recognizer: SpeechRecognizerPort,
    private val scope: CoroutineScope,
    private val settings: () -> VoiceSettings,
    private val send: (String) -> Unit,
    initialText: String = "",
) {
    var phase by mutableStateOf(VoicePhase.Listening)
        private set
    var committed by mutableStateOf(initialText.trim())
        private set
    var partial by mutableStateOf("")
        private set
    var reason by mutableStateOf("")
        private set
    var resumeIn by mutableIntStateOf(0)
        private set

    /** Tudo que foi dito até agora (guardado + o que está sendo falado). */
    val heard: String get() = joinSpeech(committed, partial)

    private var closed = false
    private var silenceJob: Job? = null
    private var resumeJob: Job? = null

    private val listener = object : SpeechRecognizerPort.Listener {
        override fun onPartial(text: String) {
            if (closed || phase != VoicePhase.Listening) return
            partial = text.trim()
            armSilence()
        }

        override fun onFinal(text: String) {
            if (closed || phase != VoicePhase.Listening) return
            committed = joinSpeech(committed, text)
            partial = ""
            armSilence()
        }

        override fun onInterrupted(reason: String) = interrupt(reason)
    }

    fun start() {
        if (closed) return
        phase = VoicePhase.Listening
        recognizer.start(listener)
    }

    /** "Voltar a ouvir agora" (ou fim dos 3 s). */
    fun resumeNow() {
        resumeJob?.cancel()
        resumeJob = null
        resumeIn = 0
        if (closed) return
        partial = ""
        phase = VoicePhase.Listening
        recognizer.start(listener)
    }

    /** Para de ouvir sem fechar a sessão (o texto continua valendo para Enviar). */
    fun pause() {
        silenceJob?.cancel()
        resumeJob?.cancel()
        recognizer.stop()
    }

    fun sendNow() {
        val text = heard.trim()
        if (closed || text.isEmpty()) return
        close()
        send(text)
    }

    /** Solta o microfone e cancela os tempos. Não chama ninguém. */
    fun close() {
        closed = true
        silenceJob?.cancel()
        resumeJob?.cancel()
        recognizer.stop()
    }

    private fun armSilence() {
        silenceJob?.cancel()
        silenceJob = null
        if (!settings().sendsOnSilence || heard.isBlank()) return
        silenceJob = scope.launch {
            delay(SILENCE_SEND_MS)
            silenceJob = null
            sendNow()
        }
    }

    private fun interrupt(why: String) {
        if (closed || phase != VoicePhase.Listening) return
        silenceJob?.cancel()
        silenceJob = null
        committed = joinSpeech(committed, partial)
        partial = ""
        reason = why
        phase = VoicePhase.Interrupted
        recognizer.stop()
        resumeJob?.cancel()
        if (!settings().autoResume) {
            resumeIn = 0
            return
        }
        resumeIn = RESUME_SECONDS
        resumeJob = scope.launch {
            repeat(RESUME_SECONDS) { i ->
                delay(1_000)
                resumeIn = RESUME_SECONDS - 1 - i
            }
            resumeNow()
        }
    }
}
