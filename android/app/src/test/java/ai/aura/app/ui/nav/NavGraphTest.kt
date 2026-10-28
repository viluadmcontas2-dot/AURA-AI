package ai.aura.app.ui.nav

import ai.aura.app.ui.AuraTab
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** O mapa de telas (ROUTES) contra os arquivos de docs/design/telas. JVM puro, sem Compose. */
class NavGraphTest {
    /** Os 5 arquivos que documentam o design e não são telas do app. */
    private val documentacao = setOf("Progresso", "Fluxo-do-usuario", "Contrato", "Sistema-Cores-Tipo", "Sistema-Componentes")
    private val entradas = listOf("onb-boas-vindas", "inicio")

    private fun telas(): Set<String> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val pasta = File(dir, "docs/design/telas")
            if (pasta.isDirectory) {
                return pasta.listFiles { f -> f.name.endsWith(".dc.html") }!!
                    .map { it.name.removeSuffix(".dc.html") }.toSet() - documentacao
            }
            dir = dir.parentFile
        }
        error("docs/design/telas não encontrado a partir de ${File("").absolutePath}")
    }

    @Test
    fun todoArquivoDoDesignTemRotaEIdsSaoUnicos() {
        val designs = ROUTES.mapNotNull { it.design }.toSet()
        assertEquals("ids repetidos", ROUTES.size, ROUTES.map { it.id }.toSet().size)
        assertEquals("arquivos do design sem rota", emptySet<String>(), telas() - designs)
        assertEquals("rota que aponta para arquivo inexistente", emptySet<String>(), designs - telas())
    }

    @Test
    fun todaArestaApontaParaRotaExistente() {
        val ids = ROUTES.map { it.id }.toSet()
        for (rota in ROUTES) for (destino in rota.edges) assertTrue("${rota.id} -> $destino não existe", destino in ids)
    }

    @Test
    fun tudoEAlcancavelDasEntradas() {
        val porId = ROUTES.associateBy { it.id }
        val vistas = mutableSetOf<String>()
        val fila = ArrayDeque(entradas)
        while (fila.isNotEmpty()) {
            val id = fila.removeFirst()
            if (vistas.add(id)) fila += porId.getValue(id).edges
        }
        assertEquals("rotas inalcançáveis", emptySet<String>(), ROUTES.map { it.id }.toSet() - vistas)
    }

    /** Informativo até a T17, que troca o corpo por `assertEquals(emptyList<String>(), pending)` (portão de entrega). */
    @Test
    fun pendingEOQueAindaNaoTemTela() {
        val pending = ROUTES.filter { it.screen == null }.map { it.id }
        println("pending (${pending.size} de ${ROUTES.size}): $pending")
    }

    @Test
    fun voltarDesempilhaENaRaizDeixaOSistemaFechar() {
        assertEquals(listOf("inicio", "ajustes"), pushRoute(listOf("inicio"), "ajustes"))
        // Rota sem tela não abre: nunca tela em branco nem "em construção".
        assertEquals(listOf("inicio"), pushRoute(listOf("inicio"), "chat-entendi"))
        assertEquals(listOf("inicio"), popRoute(listOf("inicio", "ajustes")))
        assertEquals(listOf("inicio"), popRoute(tabStack(AuraTab.Projects)))
        assertNull(popRoute(listOf("inicio")))
        for (aba in AuraTab.entries) {
            val raiz = ROUTES.single { it.id == tabStack(aba).last() }
            assertEquals(aba, raiz.tab)
            assertTrue("raiz da aba $aba sem tela", raiz.screen != null)
        }
    }
}
