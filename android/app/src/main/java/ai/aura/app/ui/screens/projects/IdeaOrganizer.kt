package ai.aura.app.ui.screens.projects

import java.text.Normalizer
import java.util.UUID

/**
 * Organiza a ideia em um rascunho de projeto SEM inventar fatos: nome, objetivo e repositório vêm
 * do texto do usuário; plataforma e critérios são os padrões do produto (Android/Kotlin, testes no
 * GitHub). Função pura (testável em JVM). Quem realmente interpreta com modelo é o engine.
 */
internal object IdeaOrganizer {
    private val filler = setOf(
        "crie", "criar", "faca", "fazer", "faça", "quero", "preciso", "de", "do", "da", "um", "uma", "app",
        "aplicativo", "aplicacao", "aplicação", "para", "pra", "que", "eu", "meu", "minha", "me", "o", "a",
        "completo", "simples",
    )

    /** Menos de 3 palavras: falta o essencial e perguntamos UMA vez. */
    fun missingQuestion(idea: String): String? =
        if (idea.trim().split(Regex("\\s+")).count { it.isNotBlank() } < 3) "O que o aplicativo precisa fazer por você?" else null

    fun organize(idea: String, id: String = UUID.randomUUID().toString()): Project {
        val clean = idea.trim().replace(Regex("\\s+"), " ")
        val words = clean.split(" ").filter { it.isNotBlank() }
        val core = words.dropWhile { it.lowercase().trim(',', '.') in filler }.take(3)
            .ifEmpty { words.take(3) }
        val name = core.joinToString(" ") { w -> w.trim(',', '.').replaceFirstChar { it.uppercase() } }
            .ifBlank { "Meu projeto" }
        val sentence = clean.split(Regex("(?<=[.!?])\\s+")).firstOrNull().orEmpty().trimEnd('.')
        val goal = sentence.replaceFirstChar { it.uppercase() }.let { if (it.length > 90) it.take(87) + "…" else it }
            .ifBlank { name }
        return Project(
            id = id, name = name, goal = goal, platform = "Android, Kotlin + Compose",
            screens = emptyList(),
            doneWhen = listOf("Abre sem travar no celular", "Testes passam no GitHub"),
            deliveries = listOf("Fundação", "Primeira versão", "Revisão"),
            repo = slug(name),
        )
    }

    fun slug(name: String): String {
        val plain = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return plain.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "meu-projeto" }
    }
}
