package ai.aura.app.integration

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Tipo de rede como o motor entende: `metered` (dados medidos) e `transport` (texto curto, até 40 letras). */
data class NetworkSnapshot(val metered: Boolean, val transport: String)

/** Traduz as capacidades da rede ativa. Sem informação, o motor trata como Wi-Fi (padrão dele). */
fun snapshotOf(caps: NetworkCapabilities?): NetworkSnapshot {
    if (caps == null) return NetworkSnapshot(metered = false, transport = "unknown")
    val transport = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        else -> "unknown"
    }
    // Medida = qualquer rede SEM a capacidade NOT_METERED (dados móveis, ou Wi-Fi marcado como medido pelo dono).
    return NetworkSnapshot(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED), transport)
}

/**
 * Avisa o motor (POST /v1/network) quando a rede muda e ao iniciar, para ele nunca baixar pacote do Termux em dados
 * móveis. Só precisa de ACCESS_NETWORK_STATE. Se o envio falhar (motor parado), tenta de novo com espera crescente
 * enquanto estiver ativo, e repete a última rede conhecida quando o motor voltar via [flush].
 */
class NetworkReporter(
    private val connectivity: ConnectivityManager,
    private val scope: CoroutineScope,
    private val send: suspend (NetworkSnapshot) -> Unit,
    private val retryDelaysMs: List<Long> = listOf(5_000, 15_000, 30_000, 60_000),
) {
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = report(snapshotOf(caps))
        override fun onLost(network: Network) {
            // Sem rede não há o que baixar; a próxima rede que subir é reportada mesmo se igual à anterior.
            synchronized(lock) { lastSent = null }
        }
    }
    private val lock = Any()
    private var registered = false
    private var latest: NetworkSnapshot? = null
    private var lastSent: NetworkSnapshot? = null
    private var pending: NetworkSnapshot? = null
    private var job: Job? = null

    /** Registra o callback e envia a rede de agora. Seguro chamar de novo. */
    fun start() {
        synchronized(lock) { if (registered) return; registered = true }
        // Estado inicial: não espera o primeiro callback.
        runCatching { connectivity.getNetworkCapabilities(connectivity.activeNetwork) }.getOrNull()
            ?.let { report(snapshotOf(it)) }
        // Sem permissão ou sem serviço, o app segue vivo (o motor assume Wi-Fi).
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
    }

    fun stop() {
        synchronized(lock) { if (!registered) return; registered = false }
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        job?.cancel()
    }

    /** Última rede vista, para quem precisa garantir que o motor sabe antes de baixar algo. */
    fun current(): NetworkSnapshot? = synchronized(lock) { latest }

    /** Reenvia a rede atual mesmo se igual (use depois de o motor voltar ou antes de instalar). */
    suspend fun flush() {
        val snap = synchronized(lock) { latest }
            ?: runCatching { snapshotOf(connectivity.getNetworkCapabilities(connectivity.activeNetwork)) }.getOrNull()
            ?: return
        send(snap)
        synchronized(lock) { if (latest == snap) lastSent = snap }
    }

    private fun report(snap: NetworkSnapshot) {
        synchronized(lock) {
            latest = snap
            if (snap == lastSent || snap == pending) return
            pending = snap
        }
        job?.cancel()
        job = scope.launch {
            var attempt = 0
            while (true) {
                try {
                    send(snap)
                    synchronized(lock) { if (latest == snap) lastSent = snap; if (pending == snap) pending = null }
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Motor parado ou token ausente: não é erro para o usuário.
                    if (attempt >= retryDelaysMs.size) {
                        synchronized(lock) { if (pending == snap) pending = null }
                        return@launch
                    }
                    delay(retryDelaysMs[attempt++])
                    if (synchronized(lock) { latest } != snap) return@launch // uma rede mais nova assumiu
                }
            }
        }
    }
}
