package ai.aura.app.ui.screens.capabilities

import ai.aura.app.offline.LocalStore
import ai.aura.app.ui.engine.Gate
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobStatus
import ai.aura.app.ui.engine.PolicyDoc
import ai.aura.app.ui.engine.PolicyResult
import ai.aura.app.ui.screens.Reading
import ai.aura.app.ui.screens.readWithLastCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Uma skill do motor (GET /v1/skills). `description` vem do SKILL.md, em inglês; origin: bundled | user. */
data class Skill(val id: String, val name: String, val description: String, val origin: String)

enum class CapGroup(val title: String, val hint: String, val chip: String) {
    Engineering("Engenharia de software", "Testes, revisão, Git", "Engenharia"),
    Clarity("Clareza e interpretação", "Intenção e explicações", "Clareza"),
    Android("Controle Android", "ADB, capturas, diagnósticos", "Android"),
    Organization("Organização e pesquisa", "Arquivos, projetos, Drive", "Organização"),
}

enum class Mode(val label: String) { Always("sempre"), Ask("perguntar antes"), Off("desligado") }

/**
 * Uma permissão da tela de detalhe e a chave da política que ela grava:
 * risk.read | risk.write | kind.shell | kind.adb | kind.files | kind.root.
 */
data class Perm(val label: String, val key: String) {
    /** Só o root tem "desligado" de verdade (root.enabled). Os demais são auto ou ask no motor. */
    val modes: List<Mode>
        get() = if (key == "kind.root") listOf(Mode.Always, Mode.Ask, Mode.Off) else listOf(Mode.Always, Mode.Ask)
}

class SkillInfo(
    val group: CapGroup,
    val title: String,
    val summary: String,
    val examples: List<String>,
    val perms: List<Perm>,
)

private val READ = Perm("Ler arquivos do projeto", "risk.read")
private val WRITE = Perm("Alterar arquivos do projeto", "risk.write")
private val SHELL = Perm("Rodar comandos no Termux", "kind.shell")
private val ADB = Perm("Usar o ADB (controle do Android)", "kind.adb")
private val FILES = Perm("Mexer em arquivos do aparelho", "kind.files")
private val ROOT = Perm("Usar acesso root", "kind.root")

/** Skills que já vêm no motor, com texto em português. O motor não informa grupo nem permissões. */
private val CATALOG = mapOf(
    "vibe-coding" to SkillInfo(
        CapGroup.Engineering, "Corrigir e testar código",
        "Acha o erro, corrige o código e roda os testes para provar que funcionou.",
        listOf("“corrija este aplicativo”", "“a tela de login está cortada”"), listOf(READ, WRITE, SHELL),
    ),
    "android-diagnosis" to SkillInfo(
        CapGroup.Android, "Diagnosticar o Android",
        "Olha o aparelho pelo ADB e explica o que está errado, sem mudar nada sem a sua aprovação.",
        listOf("“por que o celular está travando?”", "“tire uma captura da tela”"), listOf(ADB, ROOT),
    ),
    "file-organization" to SkillInfo(
        CapGroup.Organization, "Organizar arquivos e pastas",
        "Lista, separa e guarda arquivos em pastas, sempre com um jeito de desfazer.",
        listOf("“organize minha pasta de Downloads”", "“ache os arquivos repetidos”"), listOf(FILES, WRITE),
    ),
)

/** Skill fora do catálogo (a do usuário): mostra o que o motor informou e não inventa permissões. */
fun Skill.info(): SkillInfo = CATALOG[id] ?: SkillInfo(CapGroup.Organization, name, description, emptyList(), emptyList())

/** Sempre os 4 grupos, na ordem do design; grupo sem skill fica com lista vazia. */
fun List<Skill>.byGroup(): Map<CapGroup, List<Skill>> =
    CapGroup.entries.associateWith { group -> filter { it.info().group == group } }

private val SKILL_OF_KIND = mapOf(
    "adb" to "android-diagnosis", "files" to "file-organization", "shell" to "vibe-coding", "github" to "vibe-coding",
)

/**
 * "Em uso agora": skill -> ids dos jobs rodando que a usam. Só entram os tipos que apontam para uma
 * skill sem dúvida; conversa com IA (claude_cli etc.) não diz qual skill usou.
 */
fun inUse(jobs: List<Job>): Map<String, List<String>> =
    jobs.filter { it.status == JobStatus.Running }
        .mapNotNull { job -> SKILL_OF_KIND[job.kind]?.let { it to job.id } }
        .groupBy({ it.first }, { it.second })

private fun modeOf(gate: Gate?, default: Gate) = if ((gate ?: default) == Gate.Auto) Mode.Always else Mode.Ask

/** Modo de cada permissão. Sem a chave no documento vale o padrão do motor (risco: ask, tipo: auto, root: ask). */
fun modesOf(doc: PolicyDoc): Map<String, Mode> = buildMap {
    for (k in listOf("read", "write")) put("risk.$k", modeOf(doc.byRisk[k], Gate.Ask))
    for (k in listOf("shell", "adb", "files")) put("kind.$k", modeOf(doc.byKind[k], Gate.Auto))
    put("kind.root", if (!doc.root.enabled) Mode.Off else modeOf(doc.byKind["root"], Gate.Ask))
}

/** Aplica só as mudanças pedidas sobre [doc] (a política viva). "desligado" só vale para o root. */
fun applyModes(doc: PolicyDoc, changes: Map<String, Mode>): PolicyDoc {
    var risk = doc.byRisk
    var kind = doc.byKind
    var root = doc.root
    for ((key, mode) in changes) {
        val gate = if (mode == Mode.Always) Gate.Auto else Gate.Ask
        val name = key.substringAfter('.')
        when {
            key == "kind.root" -> {
                root = root.copy(enabled = mode != Mode.Off)
                if (mode != Mode.Off) kind = kind + (name to gate)
            }
            key.startsWith("risk.") -> risk = risk + (name to gate)
            key.startsWith("kind.") -> kind = kind + (name to gate)
        }
    }
    return doc.copy(byRisk = risk, byKind = kind, root = root)
}

/**
 * O que as telas pedem ao motor (T4). Espelha o gateway: `skills` lança quando o motor não respondeu;
 * `policy` e `savePolicy` devolvem [PolicyResult] (Unavailable = sem resposta).
 */
interface CapabilitiesSource {
    suspend fun skills(): List<Skill>
    suspend fun policy(): PolicyResult
    suspend fun savePolicy(doc: PolicyDoc): PolicyResult
}

data class CapabilitiesData(val skills: List<Skill>, val modes: Map<String, Mode>)

private const val CACHE = "capacidades"
private const val NO_READING = "Não deu para ler as capacidades e ainda não há leitura guardada. Confira se o motor está ligado."
private const val UNREACHED = "O motor não respondeu. Nada foi alterado; suas escolhas continuam aqui. Toque em Salvar permissões quando ele voltar."

/** Lê skills e modos (com a última leitura guardada) e grava permissões na política do motor. */
class CapabilitiesModel(private val source: CapabilitiesSource, private val store: LocalStore) {
    private val _state = MutableStateFlow<Reading<CapabilitiesData>>(Reading.Loading)
    val state: StateFlow<Reading<CapabilitiesData>> = _state

    suspend fun load() {
        _state.value = Reading.Loading
        _state.value = readWithLastCopy(store, CACHE, NO_READING, ::encode, ::decode) {
            val doc = (source.policy() as? PolicyResult.Ok)?.doc ?: error("sem política")
            CapabilitiesData(source.skills(), modesOf(doc))
        }
    }

    /** Grava só o que mudou, sobre a política VIVA (nunca sobre a cópia antiga). Devolve o erro, ou null se gravou. */
    suspend fun save(changes: Map<String, Mode>): String? {
        val result = try {
            val live = (source.policy() as? PolicyResult.Ok)?.doc ?: return UNREACHED
            source.savePolicy(applyModes(live, changes))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return UNREACHED
        }
        return when (result) {
            is PolicyResult.Ok -> {
                val before = _state.value as? Reading.Loaded<CapabilitiesData>
                val next = CapabilitiesData(before?.value?.skills.orEmpty(), modesOf(result.doc))
                _state.value = Reading.Loaded(next, before?.stale ?: false)
                runCatching { store.write(CACHE, encode(next)) }
                null
            }
            is PolicyResult.Rejected -> "O motor não aceitou a mudança (${result.code}). Nada foi alterado."
            is PolicyResult.Unavailable -> UNREACHED
        }
    }

    private fun encode(data: CapabilitiesData): JSONObject = JSONObject()
        .put(
            "skills",
            JSONArray().also { array ->
                data.skills.forEach {
                    array.put(
                        JSONObject().put("id", it.id).put("name", it.name)
                            .put("description", it.description).put("origin", it.origin),
                    )
                }
            },
        )
        .put("modes", JSONObject().also { o -> data.modes.forEach { (key, mode) -> o.put(key, mode.name) } })

    private fun decode(json: JSONObject): CapabilitiesData {
        val array = json.getJSONArray("skills")
        val modes = json.getJSONObject("modes")
        return CapabilitiesData(
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                Skill(o.getString("id"), o.getString("name"), o.getString("description"), o.getString("origin"))
            },
            modes.keys().asSequence().associateWith { Mode.valueOf(modes.getString(it)) },
        )
    }
}
