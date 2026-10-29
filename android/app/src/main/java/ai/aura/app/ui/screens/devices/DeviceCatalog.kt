package ai.aura.app.ui.screens.devices

import androidx.compose.runtime.staticCompositionLocalOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Catálogo LOCAL de aparelhos conhecidos (docs/FLUXOS_DE_USO.md, seção 6). Ele existe para a aba Dispositivos nunca
 * ficar vazia só porque o motor não respondeu: o que a pessoa já usou continua na lista, com "Última conexão", e o
 * estado de agora é "desconhecido" — nunca o "Conectado" de uma vez que já passou. Não inventa descoberta de
 * aparelhos: só guarda o que o motor já reportou.
 */

data class KnownDevice(
    val id: String,
    val label: String,
    val group: DeviceGroup,
    /** Última vez que o MOTOR reportou este aparelho conectado. null = nunca vimos conectado. */
    val lastConnectedAtMillis: Long?,
)

interface DeviceCatalog {
    fun load(): List<KnownDevice>
    fun save(devices: List<KnownDevice>)
}

class MemoryDeviceCatalog(initial: List<KnownDevice> = emptyList()) : DeviceCatalog {
    private var devices = initial
    override fun load() = devices
    override fun save(devices: List<KnownDevice>) { this.devices = devices }
}

/** JSON em arquivo, gravado por inteiro (arquivo temporário + troca). Qualquer falha de disco vira lista vazia. */
class FileDeviceCatalog(private val file: File) : DeviceCatalog {
    override fun load(): List<KnownDevice> = try {
        val root = JSONObject(file.readText(Charsets.UTF_8))
        val array = root.optJSONArray("devices") ?: JSONArray()
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifBlank { return@mapNotNull null }
            KnownDevice(
                id = id,
                label = o.optString("label").ifBlank { id },
                group = runCatching { DeviceGroup.valueOf(o.optString("group")) }.getOrDefault(DeviceGroup.Device),
                lastConnectedAtMillis = if (o.has("last")) o.optLong("last") else null,
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    override fun save(devices: List<KnownDevice>) {
        try {
            file.parentFile?.mkdirs()
            val array = JSONArray()
            devices.forEach { d ->
                array.put(
                    JSONObject().put("id", d.id).put("label", d.label).put("group", d.group.name)
                        .also { o -> d.lastConnectedAtMillis?.let { o.put("last", it) } },
                )
            }
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(JSONObject().put("devices", array).toString(), Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText(Charsets.UTF_8), Charsets.UTF_8)
                temp.delete()
            }
        } catch (_: Exception) {
            // Disco cheio ou sem permissão: a tela segue com o que tem em memória.
        }
    }
}

/** Entregue pela MainActivity se quiser trocar o arquivo; nulo = a aba usa `filesDir/aura/devices.json`. */
val LocalDeviceCatalog = staticCompositionLocalOf<DeviceCatalog?> { null }

/**
 * Junta o que o motor reportou agora ([live], null = o motor não respondeu) com o catálogo [known].
 * - Motor respondeu: o estado de cada aparelho é o real; os conectados ganham "Última conexão" = [now];
 *   os que sumiram da lista do motor continuam no catálogo como Offline (o motor respondeu e não os viu).
 * - Motor não respondeu: todos os conhecidos aparecem com estado DESCONHECIDO, nunca "Conectado".
 * Devolve o catálogo novo e as linhas para mostrar.
 */
fun mergeDevices(known: List<KnownDevice>, live: List<DeviceEntry>?, now: Long): Pair<List<KnownDevice>, List<DeviceEntry>> {
    if (live == null) {
        val rows = known.map { k ->
            DeviceEntry(k.id, k.label, k.group, ConnectionState.Unknown, UNVERIFIED_HINT, lastConnectedAtMillis = k.lastConnectedAtMillis)
        }
        return known to rows
    }
    val byId = known.associateBy { it.id }.toMutableMap()
    val rows = mutableListOf<DeviceEntry>()
    for (entry in live) {
        val old = byId[entry.id]
        val last = if (entry.state == ConnectionState.Connected) now else old?.lastConnectedAtMillis
        byId[entry.id] = KnownDevice(entry.id, entry.label, entry.group, last)
        rows += entry.copy(lastConnectedAtMillis = last)
    }
    val liveIds = live.map { it.id }.toSet()
    known.filter { it.id !in liveIds && it.group == DeviceGroup.Device }.forEach { k ->
        rows += DeviceEntry(k.id, k.label, k.group, ConnectionState.Offline, "Sem conexão agora.", lastConnectedAtMillis = k.lastConnectedAtMillis)
    }
    return byId.values.toList() to rows
}

const val UNVERIFIED_HINT = "Não consegui verificar este aparelho agora."

/** "Última conexão: 28/10 12:30", ou "Nunca conectado por aqui." quando nunca houve conexão confirmada. */
fun lastConnectionText(millis: Long?, locale: Locale = Locale("pt", "BR")): String =
    if (millis == null) "Nunca conectado por aqui."
    else "Última conexão: " + SimpleDateFormat("dd/MM HH:mm", locale).format(Date(millis))
