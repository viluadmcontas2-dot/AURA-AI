package ai.aura.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ai.aura.app.integration.AuraEngineClient
import ai.aura.app.integration.EngineCredentialStore
import ai.aura.app.integration.RealEngineGateway
import ai.aura.app.integration.RealTermuxPort
import ai.aura.app.integration.asReformTransport
import ai.aura.app.ui.screens.onboarding.LocalTermuxFlow
import ai.aura.app.ui.screens.onboarding.TermuxFlow
import androidx.compose.runtime.rememberCoroutineScope
import ai.aura.app.integration.TermuxBridge
import ai.aura.app.offline.EngineLink
import ai.aura.app.offline.LocalStore
import ai.aura.app.offline.OfflineFirstGateway
import ai.aura.app.offline.OfflineReason
import ai.aura.app.permissions.AppPermissions
import ai.aura.app.ui.AuraApp
import ai.aura.app.voice.LocalVoiceInput
import ai.aura.app.voice.SpokenText

/** Native AURA shell. No simulated provider results are ever shown in production. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Guarda o último erro que fechou o app, para mostrar na próxima abertura (sem logcat).
        val crashFile = java.io.File(filesDir, "last_crash.txt")
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { crashFile.writeText(error.stackTraceToString().take(6000)) }
            previousHandler?.uncaughtException(thread, error)
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        // Só preferências de UI (onboarding e rascunho). Nunca token ou segredo aqui.
        val prefs = getSharedPreferences("aura_prefs", MODE_PRIVATE)
        // Permissões no primeiro uso, no passo certo da abertura (Termux no passo do Termux, avisos em Contas) e depois
        // pela tela Conexões. Negar nunca trava o app. Microfone: só ao tocar na voz (ui/screens/voice).
        val notificationsKey = AppPermissions.denialKey(android.Manifest.permission.POST_NOTIFICATIONS)
        val notificationsLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (!granted) prefs.edit().putInt(notificationsKey, prefs.getInt(notificationsKey, 0) + 1).apply()
        }
        val askNotifications = {
            val ask = android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                prefs.getInt(notificationsKey, 0) < AppPermissions.MAX_DENIALS
            if (ask) runCatching { notificationsLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
            Unit
        }
        // Voz: tela de reconhecimento do próprio Android (não depende do Termux nem do motor).
        var onSpoken: ((String) -> Unit)? = null
        val voiceLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
        ) { r ->
            val text = SpokenText.from(r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS))
            if (text != null) onSpoken?.invoke(text)
            else android.widget.Toast.makeText(this, "Não ouvi nada. Toque e fale de novo.", android.widget.Toast.LENGTH_SHORT).show()
        }
        val voiceIntent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Fale com o AURA")
        val voiceAvailable = voiceIntent.resolveActivity(packageManager) != null
        val voiceHook: (((String) -> Unit) -> Unit)? = if (!voiceAvailable) null else { cb ->
            onSpoken = cb
            runCatching { voiceLauncher.launch(voiceIntent) }
        }
        setContent {
            // Motor real guardado em disco (sem ele o app mostra o último dado) e o estado do vínculo para o banner.
            val bridge = remember { TermuxBridge(applicationContext) }
            val link = remember { EngineLink(bridge) }
            val engineClient = remember { AuraEngineClient(EngineCredentialStore(applicationContext)) }
            val realGateway = remember { RealEngineGateway(engineClient) }
            val settingsGateway = remember { ai.aura.app.integration.RealSettingsGateway(engineClient) }
            val queueGateway = remember { ai.aura.app.integration.RealQueueGateway(engineClient) }
            val gateway = remember {
                OfflineFirstGateway(
                    realGateway,
                    LocalStore(java.io.File(filesDir, "aura")),
                    link,
                )
            }
            val scope = rememberCoroutineScope()
            val port = remember { RealTermuxPort(this, bridge, realGateway) }
            val termuxFlow = remember { TermuxFlow(port, link, scope) }
            val openUrl: (String) -> Unit = { url ->
                val uri = runCatching { Uri.parse(url) }.getOrNull()
                if (uri?.scheme == "https" && !uri.host.isNullOrBlank()) {
                    // Sem navegador instalado o app não fecha.
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }.onFailure {
                        android.widget.Toast.makeText(this, "Não foi possível abrir o navegador. Instale ou habilite um navegador e tente de novo.", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
            // Reforma de apps: ponte com o motor, rede (dados móveis nunca baixam pacote) e cópia do APK para o Termux.
            val reformGateway = remember { ai.aura.app.integration.RealReformGateway(engineClient.asReformTransport()) }
            val networkReporter = remember {
                getSystemService(android.net.ConnectivityManager::class.java)?.let { cm ->
                    ai.aura.app.integration.NetworkReporter(cm, scope, { reformGateway.reportNetwork(it.metered, it.transport) })
                }
            }
            androidx.compose.runtime.DisposableEffect(networkReporter) {
                networkReporter?.start()
                onDispose { networkReporter?.stop() }
            }
            val reformController = remember {
                ai.aura.app.ui.screens.reform.ReformController(
                    reformGateway, ai.aura.app.integration.SharedApkCopier(applicationContext),
                    object : ai.aura.app.ui.screens.reform.ReformIdStore {
                        override var reformId: String?
                            get() = prefs.getString("reform_id", null)
                            set(value) { prefs.edit().putString("reform_id", value).apply() }
                    },
                    scope, beforeInstall = { networkReporter?.flush() },
                )
            }
            var lastCrash by remember { mutableStateOf(runCatching { crashFile.takeIf { it.exists() }?.readText() }.getOrNull()) }
            androidx.compose.runtime.CompositionLocalProvider(
                LocalVoiceInput provides voiceHook, LocalTermuxFlow provides termuxFlow,
                ai.aura.app.ui.screens.reform.LocalReformController provides reformController,
            ) {
            AuraApp(
                lastCrash = lastCrash,
                onDismissCrash = {
                    runCatching { crashFile.delete() }
                    lastCrash = null
                },
                gateway = gateway,
                settingsGateway = settingsGateway,
                queueGateway = queueGateway,
                onOpenUrl = openUrl,
                onRequestTermuxPermission = {
                    runCatching { TermuxBridge(this).askPermission(this) }
                },
                onRequestNotifications = askNotifications,
                onOpenTermux = {
                    runCatching {
                        packageManager.getLaunchIntentForPackage("com.termux")?.let { startActivity(it) }
                    }
                },
                // Sinais locais do aparelho: Termux instalado e permissão RUN_COMMAND concedida.
                termuxInstalled = {
                    runCatching { packageManager.getLaunchIntentForPackage("com.termux") != null }.getOrDefault(false)
                },
                termuxPermissionGranted = {
                    runCatching { TermuxBridge(this).hasPermission() }.getOrDefault(false)
                },
                onboardingDone = prefs.getBoolean("onboarding_done", false),
                onOnboardingFinished = { prefs.edit().putBoolean("onboarding_done", true).apply() },
                initialDraft = prefs.getString("draft", "") ?: "",
                onSaveDraft = { prefs.edit().putString("draft", it).apply() },
                linkState = link.state,
                // Uma ação por motivo, com o que a ponte já sabe fazer; o fluxo completo (feedback, tempo limite) é do T6.
                onEngineAction = { reason ->
                    when (reason) {
                        OfflineReason.NoTermux -> openUrl("https://f-droid.org/packages/com.termux/")
                        OfflineReason.NoPermission -> runCatching { bridge.askPermission(this) }
                        OfflineReason.NoToken -> termuxFlow.confirmPasted()
                        OfflineReason.EngineStopped -> termuxFlow.startEngine()
                    }
                },
            )
            }
        }
    }
}
