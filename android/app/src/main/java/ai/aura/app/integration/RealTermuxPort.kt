package ai.aura.app.integration

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
    override suspend fun engineUp(): Boolean {
        val result = gateway.health()
        return result is EngineResult.Ok && result.value.service == "aura-engine"
    }
}
