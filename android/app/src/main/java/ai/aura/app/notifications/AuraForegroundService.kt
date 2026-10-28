package ai.aura.app.notifications

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/** Quanto o aviso fixo pode ficar de pé sem ninguém renovar (o Android corta o serviço de dados em 6 h). */
internal const val WORKING_TIMEOUT_MILLIS = 30 * 60_000L

/** O microfone ligado é o caso sensível: dez minutos sem renovar e desliga. */
internal const val LISTENING_TIMEOUT_MILLIS = 10 * 60_000L

enum class StartResult { Started, NotificationsOff, NoMicrophone, NotAllowed }

/**
 * Serviço de primeiro plano das notificações fixas (Enquanto trabalha e Ouvindo em segundo plano).
 * Garantia de parar: relógio por início, onTimeout do Android 15, onTaskRemoved na escuta e [stop].
 */
class AuraForegroundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { stopNow() }
    private var listening = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val given = intent?.let { IntentCompat.getSerializableExtra(it, EXTRA_SPEC, NotificationSpec::class.java) }
        // O Android exige o aviso mesmo para parar logo em seguida (e o id nunca é 0); sem pedido válido, mostra um e encerra.
        val spec = given ?: NotificationSpec(1, AuraChannel.Working, "AURA AI", "Encerrando")
        listening = spec.channel == AuraChannel.Listening
        val type = if (listening) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        try {
            ServiceCompat.startForeground(this, spec.id, AuraNotifier(this).build(spec), type)
        } catch (e: RuntimeException) {
            // O Android recusou (app em segundo plano, tipo sem permissão): parar é melhor que fechar o app.
            stopNow()
            return START_NOT_STICKY
        }
        if (given == null) {
            stopNow()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, if (listening) LISTENING_TIMEOUT_MILLIS else WORKING_TIMEOUT_MILLIS)
        return START_NOT_STICKY
    }

    /** Android 15: o sistema avisa que o tempo do tipo acabou; ou paramos agora, ou o app fecha. */
    override fun onTimeout(startId: Int, fgsType: Int) = stopNow()

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (listening) stopNow()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        super.onDestroy()
    }

    private fun stopNow() {
        handler.removeCallbacks(timeout)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val EXTRA_SPEC = "ai.aura.app.extra.SPEC"

        fun intentFor(context: Context, spec: NotificationSpec): Intent =
            Intent(context, AuraForegroundService::class.java).putExtra(EXTRA_SPEC, spec)

        /**
         * Sobe o serviço só se o aviso puder aparecer (senão o Android derruba o app) e, na escuta, com o
         * microfone liberado. Sempre devolve o motivo: quem chama mostra a ação certa, nada some em silêncio.
         */
        fun start(context: Context, spec: NotificationSpec): StartResult {
            if (!AuraNotifier(context).canPost(spec.channel)) return StartResult.NotificationsOff
            val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            if (spec.channel == AuraChannel.Listening && mic != PackageManager.PERMISSION_GRANTED) return StartResult.NoMicrophone
            return try {
                ContextCompat.startForegroundService(context, intentFor(context, spec))
                StartResult.Started
            } catch (e: RuntimeException) {
                StartResult.NotAllowed
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AuraForegroundService::class.java))
        }
    }
}
