package ai.aura.app.ui.screens

import ai.aura.app.offline.LocalStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Uma leitura do motor para uma tela: carregando, falhou sem nada guardado, ou dado (stale = última leitura guardada). */
sealed interface Reading<out T> {
    data object Loading : Reading<Nothing>
    data class Failed(val message: String) : Reading<Nothing>
    data class Loaded<T>(val value: T, val stale: Boolean = false) : Reading<T>
}

const val READ_TIMEOUT_MS = 8_000L

/**
 * Lê do motor com limite de tempo e guarda a leitura em [name]. Sem resposta (erro ou demora) devolve a
 * última leitura guardada (stale); sem nenhuma, [Reading.Failed]. Nunca fica em Loading.
 */
suspend fun <T> readWithLastCopy(
    store: LocalStore,
    name: String,
    failure: String,
    save: (T) -> JSONObject,
    load: (JSONObject) -> T,
    fetch: suspend () -> T,
): Reading<T> {
    val fresh = try {
        withTimeoutOrNull(READ_TIMEOUT_MS) { fetch() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    if (fresh != null) {
        runCatching { store.write(name, save(fresh)) } // falha de disco não pode esconder o dado novo
        return Reading.Loaded(fresh)
    }
    val copy = store.read(name)?.let { runCatching { load(it) }.getOrNull() }
    return if (copy != null) Reading.Loaded(copy, stale = true) else Reading.Failed(failure)
}
