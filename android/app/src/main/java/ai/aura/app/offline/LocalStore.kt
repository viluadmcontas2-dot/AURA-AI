package ai.aura.app.offline

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Guarda um JSON por nome na pasta [dir] (`<nome>.json`). Feito para dados pequenos: ler e gravar
 * são síncronos, na thread de quem chama.
 *
 * A gravação é atômica: escreve em `<nome>.json.tmp`, força para o disco e só então troca o arquivo
 * de verdade. Se o app morrer no meio, sobra um `.tmp` que a leitura ignora e a próxima gravação
 * sobrescreve; o último valor bom continua lá. Arquivo corrompido lê como `null`, nunca lança.
 *
 * Nomes: 1 a 100 caracteres entre letras, números, `_` e `-`. Qualquer outro (barra, `..`, vazio)
 * lança [IllegalArgumentException], então nada sai da pasta.
 */
class LocalStore(private val dir: File) {
    fun read(name: String): JSONObject? {
        val file = fileOf(name)
        if (!file.isFile) return null
        return try {
            JSONObject(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    fun write(name: String, value: JSONObject) {
        val file = fileOf(name)
        // Um cadeado só para o processo: duas gravações do mesmo nome não misturam o temporário.
        synchronized(lock) {
            dir.mkdirs()
            val tmp = File(dir, file.name + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write(value.toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /** Apagar o que não existe não é erro. */
    fun delete(name: String) {
        val file = fileOf(name)
        synchronized(lock) {
            file.delete()
            File(dir, file.name + ".tmp").delete()
        }
    }

    private fun fileOf(name: String): File {
        require(NAME.matches(name)) { "Nome de arquivo inválido" }
        return File(dir, "$name.json")
    }

    private companion object {
        val NAME = Regex("[A-Za-z0-9_-]{1,100}")
        val lock = Any()
    }
}
