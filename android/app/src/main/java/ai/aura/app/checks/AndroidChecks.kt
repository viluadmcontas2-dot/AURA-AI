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

/**
 * As verificações com o aparelho de verdade. O motor entra só por [engineUp] (GET /v1/health) e por [engine];
 * sem motor, contas e ADB falham de verdade (viram "não consegui verificar") em vez de mostrar um cache.
 */
fun androidChecks(context: Context, termux: TermuxProbe, engine: EngineGateway, engineUp: suspend () -> Boolean): List<Check> {
    val app = context.applicationContext
    suspend fun needEngine() = check(engineUp()) { "motor desligado" }
    return standardChecks(
        termux = termux,
        engineUp = engineUp,
        accounts = { needEngine(); engine.authStatus() },
        adbInstalled = { needEngine(); engine.capabilities().any { it.id == "adb" && it.available } },
        notificationsOn = { app.getSystemService(NotificationManager::class.java).areNotificationsEnabled() },
        freeBytes = { StatFs(app.filesDir.path).availableBytes },
        ignoresBatteryLimits = { app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName) },
        sdk = Build.VERSION.SDK_INT,
        pageSize = { Os.sysconf(OsConstants._SC_PAGESIZE).toInt() },
    )
}
