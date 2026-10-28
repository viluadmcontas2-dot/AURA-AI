package ai.aura.app.ui.screens.memory

import ai.aura.app.offline.LocalStore
import ai.aura.app.ui.components.AuraButtonDestructive
import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.components.AuraButtonText
import ai.aura.app.ui.components.AuraCard
import ai.aura.app.ui.components.EmptyState
import ai.aura.app.ui.components.StatusChip
import ai.aura.app.ui.components.StatusKind
import ai.aura.app.ui.screens.Reading
import ai.aura.app.ui.screens.devices.BackHeader
import ai.aura.app.ui.screens.readWithLastCopy
import ai.aura.app.ui.screens.settings.RowDivider
import ai.aura.app.ui.screens.settings.Segmented
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraSpacing
import ai.aura.app.ui.theme.AuraType
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** `kind` do contrato (GET /v1/memory). */
enum class MemoryKind(val wire: String, val label: String) {
    Preference("preference", "Preferência"),
    Project("project", "Projeto"),
    Device("device", "Aparelho"),
    Rule("rule", "Regra");

    companion object {
        fun fromWire(value: String): MemoryKind? = entries.firstOrNull { it.wire == value }
    }
}

/** createdAtSeconds = `createdAt` do contrato (segundos). */
data class MemoryItem(val id: String, val kind: MemoryKind, val text: String, val source: String, val createdAtSeconds: Double)

enum class MemoryFilter(val label: String, val kind: MemoryKind?) {
    All("Tudo", null),
    Preferences("Preferências", MemoryKind.Preference),
    Projects("Projetos", MemoryKind.Project),
    Devices("Aparelhos", MemoryKind.Device),
}

/** Mais nova primeiro. "Regra" só aparece em Tudo (o design não tem filtro de regras). */
fun List<MemoryItem>.visible(selected: MemoryFilter): List<MemoryItem> =
    filter { selected.kind == null || it.kind == selected.kind }.sortedByDescending { it.createdAtSeconds }

private val MONTHS = listOf("jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez")

/** Frase de origem do contrato (docs/ENGINE_CONTRACT.md, "Memoria"); valor desconhecido aparece cru. */
fun MemoryItem.origin(): String = when (source) {
    "user" -> if (kind == MemoryKind.Rule) "você definiu" else "você disse em ${shortDate(createdAtSeconds)}"
    "repository" -> "aprendido no repositório"
    "device" -> "aprendido ao conectar"
    else -> source
}

private fun shortDate(seconds: Double): String {
    val day = Instant.ofEpochSecond(seconds.toLong()).atZone(ZoneId.systemDefault())
    return "${day.dayOfMonth} ${MONTHS[day.monthValue - 1]}"
}

/**
 * O que a tela pede ao motor (T3/T4). Cada método LANÇA exceção quando o motor não respondeu:
 * nunca devolve lista vazia no lugar do erro. `forget` trata 404 como já esquecido.
 */
interface MemoryActions {
    suspend fun list(): List<MemoryItem>
    suspend fun forget(id: String)

    /** DELETE /v1/memory: apaga de todos os projetos e devolve quantas apagou. */
    suspend fun forgetAll(): Int
}

private const val CACHE = "memoria"
private const val NO_READING = "Não deu para ler a memória e ainda não há leitura guardada. Confira se o motor está ligado."
private const val FORGET_FAILED = "O motor não respondeu, então nada foi esquecido. Tente de novo."

/**
 * Lê, esquece uma e esquece tudo. O estado local só muda depois de o motor confirmar; a última
 * leitura fica guardada no celular para a tela abrir sem motor.
 */
class MemoryModel(private val actions: MemoryActions, private val store: LocalStore) {
    private val _state = MutableStateFlow<Reading<List<MemoryItem>>>(Reading.Loading)
    val state: StateFlow<Reading<List<MemoryItem>>> = _state

    private val _notice = MutableStateFlow<String?>(null)

    /** Falha da última ação de esquecer; some quando a próxima dá certo. */
    val notice: StateFlow<String?> = _notice

    suspend fun load() {
        _notice.value = null
        _state.value = Reading.Loading
        _state.value = readWithLastCopy(store, CACHE, NO_READING, ::encode, ::decode) { actions.list() }
    }

    suspend fun forget(id: String) {
        if (!confirmed { actions.forget(id) }) return
        val now = _state.value as? Reading.Loaded<List<MemoryItem>> ?: return
        show(now.value.filterNot { it.id == id }, now.stale)
    }

    suspend fun forgetAll() {
        if (!confirmed { actions.forgetAll() }) return
        show(emptyList(), stale = false)
    }

    private suspend fun confirmed(call: suspend () -> Unit): Boolean = try {
        call()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        _notice.value = FORGET_FAILED
        false
    }

    private fun show(items: List<MemoryItem>, stale: Boolean) {
        _notice.value = null
        _state.value = Reading.Loaded(items, stale)
        runCatching { store.write(CACHE, encode(items)) }
    }

    private fun encode(items: List<MemoryItem>): JSONObject = JSONObject().put(
        "items",
        JSONArray().also { array ->
            items.forEach {
                array.put(
                    JSONObject().put("id", it.id).put("kind", it.kind.wire).put("text", it.text)
                        .put("source", it.source).put("createdAt", it.createdAtSeconds),
                )
            }
        },
    )

    private fun decode(json: JSONObject): List<MemoryItem> {
        val array = json.getJSONArray("items")
        return List(array.length()) { i ->
            val o = array.getJSONObject(i)
            MemoryItem(
                o.getString("id"), MemoryKind.fromWire(o.getString("kind")) ?: error("kind"),
                o.getString("text"), o.getString("source"), o.getDouble("createdAt"),
            )
        }
    }
}

/**
 * Tela Memória. Recebe estado e ações por parâmetro (quem liga é o [MemoryModel]).
 * "Esquecer tudo" pede confirmação aqui mesmo e diz o alcance: todos os projetos.
 */
@Composable
fun MemoryScreen(
    state: Reading<List<MemoryItem>>,
    notice: String?,
    onForget: (MemoryItem) -> Unit,
    onForgetAll: () -> Unit,
    onRetry: () -> Unit,
    onTell: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by rememberSaveable { mutableStateOf(MemoryFilter.All) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize().background(AuraColors.Bg).statusBarsPadding()) {
        BackHeader("Memória", null, onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = AuraSpacing.ScreenGutter).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("O que o AURA lembra para não repetir perguntas. Você manda.", style = AuraType.Secondary)
            Segmented(
                MemoryFilter.entries.map { it to it.label }, filter, { filter = it },
                "Filtrar memórias", Modifier.fillMaxWidth(),
            )
            when (state) {
                is Reading.Loading -> Text("Lendo a memória…", style = AuraType.Secondary)
                is Reading.Failed -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        state.message, style = AuraType.Body,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    AuraButtonPrimary("Tentar de novo", onRetry)
                }
                is Reading.Loaded -> {
                    if (state.stale) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            StatusChip(StatusKind.NeedsYou, "Última leitura")
                            Text(
                                "O motor não respondeu agora. Isto é o que ficou guardado no celular.",
                                style = AuraType.SecondarySmall,
                            )
                            AuraButtonText("Tentar de novo", onRetry)
                        }
                    }
                    val shown = state.value.visible(filter)
                    if (shown.isEmpty()) {
                        EmptyState(
                            "Nada aqui ainda", "Quando você contar uma preferência, o AURA lembra e ela aparece aqui.",
                            "Contar uma preferência", onTell,
                        )
                    } else {
                        AuraCard(Modifier.fillMaxWidth()) {
                            shown.forEachIndexed { i, item ->
                                if (i > 0) RowDivider()
                                MemoryRow(item) { onForget(item) }
                            }
                        }
                    }
                    if (notice != null) {
                        Text(
                            notice, style = AuraType.SecondarySmall.copy(color = AuraColors.Risk.fg),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    Text(
                        "Arquivos do celular e do Drive não vão aos modelos sem você pedir.",
                        style = AuraType.SecondarySmall,
                    )
                    if (confirming) {
                        AuraCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Esquecer tudo?", style = AuraType.Section, modifier = Modifier.semantics { heading() })
                                Text(
                                    "Isso apaga todas as lembranças do AURA, de todos os projetos. Não dá para desfazer.",
                                    style = AuraType.Body,
                                )
                                AuraButtonDestructive("Esquecer tudo", { confirming = false; onForgetAll() }, Modifier.fillMaxWidth())
                                AuraButtonText("Cancelar", { confirming = false }, Modifier.fillMaxWidth())
                            }
                        }
                    } else {
                        AuraButtonSecondary("Esquecer tudo", { confirming = true }, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryRow(item: MemoryItem, onForget: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.text, style = AuraType.BodyStrong)
            Text("${item.kind.label} · ${item.origin()}", style = AuraType.SecondarySmall)
        }
        AuraButtonSecondary("Esquecer", onForget, contentDescription = "Esquecer: ${item.text}")
    }
}
