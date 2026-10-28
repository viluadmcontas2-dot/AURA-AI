package ai.aura.app.notifications

import ai.aura.app.integration.AuraEngineClient
import ai.aura.app.integration.EngineCredentialStore
import ai.aura.app.integration.RealEngineGateway
import ai.aura.app.ui.engine.EngineGateway
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val EXTRA_KIND = "ai.aura.app.extra.KIND"
private const val EXTRA_CHANNEL = "ai.aura.app.extra.CHANNEL"
private const val EXTRA_NOTIFICATION_ID = "ai.aura.app.extra.NOTIFICATION_ID"
private const val EXTRA_DESTRUCTIVE = "ai.aura.app.extra.DESTRUCTIVE"

/** O Android dá cerca de 10 s a um receptor; o motor é local, então 8 s bastam. */
private const val ACTION_TIMEOUT_MILLIS = 8_000L

/** O que um botão do aviso pede (Aprovar, Pausar ou Parar; Ver e Abrir abrem o app e nunca passam por aqui). */
data class ActionRequest(
    val kind: ActionKind,
    val jobId: String?,
    val notificationId: Int,
    val channel: AuraChannel,
    val destructive: Boolean,
) {
    fun toIntent(context: Context): Intent =
        Intent(context, NotificationActionReceiver::class.java)
            .setData(Uri.parse("aura://notification/$notificationId/${kind.name}"))
            .putExtra(EXTRA_KIND, kind.name)
            .putExtra(NotificationIntents.EXTRA_JOB_ID, jobId)
            .putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            .putExtra(EXTRA_CHANNEL, channel.name)
            .putExtra(EXTRA_DESTRUCTIVE, destructive)

    companion object {
        fun from(intent: Intent): ActionRequest? {
            val kind = ActionKind.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_KIND) } ?: return null
            val channel = AuraChannel.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_CHANNEL) } ?: return null
            return ActionRequest(
                kind,
                intent.getStringExtra(NotificationIntents.EXTRA_JOB_ID),
                intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0),
                channel,
                intent.getBooleanExtra(EXTRA_DESTRUCTIVE, false),
            )
        }
    }
}

/**
 * Faz o que o botão pede. true = feito (o aviso some); false = não fez (o aviso fica e o dono abre o app).
 * Aprovar de algo que apaga ou gasta é recusado aqui também: o botão certo já abre o app, isto é o segundo cinto.
 * Pausar de uma tarefa cancela: o motor ainda não tem "pausar" de verdade.
 */
internal suspend fun runAction(request: ActionRequest, gateway: EngineGateway, stopListening: () -> Unit): Boolean {
    if (request.channel == AuraChannel.Listening) {
        stopListening()
        return true
    }
    val job = request.jobId ?: return false
    if (request.kind == ActionKind.Approve && request.destructive) return false
    return try {
        when (request.kind) {
            ActionKind.Approve -> gateway.approve(job)
            ActionKind.Pause, ActionKind.Stop -> gateway.cancel(job)
            ActionKind.View -> return false
        }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val request = ActionRequest.from(intent) ?: return
        val app = context.applicationContext
        val pending = goAsync() // fora de um broadcast de verdade (testes) vem nulo
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Sem credencial ou sem motor: o aviso fica, e o app já mostra o que fazer quando abrir.
                val done = runCatching {
                    withTimeoutOrNull(ACTION_TIMEOUT_MILLIS) {
                        val gateway = RealEngineGateway(AuraEngineClient(EngineCredentialStore(app)))
                        runAction(request, gateway) { AuraForegroundService.stop(app) }
                    }
                }.getOrNull() == true
                if (done) AuraNotifier(app).cancel(request.notificationId)
            } finally {
                pending?.finish()
            }
        }
    }
}
