package ai.aura.app.ui.screens.connections

import ai.aura.app.integration.SetupStatus
import ai.aura.app.offline.LinkState
import ai.aura.app.offline.OfflineReason
import ai.aura.app.ui.components.StatusKind
import ai.aura.app.ui.engine.AuthState
import ai.aura.app.ui.engine.AuthStatus
import ai.aura.app.ui.screens.onboarding.TermuxSetupState

/*
 * Conexões = preparar o assistente (docs/FLUXOS_DE_USO.md, seção 6). Esta função pura decide, a partir de fatos
 * VERIFICADOS, o estado de cada grupo e UMA ação principal. Ela nunca afirma "Pronto" sem a conta do Claude ter sido
 * confirmada pelo motor, e nunca transforma "não consegui verificar" em "desconectado".
 */

/** Ação principal contextual. [label] é verbo + objeto, igual ao botão e ao aviso. */
enum class ConnectionAction(val label: String) {
    InstallTermux("Instalar Termux"),
    AllowTermux("Permitir acesso ao Termux"),
    PrepareEngine("Preparar motor"),
    StartEngine("Iniciar o motor"),
    PairTermux("Conectar Termux"),
    InstallClaude("Instalar o Claude"),
    SignInClaude("Entrar no Claude"),
    SignInAgain("Entrar novamente"),
    CheckSignIn("Verificar entrada"),
}

/** O que se sabe da conta do Claude. Só [Verified] vem de uma verificação real do motor. */
enum class ClaudeSignal { Unknown, NotInstalled, SignedOut, Expired, Unverified, SigningIn, Verified }

data class GroupLine(val chip: String, val kind: StatusKind, val hint: String)

data class ConnectionsPlan(
    val assistant: GroupLine,
    val claude: GroupLine,
    /** A única ação principal da tela. */
    val action: ConnectionAction?,
    /** O grupo que mostra o botão principal: o do passo atual. */
    val actionInAssistant: Boolean,
    val ready: Boolean,
)

/** Resume o que o motor sabe da conta do Claude. [signingIn] = login em andamento neste aparelho. */
fun claudeSignal(auth: AuthStatus?, setup: SetupStatus?, signingIn: Boolean): ClaudeSignal = when {
    signingIn -> ClaudeSignal.SigningIn
    auth == null && setup == null -> ClaudeSignal.Unknown
    setup?.claudeInstallation == "missing" || auth?.state == AuthState.Unavailable -> ClaudeSignal.NotInstalled
    auth?.state == AuthState.Connected || setup?.claudeAuth == "signed_in" -> ClaudeSignal.Verified
    auth?.state == AuthState.Expired || setup?.claudeAuth == "expired" -> ClaudeSignal.Expired
    setup?.claudeAuth == "unknown" || setup?.claudeInstallation == "unsupported" ||
        auth?.detail?.contains("Sem resposta", ignoreCase = true) == true -> ClaudeSignal.Unverified
    else -> ClaudeSignal.SignedOut
}

fun connectionsPlan(link: LinkState, termux: TermuxSetupState?, claude: ClaudeSignal): ConnectionsPlan {
    val offlineClaude = GroupLine("Indisponível", StatusKind.Neutral, "Ligue o assistente para conferir a sua conta do Claude.")
    fun stop(chip: String, kind: StatusKind, hint: String, action: ConnectionAction?) =
        ConnectionsPlan(GroupLine(chip, kind, hint), offlineClaude, action, actionInAssistant = true, ready = false)

    return when (link) {
        LinkState.Starting -> stop("Iniciando", StatusKind.Info, "Aguarde alguns segundos. Eu confiro sozinho.", null)
        is LinkState.Offline -> when (link.reason) {
            OfflineReason.NoTermux -> stop(
                "Sem Termux", StatusKind.NeedsYou, "O Termux não está instalado. Instale pela F-Droid e volte aqui.",
                ConnectionAction.InstallTermux,
            )
            OfflineReason.NoPermission -> stop(
                "Sem permissão", StatusKind.NeedsYou, "O AURA ainda não tem permissão para falar com o Termux.",
                ConnectionAction.AllowTermux,
            )
            OfflineReason.NoToken -> stop(
                "Sem vínculo", StatusKind.NeedsYou, "Falta conectar o Termux ao AURA.", ConnectionAction.PairTermux,
            )
            OfflineReason.EngineStopped -> stoppedOrNotInstalled(termux, ::stop)
        }
        LinkState.Unknown -> stoppedOrNotInstalled(termux, ::stop)
        LinkState.Online -> {
            val engine = GroupLine("Ligado", StatusKind.Ready, "O motor responde neste celular.")
            when (claude) {
                ClaudeSignal.Unknown -> ConnectionsPlan(
                    engine, GroupLine("Verificando", StatusKind.Neutral, "Verificando entrada…"), null, false, false,
                )
                ClaudeSignal.SigningIn -> ConnectionsPlan(
                    engine, GroupLine("Entrando", StatusKind.Info, "Verificando entrada…"), null, false, false,
                )
                ClaudeSignal.NotInstalled -> ConnectionsPlan(
                    engine, GroupLine("Não instalado", StatusKind.Neutral, "O Claude ainda não está neste celular. Antes de baixar qualquer coisa eu peço a sua confirmação."),
                    ConnectionAction.InstallClaude, false, false,
                )
                ClaudeSignal.SignedOut -> ConnectionsPlan(
                    engine, GroupLine("Precisa entrar na conta", StatusKind.NeedsYou, "Entre na sua conta na página que será aberta."),
                    ConnectionAction.SignInClaude, false, false,
                )
                ClaudeSignal.Expired -> ConnectionsPlan(
                    engine, GroupLine("A entrada expirou", StatusKind.NeedsYou, "A entrada expirou."),
                    ConnectionAction.SignInAgain, false, false,
                )
                ClaudeSignal.Unverified -> ConnectionsPlan(
                    engine, GroupLine("Não consegui confirmar", StatusKind.Neutral, "Não consegui confirmar a entrada."),
                    ConnectionAction.CheckSignIn, false, false,
                )
                ClaudeSignal.Verified -> ConnectionsPlan(
                    GroupLine("Pronto", StatusKind.Ready, "O assistente está ligado e a sua conta foi confirmada."),
                    GroupLine("Conectado", StatusKind.Ready, "Entrada confirmada pelo motor."), null, false, true,
                )
            }
        }
    }
}

private fun stoppedOrNotInstalled(
    termux: TermuxSetupState?,
    stop: (String, StatusKind, String, ConnectionAction?) -> ConnectionsPlan,
): ConnectionsPlan =
    if (termux?.engineInstalled == false) {
        stop(
            "Não instalado", StatusKind.NeedsYou,
            "O assistente ainda não está neste celular. Antes de instalar qualquer coisa eu peço a sua confirmação.",
            ConnectionAction.PrepareEngine,
        )
    } else {
        stop(
            "Parado", StatusKind.NeedsYou, "O motor está parado. O que você pedir fica guardado até ele ligar.",
            ConnectionAction.StartEngine,
        )
    }
