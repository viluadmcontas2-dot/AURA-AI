package ai.aura.app.ui.screens

import ai.aura.app.offline.LocalStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Pasta de dados do app (a mesma da fila offline): projetos, histórico do chat e rascunhos moram aqui. */
@Composable
internal fun rememberAuraStore(): LocalStore {
    val dir = File(LocalContext.current.filesDir, "aura")
    return remember(dir) { LocalStore(dir) }
}

/** Uma lista por arquivo (`{"items":[...]}`). Item que não é objeto, ou que o [parse] recusa, é descartado. */
internal fun <T : Any> LocalStore.readList(name: String, parse: (JSONObject) -> T?): List<T> {
    val items = read(name)?.optJSONArray("items") ?: return emptyList()
    return (0 until items.length()).mapNotNull { i -> items.optJSONObject(i)?.let(parse) }
}

internal fun LocalStore.writeList(name: String, items: List<JSONObject>) =
    write(name, JSONObject().put("items", JSONArray(items)))
