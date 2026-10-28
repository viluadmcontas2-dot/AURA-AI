package ai.aura.app.ui.screens.settings

import ai.aura.app.integration.Autonomy
import ai.aura.app.integration.SettingsGateway
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Último valor conhecido, guardado no aparelho. Sem ele, os Ajustes ficariam mudos sem o motor. */
interface SettingsCache {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

class MemorySettingsCache : SettingsCache {
    private val map = HashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}

class PrefsSettingsCache(private val prefs: SharedPreferences) : SettingsCache {
    override fun get(key: String) = prefs.getString("settings_$key", null)
    override fun put(key: String, value: String) { prefs.edit().putString("settings_$key", value).apply() }
    override fun remove(key: String) { prefs.edit().remove("settings_$key").apply() }
}

/**
 * Autonomia e trava de custo. Lê do motor ao abrir; sem motor mostra o último valor local (ou o padrão).
 * Mudanças valem na hora no aparelho e vão ao motor; se ele estiver fora, ficam pendentes e seguem quando voltar.
 */
class AutonomyState(
    private val gateway: SettingsGateway,
    private val cache: SettingsCache,
    private val scope: CoroutineScope,
) {
    var autonomy by mutableStateOf(Autonomy.fromWire(cache.get(K_AUTONOMY))); private set
    var costLock by mutableStateOf(cache.get(K_COST)?.toBooleanStrictOrNull() ?: true); private set
    var offline by mutableStateOf(false); private set
    var pendingSync by mutableStateOf(cache.get(K_PENDING) == "1"); private set

    fun reload() {
        scope.launch {
            try {
                if (pendingSync) {
                    // O que o dono escolheu sem motor vale mais que o que o motor guardou.
                    gateway.setAutonomy(autonomy)
                    gateway.setCostLock(costLock)
                    setPending(false)
                } else {
                    autonomy = gateway.autonomy().also { cache.put(K_AUTONOMY, it.wire) }
                    costLock = gateway.costLock().also { cache.put(K_COST, it.toString()) }
                }
                offline = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                offline = true
            }
        }
    }

    fun chooseAutonomy(value: Autonomy) {
        autonomy = value
        cache.put(K_AUTONOMY, value.wire)
        push { gateway.setAutonomy(value) }
    }

    fun toggleCostLock(on: Boolean) {
        costLock = on
        cache.put(K_COST, on.toString())
        push { gateway.setCostLock(on) }
    }

    private fun push(send: suspend () -> Unit) {
        scope.launch {
            try {
                send()
                offline = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                offline = true
                setPending(true)
            }
        }
    }

    private fun setPending(on: Boolean) {
        pendingSync = on
        if (on) cache.put(K_PENDING, "1") else cache.remove(K_PENDING)
    }

    private companion object {
        const val K_AUTONOMY = "autonomy"
        const val K_COST = "cost_lock"
        const val K_PENDING = "pending"
    }
}
