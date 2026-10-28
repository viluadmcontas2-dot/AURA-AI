package ai.aura.app.notifications

import ai.aura.app.MainActivity
import ai.aura.app.R
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Os quatro canais do design (Notificacoes.dc.html). [destination] diz ao app o que abrir ao tocar no aviso. */
enum class AuraChannel(
    val id: String,
    val label: String,
    val switchTitle: String,
    val about: String,
    val importance: Int,
    val destination: String,
) {
    Working(
        "aura_working", "Enquanto trabalha", "Mostrar o que estou fazendo",
        "Passo atual de cada tarefa em andamento. Sem som.", NotificationManagerCompat.IMPORTANCE_LOW, "task",
    ),
    NeedsYou(
        "aura_needs_you", "Precisa de você", "Avisar quando eu precisar de você",
        "Pede a sua aprovação antes de mudar algo importante.", NotificationManagerCompat.IMPORTANCE_HIGH, "approval",
    ),
    Done(
        "aura_done", "Pronto", "Avisar quando terminar",
        "Diz quando uma tarefa ficou pronta.", NotificationManagerCompat.IMPORTANCE_DEFAULT, "result",
    ),
    Listening(
        "aura_listening", "Ouvindo em segundo plano", "Ouvir com a tela apagada",
        "Aviso fixo enquanto o microfone está ligado.", NotificationManagerCompat.IMPORTANCE_LOW, "voice",
    ),
}

enum class ActionKind { Approve, View, Pause, Stop }

data class ActionSpec(val label: String, val kind: ActionKind)

/**
 * Um aviso. [destructive] = a tarefa apaga ou gasta (risco Critical ou Spend): Aprovar deixa de executar
 * e abre o app, onde o dono vê o alvo e a consequência antes de aprovar.
 */
data class NotificationSpec(
    val id: Int,
    val channel: AuraChannel,
    val title: String,
    val text: String,
    val jobId: String? = null,
    val destructive: Boolean = false,
) : java.io.Serializable {
    /** Fixo só enquanto o AURA trabalha ou escuta; os outros o dono dispensa. */
    val ongoing: Boolean get() = channel == AuraChannel.Working || channel == AuraChannel.Listening

    val actions: List<ActionSpec>
        get() = when (channel) {
            AuraChannel.Working -> listOf(ActionSpec("Pausar", ActionKind.Pause), ActionSpec("Abrir", ActionKind.View))
            AuraChannel.NeedsYou -> listOf(ActionSpec("Aprovar", ActionKind.Approve), ActionSpec("Ver", ActionKind.View))
            AuraChannel.Done -> listOf(ActionSpec("Ver resultado", ActionKind.View))
            AuraChannel.Listening -> listOf(ActionSpec("Pausar", ActionKind.Pause), ActionSpec("Parar", ActionKind.Stop))
        }

    /** Ver sempre abre o app; Aprovar abre o app quando apaga ou gasta. O resto executa sem abrir nada. */
    fun opensApp(action: ActionSpec): Boolean =
        action.kind == ActionKind.View || (action.kind == ActionKind.Approve && destructive)
}

/** O que o app recebe ao abrir por um aviso (MainActivity lê no onCreate e no onNewIntent). */
object NotificationIntents {
    const val ACTION_OPEN = "ai.aura.app.action.OPEN_FROM_NOTIFICATION"

    /** Um de AuraChannel.destination: task, approval, result ou voice. */
    const val EXTRA_DESTINATION = "ai.aura.app.extra.DESTINATION"
    const val EXTRA_JOB_ID = "ai.aura.app.extra.JOB_ID"
}

object NotificationChannels {
    /** Cria os canais (o Android ignora o que já existe). Precisa vir antes de qualquer aviso, ou o app fecha. */
    fun ensure(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        AuraChannel.entries.forEach {
            manager.createNotificationChannel(
                NotificationChannelCompat.Builder(it.id, it.importance)
                    .setName(it.label)
                    .setDescription(it.about)
                    .setShowBadge(it == AuraChannel.NeedsYou || it == AuraChannel.Done)
                    .build(),
            )
        }
    }
}

/** O interruptor de cada canal dentro do app. Ligado até o dono desligar. */
class NotificationPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("aura_notifications", Context.MODE_PRIVATE)

    fun isEnabled(channel: AuraChannel): Boolean = prefs.getBoolean(channel.id, true)

    fun setEnabled(channel: AuraChannel, on: Boolean) = prefs.edit().putBoolean(channel.id, on).apply()
}

/** Por que o Android esconderia o aviso: tudo desligado nos ajustes dele, ou só este canal. */
enum class SystemBlock { None, App, Channel }

class AuraNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun systemBlock(channel: AuraChannel): SystemBlock = when {
        !manager.areNotificationsEnabled() -> SystemBlock.App
        manager.getNotificationChannelCompat(channel.id)?.importance == NotificationManagerCompat.IMPORTANCE_NONE ->
            SystemBlock.Channel
        else -> SystemBlock.None
    }

    /** O aviso só aparece com o interruptor do app ligado e o canal liberado no Android. */
    fun canPost(channel: AuraChannel): Boolean =
        NotificationPrefs(context).isEnabled(channel) && systemBlock(channel) == SystemBlock.None

    fun build(spec: NotificationSpec): Notification {
        NotificationChannels.ensure(context)
        val builder = NotificationCompat.Builder(context, spec.channel.id)
            .setSmallIcon(R.drawable.ic_stat_aura)
            .setContentTitle(spec.title)
            .setContentText(spec.text)
            .setContentIntent(openApp(spec, "tap"))
            .setOngoing(spec.ongoing)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        spec.actions.forEach { action ->
            builder.addAction(
                NotificationCompat.Action.Builder(0, action.label, pendingIntent(spec, action))
                    // Aprovar que executa pede o aparelho destravado: da tela de bloqueio não aprova nada.
                    .setAuthenticationRequired(action.kind == ActionKind.Approve && !spec.opensApp(action))
                    .build(),
            )
        }
        return builder.build()
    }

    /** true = mostrou; false = não mostrou (interruptor desligado, bloqueado no Android ou sem permissão). */
    fun post(spec: NotificationSpec): Boolean {
        if (!canPost(spec.channel)) return false
        return try {
            manager.notify(spec.id, build(spec))
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun cancel(id: Int) = manager.cancel(id)

    // Abrir o app é PendingIntent de Activity direto: o Android 12 barra receptor que abre tela (trampolim).
    private fun pendingIntent(spec: NotificationSpec, action: ActionSpec): PendingIntent =
        if (spec.opensApp(action)) {
            openApp(spec, action.kind.name)
        } else {
            val request = ActionRequest(action.kind, spec.jobId, spec.id, spec.channel, spec.destructive)
            PendingIntent.getBroadcast(context, 0, request.toIntent(context), PENDING_FLAGS)
        }

    // O data único por aviso e botão separa os PendingIntents (o Android ignora os extras ao comparar).
    private fun openApp(spec: NotificationSpec, tag: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(NotificationIntents.ACTION_OPEN)
            .setData(Uri.parse("aura://notification/${spec.id}/$tag"))
            .putExtra(NotificationIntents.EXTRA_DESTINATION, spec.channel.destination)
            .putExtra(NotificationIntents.EXTRA_JOB_ID, spec.jobId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PENDING_FLAGS)
    }

    private companion object {
        const val PENDING_FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }
}
