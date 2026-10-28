package ai.aura.app.integration

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Leva o APK escolhido para um lugar onde o Termux consegue ler. Devolve o caminho de arquivo. */
fun interface ApkSharer {
    suspend fun share(uri: String, displayName: String): String
}

/** Nome seguro para arquivo: só letras, números, ponto, hífen e sublinhado; sempre termina em .apk. */
fun sharedApkName(displayName: String): String {
    val last = displayName.substringAfterLast('/')
    val base = if (last.endsWith(".apk", ignoreCase = true)) last.dropLast(4) else last
    val clean = base.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_', '.').take(80).ifBlank { "app" }
    return "$clean.apk"
}

/**
 * POR QUE copiar: o seletor devolve um `content://` que só o AURA sabe abrir (o Termux é outro usuário Linux, sem
 * acesso a esse provedor), e a pasta privada do app (`filesDir`) também é invisível para ele. O motor recebe um caminho
 * de arquivo e confere `Path(apk).is_file()` dentro do Termux. Então o APK vai para uma pasta COMPARTILHADA:
 *  - Android 10+: `Download/AURA/` via MediaStore (não pede permissão; o Termux lê pelo armazenamento compartilhado,
 *    depois de `termux-setup-storage`, que o onboarding já orienta);
 *  - Android 8/9: `getExternalFilesDir` (legível no armazenamento compartilhado desses sistemas).
 * O AURA só trabalha sobre esta cópia; o original e o aparelho não são tocados (contrato, invariante 2).
 */
class SharedApkCopier(private val context: Context) : ApkSharer {

    override suspend fun share(uri: String, displayName: String): String = withContext(Dispatchers.IO) {
        val source = Uri.parse(uri)
        val name = sharedApkName(displayName)
        val resolver = context.contentResolver
        val input = resolver.openInputStream(source)
            ?: throw IOException("Não consegui abrir o APK escolhido. Escolha o arquivo de novo.")
        input.use { stream ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                // Cópia anterior com o mesmo nome sai antes: senão o MediaStore cria "nome (1).apk".
                runCatching {
                    resolver.delete(
                        collection,
                        "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                        arrayOf(name, "$RELATIVE%"),
                    )
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val target = resolver.insert(collection, values)
                    ?: throw IOException("Não consegui criar a pasta Download/AURA. Libere espaço e tente de novo.")
                try {
                    (resolver.openOutputStream(target) ?: throw IOException("Não consegui gravar em Download/AURA."))
                        .use { out -> stream.copyTo(out) }
                    resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) {
                    runCatching { resolver.delete(target, null, null) }
                    throw e
                }
                val real = runCatching {
                    @Suppress("DEPRECATION")
                    resolver.query(target, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                }.getOrNull()
                real?.takeIf { it.isNotBlank() } ?: "/storage/emulated/0/$RELATIVE$name"
            } else {
                val dir = context.getExternalFilesDir(null)?.let { File(it, "reform") }
                    ?: throw IOException("Sem armazenamento compartilhado. Libere espaço no aparelho e tente de novo.")
                dir.mkdirs()
                val out = File(dir, name)
                out.outputStream().use { stream.copyTo(it) }
                out.absolutePath
            }
        }
    }

    private companion object {
        const val RELATIVE = "Download/AURA/"
    }
}
