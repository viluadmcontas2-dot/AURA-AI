package ai.aura.app.checks

import ai.aura.app.offline.TermuxProbe
import ai.aura.app.ui.engine.EngineGateway
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.CancellationException

/**
 * As verificações com o aparelho de verdade. O motor entra só por [engine]; sem ele, contas e ADB falham de
 * verdade (viram "não consegui verificar") em vez de mostrar o que estava guardado.
 */
fun androidChecks(context: Context, termux: TermuxProbe, engine: EngineGateway): List<Check> {
    val app = context.applicationContext
    // O mesmo critério do onboarding: o motor está no ar quando a capacidade "termux" volta disponível.
    suspend fun engineUp() = try {
        engine.capabilities().any { it.id == "termux" && it.available }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
    suspend fun needEngine() = check(engineUp()) { "motor desligado" }
    return standardChecks(
        termux = termux,
        engineUp = { engineUp() },
        accounts = { needEngine(); engine.authStatus() },
        adbInstalled = { needEngine(); engine.capabilities().any { it.id == "adb" && it.available } },
        notificationsOn = { app.getSystemService(NotificationManager::class.java).areNotificationsEnabled() },
        freeBytes = { StatFs(app.filesDir.path).availableBytes },
        ignoresBatteryLimits = { app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName) },
        sdk = Build.VERSION.SDK_INT,
        pageSize = { Os.sysconf(OsConstants._SC_PAGESIZE).toInt() },
    )
}
