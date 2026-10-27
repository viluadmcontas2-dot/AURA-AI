package ai.aura.app.integration

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/** Secure Android -> Termux command boundary. Never shell-interpolate user or model text. */
class TermuxBridge(private val context: Context) {
    companion object {
        private const val TERMUX_PACKAGE = "com.termux"
        private const val SERVICE = "com.termux.app.RunCommandService"
        private const val PYTHON = "/data/data/com.termux/files/usr/bin/python"
        private const val WORKDIR = "/data/data/com.termux/files/home/AURA-AI/engine"
        private val nextRequestId = AtomicInteger(1001)
        const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    }

    fun installed(): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) { false }

    fun hasPermission(): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun askPermission(activity: Activity) {
        if (!hasPermission()) activity.requestPermissions(arrayOf(PERMISSION), 707)
    }

    /** Intents contain only hardcoded python module paths, never user-provided command text. */
    fun pair() {
        require(installed()) { "Instale o Termux antes de conectar" }
        require(hasPermission()) { "Autorize o AURA a executar comandos no Termux" }
        val id = nextRequestId.getAndIncrement()
        val callback = Intent(context, PairResultReceiver::class.java)
            .putExtra("pairId", id)
        val flags = PendingIntent.FLAG_ONE_SHOT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pending = PendingIntent.getBroadcast(context, id, callback, flags)
        val command = baseIntent(arrayOf("-m", "aura_engine.pair", "--emit-token"))
        command.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending)
        context.startService(command)
        PairState.message.value = "Verificando autorização do Termux..."
    }

    fun startEngine() {
        require(installed()) { "Termux ausente" }
        require(hasPermission()) { "Permissão Termux pendente" }
        context.startService(baseIntent(arrayOf("-m", "aura_engine")))
        PairState.message.value = "Motor solicitado. Conferindo a conexão..."
    }

    private fun baseIntent(arguments: Array<String>): Intent =
        Intent("com.termux.RUN_COMMAND").apply {
            setClassName(TERMUX_PACKAGE, SERVICE)
            putExtra("com.termux.RUN_COMMAND_PATH", PYTHON)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arguments)
            putExtra("com.termux.RUN_COMMAND_WORKDIR", WORKDIR)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "AURA Engine")
        }
}

object PairState {
    val message = MutableStateFlow("Termux ainda não verificado")
    val paired = MutableStateFlow(false)
}

/** Receives one-shot Termux result; never writes the raw token to logs or UI. */
class PairResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val bundle = intent.getBundleExtra("result")
        val exit = bundle?.getInt("exitCode", -999) ?: -999
        val internalError = bundle?.getInt("err", -1) ?: -999
        if (exit != 0 || internalError != -1) {
            PairState.message.value = "Termux não concluiu o pareamento. Verifique a permissão e o diretório AURA-AI."
            return
        }
        val stdout = bundle.getString("stdout", "")
        try {
            val obj = JSONObject(stdout.trim())
            require(obj.getString("service") == "aura-engine")
            val token = obj.getString("token")
            EngineCredentialStore(context).save(token)
            PairState.paired.value = true
            PairState.message.value = "Termux conectado. Credencial protegida no Android."
        } catch (_: Exception) {
            PairState.message.value = "Resposta de pareamento inválida. O token não foi armazenado."
        }
    }
}
