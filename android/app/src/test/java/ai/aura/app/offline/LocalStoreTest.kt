package ai.aura.app.offline

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store(dir: File = tmp.root) = LocalStore(dir)

    @Test fun `ler um nome que nunca foi gravado devolve null`() {
        assertNull(store().read("projetos"))
    }

    @Test fun `gravar e ler devolve o mesmo conteudo`() {
        store().write("projetos", JSONObject().put("total", 3).put("titulo", "Reformar app"))

        val lido = store().read("projetos")!!

        assertEquals(3, lido.getInt("total"))
        assertEquals("Reformar app", lido.getString("titulo"))
    }

    @Test fun `gravar de novo substitui o conteudo anterior`() {
        val s = store()
        s.write("rascunho", JSONObject().put("texto", "primeiro"))
        s.write("rascunho", JSONObject().put("texto", "segundo"))

        assertEquals("segundo", s.read("rascunho")!!.getString("texto"))
    }

    @Test fun `uma segunda instancia na mesma pasta enxerga o que foi gravado, como um app reaberto`() {
        store().write("fila", JSONObject().put("n", 7))

        assertEquals(7, store().read("fila")!!.getInt("n"))
    }

    @Test fun `arquivo corrompido devolve null em vez de lancar`() {
        File(tmp.root, "projetos.json").writeText("{\"total\": 3, ")

        assertNull(store().read("projetos"))
    }

    @Test fun `sobra de gravacao interrompida nao esconde o ultimo valor bom`() {
        val s = store()
        s.write("projetos", JSONObject().put("total", 1))
        // Simula a queda de energia no meio de uma nova gravacao: so o temporario ficou pela metade.
        File(tmp.root, "projetos.json.tmp").writeText("{\"total\": 99, ")

        assertEquals(1, store().read("projetos")!!.getInt("total"))
    }

    @Test fun `depois de uma gravacao interrompida a proxima gravacao funciona`() {
        File(tmp.root, "projetos.json.tmp").writeText("lixo")
        val s = store()

        s.write("projetos", JSONObject().put("total", 2))

        assertEquals(2, s.read("projetos")!!.getInt("total"))
    }

    @Test fun `apagar remove o arquivo`() {
        val s = store()
        s.write("fila", JSONObject().put("n", 1))

        s.delete("fila")

        assertNull(store().read("fila"))
    }

    @Test fun `apagar um nome que nao existe nao lanca`() {
        store().delete("nunca-existiu")
    }

    @Test fun `nomes com barra ou ponto ponto sao recusados e nada sai da pasta`() {
        val s = store()

        assertThrows(IllegalArgumentException::class.java) { s.read("../segredo") }
        assertThrows(IllegalArgumentException::class.java) { s.write("a/b", JSONObject()) }
        assertThrows(IllegalArgumentException::class.java) { s.delete("..") }
        assertThrows(IllegalArgumentException::class.java) { s.read("") }
    }

    @Test fun `a pasta e criada se ainda nao existe`() {
        val dir = File(tmp.root, "aura/dados")

        LocalStore(dir).write("x", JSONObject().put("ok", true))

        assertEquals(true, LocalStore(dir).read("x")!!.getBoolean("ok"))
    }
}
