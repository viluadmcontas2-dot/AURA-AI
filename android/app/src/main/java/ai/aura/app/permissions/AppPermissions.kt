package ai.aura.app.permissions

/** Uma função do app que precisa de permissão do Android: por que pede e o que acontece se negar. */
data class AppPermission(
    val function: String,
    val permission: String,
    val why: String,
    val ifDenied: String,
    val minSdk: Int = 1,
    /** Pacote que cria a permissão (o Termux). Sem ele instalado o Android não tem o que perguntar. */
    val needsPackage: String? = null,
)

enum class PermissionState { Granted, CanAsk, Blocked }

/**
 * O app não pede nada ao abrir: só pergunta quando a função é usada, depois de uma folha própria.
 * Estas linhas são as da tabela "declara" de docs/PERMISSOES.md (o PermissionsTest confere com o manifesto).
 */
object AppPermissions {
    /** Negada duas vezes, o Android não pergunta mais: só as configurações do app resolvem. */
    const val MAX_DENIALS = 2

    val all: List<AppPermission> = listOf(
        AppPermission(
            "Microfone", "android.permission.RECORD_AUDIO",
            why = "Para você falar o pedido em vez de digitar.",
            ifDenied = "A voz fica desligada. Digitar continua igual.",
        ),
        AppPermission(
            "Avisos", "android.permission.POST_NOTIFICATIONS",
            why = "Para avisar quando o AURA terminar ou precisar de você.",
            ifDenied = "Nenhum aviso fora do app. Tudo continua na aba Atividade.",
            minSdk = 33,
        ),
        AppPermission(
            "Termux", "com.termux.permission.RUN_COMMAND",
            why = "Para o AURA pedir ao Termux que ligue o motor.",
            ifDenied = "O app abre e toda tela funciona. O motor fica desligado.",
            needsPackage = "com.termux",
        ),
    )

    /** Chave em aura_prefs com quantas vezes o dono negou esta permissão (a tela Conexões e a abertura leem a mesma). */
    fun denialKey(permission: String): String = "perm_denied_$permission"

    fun forSdk(sdk: Int): List<AppPermission> = all.filter { sdk >= it.minSdk }

    fun state(granted: Boolean, denials: Int): PermissionState = when {
        granted -> PermissionState.Granted
        denials >= MAX_DENIALS -> PermissionState.Blocked
        else -> PermissionState.CanAsk
    }
}
