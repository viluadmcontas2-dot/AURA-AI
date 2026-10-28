package ai.aura.app.integration

import ai.aura.app.offline.TermuxProbe
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import ai.aura.app.offline.TermuxResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Secure Android -> Termux command boundary. Never shell-interpolate user or model text. */
class TermuxBridge(private val context: Context) : TermuxProbe {
    companion object {
        private const val TERMUX_PACKAGE = "com.termux"
        private const val SERVICE = "com.termux.app.RunCommandService"
        private const val PYTHON = "/data/data/com.termux/files/usr/bin/python"
        private const val HOME = "/data/data/com.termux/files/home"
        private const val WORKDIR = "$HOME/.aura/app"
        private const val BASH = "/data/data/com.termux/files/usr/bin/bash"
        private const val CHUNK = 60_000
        private val nextRequestId = AtomicInteger(1001)
        const val PERMISSION = "com.termux.permission.RUN_COMMAND"
        const val ENGINE_ASSET = "engine.tar.gz"
        // Instala o Python só se faltar, apaga a cópia antiga do motor e extrai a nova. Termina com a marca de sucesso.
        private val INSTALL_SCRIPT = """
            export PATH=/data/data/com.termux/files/usr/bin:${'$'}PATH
            H=/data/data/com.termux/files/home
            command -v python >/dev/null 2>&1 || { yes | pkg install -y python || exit 1; }
            rm -rf "${'$'}H/.aura/app/aura_engine"
            mkdir -p "${'$'}H/.aura/app" || exit 1
            printf %s "${'$'}@" | base64 -d | tar xz -C "${'$'}H/.aura/app" || exit 1
            test -f "${'$'}H/.aura/app/aura_engine/__main__.py" || exit 1
            echo $ENGINE_INSTALLED_MARK
        """.trimIndent()
    }

    override fun installed(): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) { false }

    override fun hasPermission(): Boolean =
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
        TermuxReplies.pending[id] = TermuxReply("pair")
        try {
            checkNotNull(context.startService(command))
        } catch (error: Exception) {
            TermuxReplies.pending.remove(id)
            pending.cancel()
            throw error
        }
        PairState.message.value = "Verificando autorização do Termux..."
    }

    fun startEngine() {
        require(installed()) { "Termux ausente" }
        require(hasPermission()) { "Permissão Termux pendente" }
        context.startService(baseIntent(arrayOf("-m", "aura_engine")))
        PairState.message.value = "Motor solicitado. Conferindo a conexão..."
    }

    suspend fun probe(): TermuxResult = withTimeoutOrNull(1_500L) {
        awaitCommand("probe", baseIntent(arrayOf("aura-ok")).apply {
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/echo")
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
        })
    } ?: TermuxResult.Failed

    suspend fun pairAndWait(): TermuxResult = withTimeoutOrNull(15_000L) {
        awaitCommand("pair", baseIntent(arrayOf("-m", "aura_engine.pair", "--emit-token")))
    } ?: TermuxResult.Failed

    // O processo do motor só responde ao callback quando termina; o fluxo confere a saúde em paralelo.
    suspend fun runEngine(): TermuxResult =
        awaitCommand("start", baseIntent(arrayOf("-m", "aura_engine")))

    /** Motor embutido (assets/engine.tar.gz) -> ~/.aura/app no Termux. O tar viaja em argumentos base64: sem rede e sem git. */
    suspend fun installEngine(): TermuxResult {
        val packed = try {
            context.assets.open(ENGINE_ASSET).use { android.util.Base64.encodeToString(it.readBytes(), android.util.Base64.NO_WRAP) }
        } catch (_: Exception) { return TermuxResult.Failed }
        val chunks = packed.chunked(CHUNK).toTypedArray()
        val intent = baseIntent(arrayOf("-c", INSTALL_SCRIPT, "aura-install") + chunks).apply {
            putExtra("com.termux.RUN_COMMAND_PATH", BASH)
            putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
        }
        return awaitCommand("install", intent)
    }

    private suspend fun awaitCommand(operation: String, command: Intent): TermuxResult {
        if (!installed()) return TermuxResult.Failed
        if (!hasPermission()) return TermuxResult.NotAllowed
        val id = nextRequestId.getAndIncrement()
        val reply = TermuxReply(operation)
        TermuxReplies.pending[id] = reply
        val flags = PendingIntent.FLAG_ONE_SHOT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pending = PendingIntent.getBroadcast(context, id,
            Intent(context, PairResultReceiver::class.java).putExtra("pairId", id), flags)
        try {
            command.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending)
            try {
                if (context.startService(command) == null) return TermuxResult.Failed
            } catch (_: SecurityException) {
                return TermuxResult.NotAllowed
            } catch (_: Exception) {
                return TermuxResult.Failed
            }
            return reply.result.await()
        } finally {
            TermuxReplies.pending.remove(id)
            pending.cancel()
        }
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

private class TermuxReply(val operation: String) {
    val result = CompletableDeferred<TermuxResult>()
}

private object TermuxReplies {
    val pending = ConcurrentHashMap<Int, TermuxReply>()
}

/** Só aceita respostas de pedidos em andamento; cancelamentos descartam callbacks tardios. */
class PairResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reply = TermuxReplies.pending.remove(intent.getIntExtra("pairId", -1)) ?: return
        val bundle = intent.getBundleExtra("result")
        val exit = if (bundle?.containsKey("exitCode") == true) bundle.getInt("exitCode") else null
        val result = termuxResultFrom(
            reply.operation, exit, bundle?.getInt("err", -1) ?: -999,
            bundle?.getString("errmsg"), bundle?.getString("stdout"),
        ) { EngineCredentialStore(context).save(it) }
        if (reply.operation == "pair") {
            PairState.paired.value = result == TermuxResult.Ok
            PairState.message.value = when (result) {
                TermuxResult.Ok -> "Termux conectado. Credencial protegida no Android."
                TermuxResult.NotAllowed -> "O Termux recusou comandos. Copie o comando e cole no Termux."
                TermuxResult.Failed -> "Pareamento falhou. Confira o Python e a pasta AURA-AI no Termux e tente de novo."
            }
        }
        reply.result.complete(result)
    }
}
