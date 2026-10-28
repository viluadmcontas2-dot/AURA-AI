package ai.aura.app.ui.screens.settings

import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.components.AuraButtonText
import ai.aura.app.ui.components.AuraCard
import ai.aura.app.ui.components.StatusChip
import ai.aura.app.ui.components.StatusKind
import ai.aura.app.ui.engine.AUTH_PROVIDER_IDS
import ai.aura.app.ui.engine.AuthStartResult
import ai.aura.app.ui.engine.AuthState
import ai.aura.app.ui.engine.AuthStatus
import ai.aura.app.ui.engine.EngineEvent
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.ToolActionResult
import ai.aura.app.integration.SetupGateway
import ai.aura.app.ui.screens.connections.InstallConsentGate
import ai.aura.app.ui.screens.connections.InstallFlow
import ai.aura.app.ui.screens.connections.InstallText
import ai.aura.app.ui.screens.connections.LocalInstallConsent
import ai.aura.app.ui.screens.connections.LocalSetupGateway
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Nome para a tela de cada provedor (ids do contrato em AUTH_PROVIDER_IDS). */
internal fun providerLabel(id: String): String = when (id) {
    "claude" -> "Claude"
    "gemini" -> "Gemini"
    "agy" -> "Antigravity"
    "codex" -> "Codex"
    "github" -> "GitHub"
    "gdrive" -> "Google Drive"
    else -> id
}

/** Alvo do plano de instalação (C4) de cada conta: o motor instala `claude` à parte e os outros por `cli`. */
internal fun installTarget(provider: String): Pair<String, String?> = when (provider) {
    "claude" -> "claude" to null
    "github" -> "cli" to "gh"
    "gdrive", "google" -> "cli" to "rclone"
    else -> "cli" to provider
}

internal fun installReason(provider: String): String =
    if (provider == "claude") "Responder aos seus pedidos" else "Conectar ${providerLabel(provider)}"

/** Passo ativo: link e código de uso único vindos de auth.prompt. Nunca há token aqui. */
internal data class AuthPrompt(val provider: String, val url: String?, val code: String?)

internal const val AUTH_WAIT_MILLIS = 5 * 60_000L
internal const val AUTH_POLL_MILLIS = 2_000L

/**
 * Login das contas pelo motor (POST /v1/auth/start, GET /v1/auth/status, eventos auth.*). O motor roda o
 * login oficial do programa DENTRO do Termux e devolve só link https e código curto: este estado mostra os
 * dois, confere o estado até "Conectado" e nunca vê senha, token ou cookie. Usado pela tela Conexões e pelo
 * passo "Conectar suas contas" da abertura, para as duas falarem igual.
 */
internal class AccountsAuthState(
    private val gateway: EngineGateway,
    private val scope: CoroutineScope,
    private val pollMillis: Long = AUTH_POLL_MILLIS,
    private val waitMillis: Long = AUTH_WAIT_MILLIS,
    /** Instalar programas: pede o plano ao motor e só instala depois do aceite no [consent]. Sem eles, nada se instala. */
    setup: SetupGateway? = null,
    private val consent: InstallConsentGate? = null,
    installPollMillis: Long = 2_000,
) {
    private val installFlow: InstallFlow? =
        if (setup != null && consent != null) InstallFlow(setup, consent, scope, installPollMillis) else null

    var statuses by mutableStateOf<List<AuthStatus>>(emptyList()); private set
    var loaded by mutableStateOf(false); private set

    /** O motor respondeu de verdade à última conferência. Sem isso, "Indisponível" é falta de resposta, não fato sobre a conta. */
    var engineAnswered by mutableStateOf(false); private set

    /** Conta com login em andamento (do toque em Conectar até Conectado, erro ou cancelamento). */
    var starting by mutableStateOf<String?>(null); private set
    var prompt by mutableStateOf<AuthPrompt?>(null); private set
    var notice by mutableStateOf<SettingsNotice?>(null); private set
    var copied by mutableStateOf(false)

    /** Conta cujo programa está sendo instalado (do toque em Instalar até o resultado). Impede o toque duplo. */
    var installing by mutableStateOf<String?>(null); private set

    fun statusOf(provider: String): AuthStatus? = statuses.firstOrNull { it.provider == provider }

    suspend fun refresh() {
        val answer = runCatching { gateway.authStatus() }.getOrDefault(emptyList())
        engineAnswered = answer.isNotEmpty()
        statuses = answer.ifEmpty { AUTH_PROVIDER_IDS.map { AuthStatus(it, AuthState.Unavailable) } }
        loaded = true
    }

    private fun finish(ok: Boolean, text: String) {
        prompt = null
        starting = null
        copied = false
        notice = SettingsNotice(ok, text)
    }

    suspend fun onEvent(e: EngineEvent) {
        if (e.type == "download.notice") {
            // Aviso, nunca bloqueio: o dono decide se espera o Wi-Fi.
            val wifi = e.payload["waiting_wifi"] == "true" || e.payload["state"] == "aguardando_wifi"
            notice = SettingsNotice(
                true,
                if (wifi) "Aguardando Wi-Fi. Conecte ao Wi-Fi e eu continuo de onde parei."
                else "Download grande (mais de 100 MB). Prefira o Wi-Fi; nada foi bloqueado.",
            )
            return
        }
        if (e.type == "tool.install") {
            // O motor diz installing | ready | failed | waiting_wifi; os nomes em português vêm de motores antigos.
            when (e.payload["state"]) {
                "aguardando_wifi", "waiting_wifi" ->
                    notice = SettingsNotice(true, "Aguardando Wi-Fi. Conecte ao Wi-Fi e eu continuo de onde parei.")
                "ready", "concluido", "instalado", "failed", "falhou" -> refresh()
            }
            return
        }
        val p = e.payload["provider"]?.let { if (it == "google") "gdrive" else it } ?: return
        val mine = p == starting || p == prompt?.provider
        if (!mine) return
        when (e.type) {
            "auth.prompt" -> {
                prompt = AuthPrompt(p, e.payload["url"], e.payload["code"])
                copied = false
            }
            "auth.done" -> {
                refresh()
                finish(true, "${providerLabel(p)} conectado.")
            }
            "auth.failed" -> {
                val reason = e.payload["reason"] ?: "Não deu para conectar ${providerLabel(p)}."
                val fix = e.payload["fix"] ?: "Tente de novo."
                refresh()
                finish(false, "$reason $fix")
            }
        }
    }

    /** Plano B para um auth.done perdido: confere o estado de tempos em tempos, até [waitMillis]. */
    suspend fun waitForLogin(provider: String) {
        var waited = 0L
        while (waited < waitMillis) {
            delay(pollMillis)
            waited += pollMillis
            refresh()
            if (statusOf(provider)?.state == AuthState.Connected) {
                if (starting == provider) finish(true, "${providerLabel(provider)} conectado.")
                return
            }
        }
        if (starting == provider) {
            finish(false, "O tempo acabou. Toque em Conectar ${providerLabel(provider)} e tente de novo.")
        }
    }

    fun connect(provider: String) {
        notice = null
        prompt = null
        starting = provider
        scope.launch {
            val r = try {
                gateway.startAuth(provider)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            when (r) {
                null -> finish(false, "O motor não respondeu. $ENGINE_DOWN_HINT")
                is AuthStartResult.Failed -> finish(false, "${r.reason} ${r.fix}")
                is AuthStartResult.Prompted -> {
                    if (starting == provider) prompt = AuthPrompt(provider, r.url, r.code)
                }
                AuthStartResult.Started -> Unit // o link e o código chegam pelo evento auth.prompt
            }
        }
    }

    /**
     * "Instalar X": NUNCA instala direto. Pede o plano ao motor, mostra "Instalar programas necessários?" e só
     * depois do aceite o motor baixa. Recusa, plano vencido ou alterado e tamanho desconhecido não instalam nada.
     */
    fun install(provider: String, variant: String? = null) {
        if (installing != null) return // toque duplo: já tem uma instalação em andamento
        val flow = installFlow
        val label = providerLabel(provider)
        if (flow == null) {
            notice = SettingsNotice(false, InstallText.NO_GATE + " Abra o AURA de novo e tente outra vez.")
            return
        }
        installing = provider
        notice = SettingsNotice(true, "Preparando a instalação de $label…")
        val (target, resource) = installTarget(provider)
        flow.start(
            target, installReason(provider), resource, variant,
            onStatus = { item -> notice = SettingsNotice(true, "Instalando $label" + (item?.let { " · $it" } ?: "") + ". Pode sair desta tela.") },
        ) { outcome -> installDone(provider, label, outcome) }
    }

    private fun installDone(provider: String, label: String, outcome: InstallFlow.Outcome) {
        if (outcome is InstallFlow.Outcome.NeedsNewPlan) {
            // O caminho padrão falhou. O alternativo baixa outras coisas: novo plano, nova confirmação.
            installing = null
            notice = SettingsNotice(true, "O caminho padrão não funcionou. Há outro caminho para $label; vou pedir a sua confirmação de novo.")
            install(provider, variant = "glibc-runner")
            return
        }
        installing = null
        notice = when (outcome) {
            InstallFlow.Outcome.Completed -> SettingsNotice(true, "$label instalado. Agora toque em Entrar no $label.")
            InstallFlow.Outcome.NotNeeded -> SettingsNotice(true, "$label já está instalado.")
            InstallFlow.Outcome.Declined -> SettingsNotice(true, InstallText.DECLINED)
            is InstallFlow.Outcome.Rejected -> SettingsNotice(false, InstallText.rejected(outcome.reason))
            is InstallFlow.Outcome.Failed -> SettingsNotice(
                false,
                "A instalação parou em ${outcome.plan.progressItem ?: label}. Seu trabalho foi guardado. " +
                    "Toque em Instalar $label para revisar e tentar de novo.",
            )
            InstallFlow.Outcome.WaitingWifi -> SettingsNotice(
                true, "Aguardando Wi-Fi. Conecte ao Wi-Fi e toque em Instalar $label de novo; nada foi baixado em dados móveis.",
            )
            InstallFlow.Outcome.Cancelled -> SettingsNotice(true, "Instalação cancelada. O que já foi instalado continua no Termux.")
            InstallFlow.Outcome.TimedOut -> SettingsNotice(false, "A instalação está demorando. Abra Conexões mais tarde para conferir.")
            is InstallFlow.Outcome.Unavailable -> SettingsNotice(false, outcome.message + " " + ENGINE_DOWN_HINT)
            is InstallFlow.Outcome.NeedsNewPlan -> null
        }
        scope.launch { refresh() }
    }

    /** O dono colou o código que a página do Claude mostrou. O resultado final vem por auth.done ou pela conferência. */
    fun sendCode(code: String) {
        val p = prompt?.provider ?: return
        if (code.isBlank()) { notice = SettingsNotice(false, "Cole o código que apareceu na página e toque em Enviar código."); return }
        scope.launch {
            when (val r = runCatchingNotCancelled { gateway.submitAuthCode(p, code) }) {
                is ToolActionResult.Failed -> notice = SettingsNotice(false, "${r.reason} ${r.fix}")
                is ToolActionResult.Ok -> notice = SettingsNotice(true, r.message)
                null -> notice = SettingsNotice(false, "O motor não respondeu. $ENGINE_DOWN_HINT")
            }
        }
    }

    /** Alternativa: token colado. Vai direto ao motor; não é guardado nem mostrado aqui. */
    fun sendToken(token: String) {
        val p = prompt?.provider ?: starting ?: return
        if (token.isBlank()) { notice = SettingsNotice(false, "Cole o token e toque em Enviar token."); return }
        scope.launch {
            when (val r = runCatchingNotCancelled { gateway.submitAuthToken(p, token) }) {
                is ToolActionResult.Failed -> notice = SettingsNotice(false, "${r.reason} ${r.fix}")
                is ToolActionResult.Ok -> { refresh(); finish(true, "${providerLabel(p)} conectado.") }
                null -> notice = SettingsNotice(false, "O motor não respondeu. $ENGINE_DOWN_HINT")
            }
        }
    }

    private suspend fun <T> runCatchingNotCancelled(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    fun cancel() = finish(false, "Conexão cancelada.")
}

/** Cria o estado e liga as três fontes de verdade: leitura inicial, eventos do motor e conferência periódica. */
@Composable
internal fun rememberAccountsAuth(
    gateway: EngineGateway,
    pollMillis: Long = AUTH_POLL_MILLIS,
): AccountsAuthState {
    val scope = rememberCoroutineScope()
    val setup = LocalSetupGateway.current
    val consent = LocalInstallConsent.current
    val state = remember(gateway, setup, consent) { AccountsAuthState(gateway, scope, pollMillis, setup = setup, consent = consent) }
    LaunchedEffect(state) { state.refresh() }
    LaunchedEffect(state) {
        try {
            gateway.events.collect { state.onEvent(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Sem eventos: a conferência periódica abaixo confere o estado.
        }
    }
    val active = state.starting
    LaunchedEffect(state, active) { if (active != null) state.waitForLogin(active) }
    return state
}

/** Aviso do último passo e, durante o login, o cartão com o link e o código. Vale para qualquer tela de contas. */
@Composable
internal fun AccountsPromptSlot(auth: AccountsAuthState, onOpenUrl: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val notice = auth.notice
    val prompt = auth.prompt
    val busy = auth.starting
    if (notice == null && prompt == null && busy == null) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (notice != null) {
            Text(
                notice.text,
                style = AuraType.SecondarySmall.copy(color = if (notice.ok) AuraColors.Ready.fg else AuraColors.Risk.fg),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (prompt != null) {
            AuthPromptCard(
                step = prompt, copied = auth.copied,
                onCopy = { prompt.code?.let { clipboard.setText(AnnotatedString(it)); auth.copied = true } },
                onOpen = { prompt.url?.let(onOpenUrl) },
                onCancel = auth::cancel,
                onSendCode = auth::sendCode, onSendToken = auth::sendToken,
            )
        } else if (busy != null) {
            Text(
                "Preparando a entrada em ${providerLabel(busy)}…", style = AuraType.Secondary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** Lista das contas com o estado real do motor e UM botão por linha: Conectar, Reconectar ou Instalar. */
@Composable
internal fun AccountsList(auth: AccountsAuthState, exclude: Set<String> = emptySet()) {
    if (!auth.loaded) {
        Text("Carregando contas…", style = AuraType.Secondary)
        return
    }
    SettingsGroup {
        auth.statuses.filter { it.provider !in exclude }.forEachIndexed { i, st ->
            if (i > 0) RowDivider()
            AccountRow(
                st = st, busy = auth.starting != null || auth.installing != null,
                onConnect = { auth.connect(st.provider) }, onInstall = { auth.install(st.provider) },
            )
        }
    }
}

@Composable
private fun AccountRow(st: AuthStatus, busy: Boolean, onConnect: () -> Unit, onInstall: () -> Unit) {
    val name = providerLabel(st.provider)
    val (kind, chip) = when (st.state) {
        AuthState.Connected -> StatusKind.Ready to "Conectado"
        AuthState.Disconnected -> StatusKind.Neutral to "Desconectado"
        AuthState.Expired -> StatusKind.NeedsYou to "Expirado"
        AuthState.Unavailable -> StatusKind.Neutral to if (st.installable) "Não instalado" else "Indisponível"
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(name, style = AuraType.BodyStrong, modifier = Modifier.weight(1f))
            StatusChip(kind, chip)
        }
        st.detail?.let { Text(it, style = AuraType.SecondarySmall) }
        when {
            st.state == AuthState.Disconnected -> AuraButtonSecondary("Entrar no $name", onConnect, enabled = !busy)
            st.state == AuthState.Expired -> AuraButtonSecondary("Entrar novamente no $name", onConnect, enabled = !busy)
            st.state == AuthState.Unavailable && st.installable ->
                AuraButtonSecondary("Instalar $name", onInstall, enabled = !busy)
            else -> Unit
        }
    }
}

/** Passo único: o botão principal é Abrir link; o código vem com Copiar código. */
@Composable
private fun AuthPromptCard(
    step: AuthPrompt, copied: Boolean, onCopy: () -> Unit, onOpen: () -> Unit, onCancel: () -> Unit,
    onSendCode: (String) -> Unit, onSendToken: (String) -> Unit,
) {
    // Claude: o link abre no navegador do celular e a página mostra um código para colar aqui.
    val pasteCode = step.provider == "claude" && step.code == null
    var pasted by remember { mutableStateOf("") }
    var tokenMode by remember { mutableStateOf(false) }
    AuraCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Conectar ${providerLabel(step.provider)}", style = AuraType.Section, modifier = Modifier.semantics { heading() })
            Text(
                when {
                    step.code != null -> "Entre na sua conta na página que será aberta e digite o código."
                    pasteCode -> "Entre na sua conta na página que será aberta e cole aqui o código que aparecer."
                    else -> "Entre na sua conta na página que será aberta."
                },
                style = AuraType.Body,
            )
            if (step.code != null) {
                Text(step.code, style = AuraType.Mono.copy(fontSize = 24.sp, lineHeight = 32.sp), modifier = Modifier.padding(vertical = 4.dp))
                AuraButtonSecondary(if (copied) "Código copiado" else "Copiar código", onCopy, Modifier.fillMaxWidth())
            }
            AuraButtonPrimary("Abrir página de entrada", onOpen, Modifier.fillMaxWidth(), enabled = step.url != null, large = true)
            if (pasteCode) {
                OutlinedTextField(
                    value = pasted, onValueChange = { pasted = it }, singleLine = true,
                    label = { Text(if (tokenMode) "Token do Claude" else "Código do Claude") },
                    visualTransformation = if (tokenMode) PasswordVisualTransformation() else VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )
                AuraButtonSecondary(
                    if (tokenMode) "Enviar token" else "Enviar código",
                    { val v = pasted; pasted = ""; if (tokenMode) onSendToken(v) else onSendCode(v) },
                    Modifier.fillMaxWidth(),
                )
                AuraButtonText(
                    if (tokenMode) "Prefiro colar o código" else "Prefiro colar um token",
                    { tokenMode = !tokenMode; pasted = "" }, Modifier.fillMaxWidth(),
                )
            }
            Text("Quando terminar, volte aqui. Eu aviso sozinho.", style = AuraType.SecondarySmall)
            AuraButtonText("Cancelar", onCancel, Modifier.fillMaxWidth())
        }
    }
}
