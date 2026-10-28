package ai.aura.app.integration

import ai.aura.app.offline.TermuxResult
import org.json.JSONObject

/** Valida a resposta antes de salvar qualquer credencial; nunca expõe stdout à interface. */
fun termuxResultFrom(
    operation: String,
    exitCode: Int?,
    internalError: Int,
    errorMessage: String?,
    stdout: String?,
    save: (String) -> Unit,
): TermuxResult {
    if (internalError != -1 && errorMessage?.contains("allow-external-apps", ignoreCase = true) == true) {
        return TermuxResult.NotAllowed
    }
    if (exitCode != 0 || internalError != -1) return TermuxResult.Failed
    return when (operation) {
        "probe" -> if (stdout?.trim() == "aura-ok") TermuxResult.Ok else TermuxResult.Failed
        "pair" -> try {
            val value = JSONObject(stdout.orEmpty().trim())
            require(value.getString("service") == "aura-engine")
            require(value.getString("host") == "127.0.0.1")
            require(value.getInt("port") == 8765)
            val token = value.getString("token")
            require(token.length in 32..512 && token.all { it.isLetterOrDigit() || it == '-' || it == '_' })
            save(token)
            TermuxResult.Ok
        } catch (_: Exception) {
            TermuxResult.Failed
        }
        else -> TermuxResult.Failed
    }
}
