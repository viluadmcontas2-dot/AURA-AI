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
import ai.aura.app.integration.TermuxBridge
import ai.aura.app.ui.AuraApp

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
        setContent {
            val gateway = remember {
                RealEngineGateway(AuraEngineClient(EngineCredentialStore(applicationContext)))
            }
            var lastCrash by remember { mutableStateOf(runCatching { crashFile.takeIf { it.exists() }?.readText() }.getOrNull()) }
            AuraApp(
                lastCrash = lastCrash,
                onDismissCrash = {
                    runCatching { crashFile.delete() }
                    lastCrash = null
                },
                gateway = gateway,
                onOpenUrl = { url ->
                    val uri = runCatching { Uri.parse(url) }.getOrNull()
                    if (uri?.scheme == "https" && !uri.host.isNullOrBlank()) {
                        // Sem navegador instalado o app não fecha.
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    }
                },
                onRequestTermuxPermission = {
                    runCatching { TermuxBridge(this).askPermission(this) }
                },
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
            )
        }
    }
}
