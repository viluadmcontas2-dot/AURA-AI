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
import ai.aura.app.ui.engine.JobRequest
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
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
import java.util.UUID

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
) {
    var statuses by mutableStateOf<List<AuthStatus>>(emptyList()); private set
    var loaded by mutableStateOf(false); private set

    /** Conta com login em andamento (do toque em Conectar até Conectado, erro ou cancelamento). */
    var starting by mutableStateOf<String?>(null); private set
    var prompt by mutableStateOf<AuthPrompt?>(null); private set
    var notice by mutableStateOf<SettingsNotice?>(null); private set
    var copied by mutableStateOf(false)

    fun statusOf(provider: String): AuthStatus? = statuses.firstOrNull { it.provider == provider }

    suspend fun refresh() {
        statuses = runCatching { gateway.authStatus() }.getOrDefault(emptyList()).ifEmpty {
            AUTH_PROVIDER_IDS.map { AuthStatus(it, AuthState.Unavailable) }
        }
        loaded = true
    }

    private fun finish(ok: Boolean, text: String) {
        prompt = null
        starting = null
        copied = false
        notice = SettingsNotice(ok, text)
    }

    suspend fun onEvent(e: EngineEvent) {
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

    fun install(provider: String) {
        notice = null
        scope.launch {
            val label = providerLabel(provider)
            val job = try {
                // Um id novo a cada toque: com id fixo o motor devolveria o mesmo job (já falho).
                gateway.submit("install_cli_${provider}_${UUID.randomUUID()}", JobRequest("system", "Instalar o programa de $label"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            notice = if (job != null) SettingsNotice(true, "Pedido para instalar $label criado. Aprove em Atividade.")
            else SettingsNotice(false, ENGINE_DOWN_HINT)
        }
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
    val state = remember(gateway) { AccountsAuthState(gateway, scope, pollMillis) }
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
            )
        } else if (busy != null) {
            Text(
                "Preparando o login de ${providerLabel(busy)}…", style = AuraType.Secondary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** Lista das contas com o estado real do motor e UM botão por linha: Conectar, Reconectar ou Instalar. */
@Composable
internal fun AccountsList(auth: AccountsAuthState) {
    if (!auth.loaded) {
        Text("Carregando contas…", style = AuraType.Secondary)
        return
    }
    SettingsGroup {
        auth.statuses.forEachIndexed { i, st ->
            if (i > 0) RowDivider()
            AccountRow(
                st = st, busy = auth.starting != null,
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
            st.state == AuthState.Disconnected -> AuraButtonSecondary("Conectar $name", onConnect, enabled = !busy)
            st.state == AuthState.Expired -> AuraButtonSecondary("Reconectar $name", onConnect, enabled = !busy)
            st.state == AuthState.Unavailable && st.installable ->
                AuraButtonSecondary("Instalar $name", onInstall, enabled = !busy)
            else -> Unit
        }
    }
}

/** Passo único: o botão principal é Abrir link; o código vem com Copiar código. */
@Composable
private fun AuthPromptCard(step: AuthPrompt, copied: Boolean, onCopy: () -> Unit, onOpen: () -> Unit, onCancel: () -> Unit) {
    AuraCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Conectar ${providerLabel(step.provider)}", style = AuraType.Section, modifier = Modifier.semantics { heading() })
            Text(
                if (step.code != null) "Abra o link e digite o código." else "Abra o link e entre com a sua conta.",
                style = AuraType.Body,
            )
            if (step.code != null) {
                Text(step.code, style = AuraType.Mono.copy(fontSize = 24.sp, lineHeight = 32.sp), modifier = Modifier.padding(vertical = 4.dp))
                AuraButtonSecondary(if (copied) "Código copiado" else "Copiar código", onCopy, Modifier.fillMaxWidth())
            }
            AuraButtonPrimary("Abrir link", onOpen, Modifier.fillMaxWidth(), enabled = step.url != null, large = true)
            Text("Quando terminar, volte aqui. Eu aviso sozinho.", style = AuraType.SecondarySmall)
            AuraButtonText("Cancelar", onCancel, Modifier.fillMaxWidth())
        }
    }
}
