package ai.aura.app.ui.screens.voice

import ai.aura.app.ui.nav.ROUTES
import ai.aura.app.ui.nav.ScreenEnv
import ai.aura.app.voice.AndroidSpeechRecognizer
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Texto já dito que passa da voz em tela cheia para a compacta (e de volta): minimizar não perde nada. */
internal object VoiceHandoff {
    var text by mutableStateOf("")
}

private const val PREFS = "aura_prefs"

@Composable
private fun rememberVoiceSettings(): VoiceSettings {
    val context = LocalContext.current
    return remember { VoiceSettingsStore(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).load() }
}

@Composable
private fun rememberMicGranted(): Pair<Boolean, () -> Unit> {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    return granted to { launcher.launch(Manifest.permission.RECORD_AUDIO) }
}

/** Voz-1/Voz-2 no app: reconhecedor do Android, microfone pedido na hora do toque. Enviar/Teclado levam o texto ao Início. */
@Composable
internal fun VoiceListeningRoute(env: ScreenEnv, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = rememberVoiceSettings()
    val recognizer = remember { AndroidSpeechRecognizer(context.applicationContext) }
    val (granted, askMic) = rememberMicGranted()
    VoiceScreen(
        recognizer = recognizer, micGranted = granted, settings = settings,
        onNeedMicrophone = askMic,
        onKeyboard = { text -> VoiceHandoff.text = ""; env.onDraftChange(text); env.back() },
        onSend = { text -> VoiceHandoff.text = ""; env.onDraftChange(text); env.back() },
        onCancel = { VoiceHandoff.text = ""; env.back() },
        onMinimize = { env.open("voz-compacta") },
        modifier = modifier,
        initialText = VoiceHandoff.text,
        onHeard = { VoiceHandoff.text = it },
    )
}

/** Voz-3 no app: o Início continua usável e a barra compacta ouve por cima, sem interromper o trabalho. */
@Composable
internal fun VoiceCompactRoute(env: ScreenEnv, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = rememberVoiceSettings()
    val recognizer = remember { AndroidSpeechRecognizer(context.applicationContext) }
    val (granted, _) = rememberMicGranted()
    val scope = rememberCoroutineScope()
    val latestSettings by rememberUpdatedState(settings)
    val session = remember(recognizer, granted) {
        VoiceSession(
            recognizer, scope, { latestSettings },
            { text -> VoiceHandoff.text = ""; env.onDraftChange(text); env.back() },
            VoiceHandoff.text,
        )
    }
    DisposableEffect(session) {
        if (granted && recognizer.available) session.start()
        onDispose { session.close() }
    }
    LaunchedEffect(session.heard) { VoiceHandoff.text = session.heard }
    Box(modifier.fillMaxSize()) {
        ROUTES.firstOrNull { it.id == "inicio" }?.screen?.invoke(env)
        VoiceCompact(
            heard = session.heard, queue = emptyList(),
            onExpand = { env.open("voz-ouvindo") },
            onStop = { session.close(); env.back() },
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }
}

/** Voz-5 no app: lê e grava as escolhas em aura_prefs. */
@Composable
internal fun VoiceModesRoute(env: ScreenEnv, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { VoiceSettingsStore(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)) }
    VoiceModesScreen(store.load(), { store.save(it) }, env.back, modifier)
}

/** Voz-4 no app: sem pedido de voz em andamento mostra a mensagem e a ação "Falar um pedido". */
@Composable
internal fun VoiceTriageRoute(env: ScreenEnv, modifier: Modifier = Modifier) {
    TriageScreen(state = null, onApply = {}, onBack = env.back, onSpeak = { env.open("voz-ouvindo") }, modifier = modifier)
}
