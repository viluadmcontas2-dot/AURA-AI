package ai.aura.app.integration

import ai.aura.app.offline.EngineProbe
import ai.aura.app.offline.NetworkKind
import ai.aura.app.offline.TermuxPort
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.EngineResult
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.system.Os
import android.system.OsConstants

/** Ações reais do Android e saúde autenticada do motor existente. */
class RealTermuxPort(
    private val activity: Activity,
    private val bridge: TermuxBridge,
    private val gateway: EngineGateway,
) : TermuxPort {
    override fun installed() = bridge.installed()
    override fun hasPermission() = bridge.hasPermission()
    override fun isPaired() = EngineCredentialStore(activity).read() != null
    override fun pageSize() = Os.sysconf(OsConstants._SC_PAGESIZE).toInt()
    override fun requestPermission() = bridge.askPermission(activity)
    override fun permissionBlocked() = bridge.permissionBlocked()
    override fun openInstaller() {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/")))
    }
    override fun openTermux() {
        val launch = activity.packageManager.getLaunchIntentForPackage("com.termux")
            ?: throw IllegalStateException("Instale o Termux e tente de novo.")
        activity.startActivity(launch)
    }
    override suspend fun probe() = bridge.probe()
    override suspend fun pairAndWait() = bridge.pairAndWait()
    override suspend fun runEngine() = bridge.runEngine()
    override suspend fun installEngine() = bridge.installEngine()

    /** Já preparado por este app, ou já pareado (o pareamento só existe com o motor instalado). */
    override fun engineInstalled() = bridge.engineMarked() || EngineCredentialStore(activity).read() != null
    override fun markEngineInstalled() = bridge.markEngineInstalled()
    override fun engineAssetBytes() = bridge.engineAssetBytes()
    override fun networkKind(): NetworkKind {
        val cm = activity.getSystemService(android.net.ConnectivityManager::class.java) ?: return NetworkKind.Unknown
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return NetworkKind.Unknown
        return when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.Wifi
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.Mobile
            else -> NetworkKind.Unknown
        }
    }
    override suspend fun engineUp(): Boolean {
        val result = gateway.health()
        return result is EngineResult.Ok && result.value.service == "aura-engine"
    }

    override suspend fun engineProbe(): EngineProbe = when (val result = gateway.health()) {
        is EngineResult.Ok -> if (result.value.service == "aura-engine") EngineProbe.Up(result.value.build) else EngineProbe.Down
        is EngineResult.Rejected -> if (result.code == "unauthorized") EngineProbe.Unauthorized else EngineProbe.Down
        is EngineResult.Unavailable -> EngineProbe.Down
    }

    /** Hash gravado em assets/engine.build na hora do build (mesmo cálculo do motor sobre aura_engine/). */
    override fun expectedEngineBuild(): String? = try {
        activity.assets.open(TermuxBridge.ENGINE_BUILD_ASSET).use { it.readBytes().toString(Charsets.UTF_8).trim() }.ifEmpty { null }
    } catch (_: Exception) {
        null
    }

    override suspend fun stopEngine() = bridge.stopEngine()

    override fun forgetCredential() = EngineCredentialStore(activity).clear()
}
