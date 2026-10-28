package ai.aura.app.ui.screens.chat

import ai.aura.app.offline.LocalStore
import ai.aura.app.ui.engine.ChatAction
import ai.aura.app.ui.screens.readList
import ai.aura.app.ui.screens.writeList
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import org.json.JSONObject
import java.text.Normalizer

/**
 * O que a aba Início sabe sobre o chat aberto. Só strings, para sobreviver a rotação e a
 * morte do processo (rememberSaveable).
 */
data class ChatEntry(
    val open: Boolean = false,
    /** Mensagem a enviar ao abrir o chat (vazio = chat em branco). */
    val prompt: String = "",
    val requestId: String = "",
    /** Trabalho da Atividade a mostrar como resumo (vazio = nenhum). */
    val jobId: String = "",
    /** Texto pré-preenchido no composer, sem enviar. */
    val draft: String = "",
    /** Conversa em andamento: vai com cada mensagem para o motor continuar de onde parou. */
    val sessionId: String = "",
) {
    companion object {
        val Closed = ChatEntry()
        val Saver: Saver<ChatEntry, Any> = listSaver<ChatEntry, Any>(
            save = { listOf(if (it.open) "1" else "0", it.prompt, it.requestId, it.jobId, it.draft, it.sessionId) },
            // Lista curta ou de tipo torto (versão antiga, Bundle estranho): descarta em vez de derrubar o app.
            restore = { l ->
                if (l.size < 5) null
                else ChatEntry(
                    l[0] == "1", l[1] as? String ?: "", l[2] as? String ?: "", l[3] as? String ?: "",
                    l[4] as? String ?: "", l.getOrNull(5) as? String ?: "",
                )
            },
        )
    }
}

/** [tool]: linha discreta de ferramenta vinda do histórico de uma sessão ("Lendo arquivos…"), sem balão. */
data class ChatMessage(val id: Int, val fromUser: Boolean, val text: String, val tool: Boolean = false)

/** Histórico do chat no disco (FN-12): volta ao reabrir a conversa, mesmo sem motor. */
internal fun LocalStore.loadChat(): List<ChatMessage> = readList("chat-history") { o ->
    ChatMessage(o.optInt("id", -1), o.optBoolean("fromUser", false), o.optString("text", ""), o.optBoolean("tool", false))
        .takeIf { it.id >= 0 && it.text.isNotEmpty() }
}

internal fun LocalStore.saveChat(messages: List<ChatMessage>) = writeList(
    "chat-history",
    messages.takeLast(200).map {
        JSONObject().put("id", it.id).put("fromUser", it.fromUser).put("text", it.text).also { o -> if (it.tool) o.put("tool", true) }
    },
)

internal fun LocalStore.loadSessionId(): String = read("chat-session")?.optString("session_id").orEmpty()

internal fun LocalStore.saveSessionId(id: String) {
    if (id.isEmpty()) delete("chat-session") else write("chat-session", JSONObject().put("session_id", id))
}

/**
 * Mensagens que ainda não saíram porque o motor estava desligado, em ordem. Saem sozinhas quando ele volta.
 * No disco: "items" (a fila) e "text" (as mesmas juntas; é o que versões antigas gravaram).
 */
internal fun LocalStore.loadPending(): List<String> {
    val o = read("chat-pending") ?: return emptyList()
    val items = o.optJSONArray("items")
    if (items != null) return (0 until items.length()).map { items.optString(it) }.filter { it.isNotEmpty() }
    return listOf(o.optString("text")).filter { it.isNotEmpty() }
}

internal fun LocalStore.savePending(items: List<String>) {
    if (items.isEmpty()) delete("chat-pending")
    else write("chat-pending", JSONObject().put("text", items.joinToString("\n\n")).put("items", org.json.JSONArray(items)))
}

/** Aviso do próprio app dentro da conversa, em português, com no máximo uma ação. */
class ChatNotice(val message: String, val action: ChatAction?)

/** Texto que chega aos poucos e as linhas de ferramenta, cada uma na posição do texto em que apareceu. */
class LiveReply(val text: String, val tools: List<ToolMark>) {
    class ToolMark(val offset: Int, val summary: String)
}

/** Para onde um comando "/" leva dentro do app. */
enum class AppTarget { Projects, Reform, Connections, Settings, Activity, Voice, Devices, Sessions }

class ChatCommand(val name: String, val description: String, val target: AppTarget, val opened: String)

/** Comandos "/" são do app: locais, instantâneos e nunca enviados à IA. */
object ChatCommands {
    val all: List<ChatCommand> = listOf(
        ChatCommand("/projeto", "Abrir ou criar um projeto", AppTarget.Projects, "Abri Projetos."),
        ChatCommand("/reformar", "Reformar um app que já existe", AppTarget.Reform, "Abri Reformar apps."),
        ChatCommand("/conexoes", "Termux, contas e permissões", AppTarget.Connections, "Abri Conexões."),
        ChatCommand("/ajustes", "Mudar como o AURA trabalha", AppTarget.Settings, "Abri Ajustes."),
        ChatCommand("/atividade", "Ver o que está rodando", AppTarget.Activity, "Abri Atividade."),
        ChatCommand("/aparelho", "TV, celular ou multimídia", AppTarget.Devices, "Abri Dispositivos."),
        ChatCommand("/sessoes", "Conversas do Claude Code", AppTarget.Sessions, "Abri Sessões."),
        ChatCommand("/voz", "Falar em vez de digitar", AppTarget.Voice, "Abri a voz."),
    )

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** Mostra o painel só enquanto a pessoa digita a palavra do comando (sem espaço ainda). */
    fun suggestionsFor(text: String): List<ChatCommand> =
        if (text.startsWith("/") && text.none { it.isWhitespace() }) all.filter { it.name.startsWith(norm(text)) } else emptyList()

    fun find(text: String): ChatCommand? = all.firstOrNull { it.name == norm(text.trim().substringBefore(' ')) }

    /**
     * Comando do app = "/" + uma palavra conhecida da lista (pode vir com argumento), ou "/" + uma palavra
     * desconhecida curta (até 20 letras, sem outra "/"), para o app dizer "Não conheço". Caminhos como
     * "/data/data/..." e frases que começam com "/" vão ao Claude.
     */
    fun isCommandText(text: String): Boolean {
        val t = text.trim()
        if (!t.startsWith("/")) return false
        if (find(t) != null) return true
        val word = t.drop(1)
        return word.isNotEmpty() && word.length <= 20 && word.none { it.isWhitespace() || it == '/' }
    }

    /** Palavra digitada, para a frase "Não conheço /x". */
    fun wordOf(text: String): String = text.trim().substringBefore(' ')
}
