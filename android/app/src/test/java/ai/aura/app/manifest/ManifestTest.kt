package ai.aura.app.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Trava a aparência de app confiável (docs/CONSTRAINTS.md): permissões iguais a docs/PERMISSOES.md,
 * texto puro só no motor local, só o launcher exportado, sem backup, ícone próprio adaptável.
 * JVM pura: lê os arquivos do módulo e o manifesto final que o AGP monta (o mesmo que o Robolectric usa).
 */
class ManifestTest {
    private val modulo = File(checkNotNull(System.getProperty("user.dir"))) // o Gradle roda os testes na pasta do módulo
    private val manifesto = xml(File(modulo, "src/main/AndroidManifest.xml"))
    private val aplicativo = manifesto.all("application").single()

    @Test
    fun `permissoes do manifesto e do APK montado sao as de PERMISSOES md`() {
        val documentadas = permissoesDocumentadas()
        assertIgual("src/main/AndroidManifest.xml", documentadas, permissoes(manifesto))
        // Manifesto final, com as bibliotecas: pega permissão que uma dependência acrescente sozinha.
        // As do próprio pacote (ai.aura.app.*) são do AndroidX e só valem para o app.
        val finais = permissoes(xml(manifestoFinal())).filterNot { it.startsWith("ai.aura.app.") }.toSet()
        assertIgual("manifesto final do APK", documentadas, finais)
    }

    @Test
    fun `texto puro so para o motor local`() {
        assertEquals("sem usesCleartextTraffic global", "", aplicativo.attr("usesCleartextTraffic"))
        assertEquals("@xml/network_security_config", aplicativo.attr("networkSecurityConfig"))
        val regras = xml(File(modulo, "src/main/res/xml/network_security_config.xml"))
        assertEquals("false", regras.all("base-config").single().getAttribute("cleartextTrafficPermitted"))
        val liberados = regras.all("domain-config")
            .filter { it.getAttribute("cleartextTrafficPermitted") == "true" }
            .flatMap { it.all("domain") }
            .map { it.textContent.trim() }
        assertEquals(setOf("127.0.0.1", "localhost"), liberados.toSet())
    }

    @Test
    fun `so o launcher e exportado`() {
        val componentes = componentes(aplicativo).filter { it.getAttributeNS(TOOLS, "node") != "remove" }
        assertTrue("todo componente declara exported", componentes.all { it.attr("exported").isNotEmpty() })
        assertEquals(listOf(".MainActivity"), componentes.filter { it.attr("exported") == "true" }.map { it.attr("name") })
        // No manifesto final, fora as telas de depuração que só existem no debug.
        val finais = componentes(xml(manifestoFinal()).all("application").single())
        val abertos = finais.filter { it.attr("exported") == "true" }.map { it.attr("name") }.toSet() - SO_NO_DEBUG
        assertEquals(setOf("ai.aura.app.MainActivity"), abertos)
    }

    @Test
    fun `backup desligado com regras que excluem tudo`() {
        assertEquals("false", aplicativo.attr("allowBackup"))
        assertEquals("@xml/data_extraction_rules", aplicativo.attr("dataExtractionRules"))
        assertEquals("@xml/backup_rules", aplicativo.attr("fullBackupContent"))
        for (nome in listOf("data_extraction_rules", "backup_rules")) {
            val regras = xml(File(modulo, "src/main/res/xml/$nome.xml"))
            assertTrue("$nome não pode ter <include>", regras.all("include").isEmpty())
            assertTrue("$nome precisa de <exclude>", regras.all("exclude").isNotEmpty())
        }
    }

    @Test
    fun `icone proprio e adaptavel com camada monocromatica`() {
        assertEquals("AURA AI", aplicativo.attr("label"))
        assertEquals("@mipmap/ic_launcher", aplicativo.attr("icon"))
        assertEquals("@mipmap/ic_launcher_round", aplicativo.attr("roundIcon"))
        for (nome in listOf("ic_launcher", "ic_launcher_round")) {
            val icone = xml(File(modulo, "src/main/res/mipmap-anydpi-v26/$nome.xml"))
            assertEquals("adaptive-icon", icone.tagName)
            for (camada in listOf("background", "foreground", "monochrome")) {
                assertEquals("$nome precisa de <$camada>", 1, icone.all(camada).size)
            }
        }
    }

    @Test
    fun `queries so do Termux e do reconhecedor de voz`() {
        val queries = manifesto.all("queries").single()
        assertEquals(listOf("com.termux"), queries.all("package").map { it.attr("name") })
        assertEquals(listOf("android.speech.action.RECOGNIZE_SPEECH"), queries.all("action").map { it.attr("name") })
        assertTrue("sem provider em queries", queries.all("provider").isEmpty())
    }

    private fun xml(arquivo: File): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(arquivo).documentElement

    private fun Element.all(tag: String): List<Element> =
        getElementsByTagName(tag).let { nos -> List(nos.length) { nos.item(it) as Element } }

    private fun Element.attr(nome: String): String = getAttributeNS(ANDROID, nome)

    private fun componentes(aplicativo: Element): List<Element> =
        listOf("activity", "activity-alias", "service", "receiver", "provider").flatMap { aplicativo.all(it) }

    private fun permissoes(raiz: Element): Set<String> =
        (raiz.all("uses-permission") + raiz.all("uses-permission-sdk-23")).map { it.attr("name") }.toSet()

    /** Só a tabela "Permissões que o app declara": cada linha começa com a permissão entre crases. */
    private fun permissoesDocumentadas(): Set<String> {
        val doc = checkNotNull(
            generateSequence(modulo) { it.parentFile }.map { File(it, "docs/PERMISSOES.md") }.firstOrNull { it.isFile },
        ) { "docs/PERMISSOES.md não encontrado a partir de $modulo" }
        val tabela = doc.readLines().dropWhile { !it.startsWith("## Permissões que o app declara") }
            .drop(1).takeWhile { !it.startsWith("## ") }
        return tabela.mapNotNull { Regex("^\\|\\s*`([^`]+)`").find(it)?.groupValues?.get(1) }.toSet()
    }

    /** Manifesto final do debug (com bibliotecas), pelo mesmo arquivo de configuração que o Robolectric lê. */
    private fun manifestoFinal(): File {
        val configuracao = checkNotNull(
            javaClass.getResourceAsStream("/com/android/tools/test_config.properties"),
        ) { "test_config.properties ausente: ligue testOptions.unitTests.isIncludeAndroidResources" }
        val propriedades = Properties().apply { configuracao.use { load(it) } }
        return File(modulo, checkNotNull(propriedades.getProperty("android_merged_manifest")))
    }

    private fun assertIgual(onde: String, documentadas: Set<String>, atuais: Set<String>) =
        assertTrue(
            "$onde: fora de docs/PERMISSOES.md = ${atuais - documentadas}; documentada e ausente = ${documentadas - atuais}",
            documentadas == atuais,
        )

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
        const val TOOLS = "http://schemas.android.com/tools"
        val SO_NO_DEBUG = setOf("androidx.compose.ui.tooling.PreviewActivity", "androidx.activity.ComponentActivity")
    }
}
