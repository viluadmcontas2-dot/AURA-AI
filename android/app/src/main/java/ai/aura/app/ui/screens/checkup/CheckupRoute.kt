package ai.aura.app.ui.screens.checkup

import ai.aura.app.checks.androidChecks
import ai.aura.app.integration.TermuxBridge
import ai.aura.app.offline.TermuxProbe
import ai.aura.app.ui.engine.EngineGateway
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

private const val TERMUX_URL = "https://f-droid.org/packages/com.termux/"
private const val PHANTOM_PROCESSES_URL =
    "https://github.com/agnostic-apollo/Android-Docs/blob/master/en/docs/apps/processes/phantom-cached-and-empty-processes.md"

/**
 * Liga o Checkup ao aparelho: as verificações de verdade e, para cada id, a ação que corrige. Contas e o
 * Termux ainda usam o que existe hoje (Ajustes, F-Droid); o fluxo completo do Termux é da T6.
 */
@Composable
fun CheckupRoute(
    gateway: EngineGateway,
    termuxInstalled: () -> Boolean,
    termuxPermissionGranted: () -> Boolean,
    onRequestTermuxPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val checks = remember(gateway) {
        val termux = object : TermuxProbe {
            override fun installed() = termuxInstalled()
            override fun hasPermission() = termuxPermissionGranted()
        }
        androidChecks(context, termux, gateway)
    }
    CheckupScreen(checks, onBack = onBack, onFix = { id ->
        when (id) {
            "termux" -> onOpenUrl(TERMUX_URL)
            "run_command" -> onRequestTermuxPermission()
            "engine" -> runCatching { TermuxBridge(context).startEngine() }
            "accounts" -> onOpenSettings()
            "notifications" -> context.openSystem(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
            "storage" -> context.openSystem(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
            "battery" -> context.openSystem(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            "long_tasks" -> onOpenUrl(PHANTOM_PROCESSES_URL)
            else -> Unit // os que só copiam (external_apps, adb, termux_compat) nunca chegam aqui
        }
    })
}

/** Abre a tela do sistema; se o aparelho não tiver essa, cai nos Ajustes gerais (nunca botão mudo). */
private fun Context.openSystem(intent: Intent) {
    runCatching { startActivity(intent) }.onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
}
