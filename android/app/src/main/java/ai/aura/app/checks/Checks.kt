package ai.aura.app.checks

import ai.aura.app.offline.TermuxProbe
import ai.aura.app.ui.engine.AuthState
import ai.aura.app.ui.engine.AuthStatus
import ai.aura.app.ui.screens.onboarding.TERMUX_ALLOW_EXTERNAL_COMMAND
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Ok = tudo certo; Warning = dá para usar, mas vai falhar em algum momento; Failed = o AURA não funciona assim. */
enum class CheckState { Ok, Warning, Failed }

/**
 * [message] é a frase da linha, já no estado. [fix] é o rótulo do ÚNICO botão: com [command] ele copia o texto
 * (o que só o Termux pode fazer), sem [command] leva à tela certa (o controlador recebe o id da verificação).
 */
data class CheckResult(val state: CheckState, val message: String, val fix: String? = null, val command: String? = null)

/** Uma linha do Checkup. [run] lê o mundo; quem chama usa [safeRun]. */
class Check(val id: String, val name: String, val run: suspend () -> CheckResult)

/** Botão de quem não conseguiu verificar: refaz a verificação em vez de abrir outra tela. */
const val RETRY = "Verificar de novo"

private const val TIMEOUT_MS = 5_000L
private const val GB = 1024L * 1024 * 1024
private const val SMALL_PAGE = 4096
private const val PAGE_16K_NOTICE = "Este Android usa páginas de 16 KB e o Termux atual não roda aqui"
private const val ADB_INSTALL_COMMAND = "pkg install -y android-tools"
private val BRAINS = setOf("claude", "gemini", "agy", "codex")

/** Nunca lança: o que quebra ou passa de 5 s vira "não consegui verificar". Só o cancelamento de verdade passa. */
suspend fun Check.safeRun(): CheckResult {
    val body = run
    val result = try {
        withTimeoutOrNull(TIMEOUT_MS) { body() }
    } catch (e: TimeoutCancellationException) {
        null // prazo de dentro da verificação (ex.: pedido ao motor); não é o cancelamento do Checkup
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    return result ?: CheckResult(CheckState.Warning, "Não consegui verificar: $name", RETRY)
}

/** As verificações, na ordem da tela. O mundo entra por [termux] e por lambdas, para o teste trocar cada um. */
fun standardChecks(
    termux: TermuxProbe,
    engineUp: suspend () -> Boolean,
    accounts: suspend () -> List<AuthStatus>,
    adbInstalled: suspend () -> Boolean,
    notificationsOn: () -> Boolean,
    freeBytes: () -> Long,
    ignoresBatteryLimits: () -> Boolean,
    sdk: Int,
    pageSize: () -> Int,
): List<Check> {
    fun verdict(
        good: Boolean, ok: String, bad: String, fix: String,
        state: CheckState = CheckState.Warning, command: String? = null,
    ) = if (good) CheckResult(CheckState.Ok, ok) else CheckResult(state, bad, fix, command)

    val failed = CheckState.Failed
    return listOf(
        Check("termux", "Termux") {
            verdict(termux.installed(), "Termux instalado", "Termux não instalado", "Instalar o Termux", failed)
        },
        Check("termux_compat", "Termux compatível") {
            verdict(pageSize() <= SMALL_PAGE, "Termux compatível com este Android", PAGE_16K_NOTICE, "Copiar aviso", failed, PAGE_16K_NOTICE)
        },
        Check("run_command", "Permissão do Termux") {
            verdict(termux.hasPermission(), "AURA autorizado a usar o Termux", "AURA ainda não pode usar o Termux", "Permitir o Termux", failed)
        },
        Check("external_apps", "Comandos de outros apps") {
            // Só o motor respondendo prova que o Termux aceita comandos de outros apps; sem ele, o comando fica à mão.
            verdict(
                engineUp(), "Termux aceita comandos de outros apps", "Termux precisa aceitar comandos de outros apps",
                "Copiar comando", command = TERMUX_ALLOW_EXTERNAL_COMMAND,
            )
        },
        Check("engine", "Motor do AURA") {
            verdict(engineUp(), "Motor do AURA respondendo", "O motor do AURA está desligado.", "Iniciar o motor", failed)
        },
        Check("accounts", "Contas") {
            val on = accounts().filter { it.state == AuthState.Connected && it.provider in BRAINS }
            verdict(
                on.isNotEmpty(), "Contas conectadas: " + on.joinToString { p -> p.provider.replaceFirstChar { it.uppercase() } },
                "Nenhuma conta de IA conectada", "Conectar contas",
            )
        },
        Check("adb", "ADB") {
            // Só o Termux instala pacotes: o botão copia o comando.
            verdict(
                adbInstalled(), "ADB instalado no Termux", "ADB não instalado no Termux",
                "Copiar comando", command = ADB_INSTALL_COMMAND,
            )
        },
        Check("notifications", "Notificações") {
            verdict(notificationsOn(), "Notificações liberadas", "Notificações bloqueadas: você pode perder avisos", "Permitir notificações")
        },
        Check("storage", "Armazenamento") {
            val free = freeBytes()
            verdict(free >= 2 * GB, "Armazenamento com ${free / GB} GB livres", "Pouco espaço livre no aparelho (menos de 2 GB)", "Liberar espaço")
        },
        Check("battery", "Bateria") {
            verdict(
                ignoresBatteryLimits(), "Bateria sem restrição para o AURA",
                "Bateria: o Android pode pausar o AURA em segundo plano", "Liberar bateria",
            )
        },
        Check("long_tasks", "Tarefas longas") {
            // Android 12 (API 31) começou a encerrar processos filhos de apps em segundo plano.
            verdict(
                sdk < 31, "Termux não é encerrado em tarefas longas neste Android",
                "Termux pode ser encerrado em tarefas longas (Android 12+)", "Ver como evitar",
            )
        },
    )
}
