package ai.aura.app.ui.screens.reform

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns

/** Tipo que o Android usa para .apk; é o único que o AURA aceita receber por "Compartilhar". */
const val APK_MIME = "application/vnd.android.package-archive"

/**
 * "Compartilhar para o AURA": transforma o que chegou na intent em um app da reforma, ou null se não for um APK.
 * Parte pura (sem Android) para ser testada na JVM; [sharedApkFromIntent] só lê a intent e chama esta.
 */
fun sharedApkFrom(action: String?, type: String?, stream: String?, displayName: String?): ReformApp? {
    if (action != Intent.ACTION_SEND || stream.isNullOrBlank()) return null
    val name = displayName?.takeIf { it.isNotBlank() }
        ?: stream.substringAfterLast('/').takeIf { it.isNotBlank() }
    val isApk = type.equals(APK_MIME, ignoreCase = true) || name?.endsWith(".apk", ignoreCase = true) == true
    if (!isApk) return null
    return ReformApp(id = stream, label = name?.removeSuffix(".apk") ?: "APK recebido", apkUri = stream)
}

/** Lê a intent de abertura do app. Falha ao consultar o nome não impede o recebimento. */
@Suppress("DEPRECATION")
fun sharedApkFromIntent(context: android.content.Context, intent: Intent?): ReformApp? {
    intent ?: return null
    val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return null
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull()
    return sharedApkFrom(intent.action, intent.type, uri.toString(), name)
}
