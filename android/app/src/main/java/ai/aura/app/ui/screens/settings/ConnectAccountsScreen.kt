package ai.aura.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.components.AuraButtonText
import ai.aura.app.ui.components.AuraCard
import ai.aura.app.ui.components.StatusChip
import ai.aura.app.ui.components.StatusKind
import ai.aura.app.ui.engine.AuthStartResult
import ai.aura.app.ui.engine.AuthState
import ai.aura.app.ui.engine.AuthStatus
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.FakeEngineGateway
import ai.aura.app.ui.engine.JobRequest
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraSpacing
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Nome para a tela de cada provedor (ids do contrato em AUTH_PROVIDER_IDS). */
private fun providerLabel(id: String): String = when (id) {
    "claude" -> "Claude"
    "gemini" -> "Gemini"
    "agy" -> "Antigravity"
    "codex" -> "Codex"
    "github" -> "GitHub"
    "gdrive" -> "Google Drive"
    else -> id
}

/** Passo ativo: link e código de uso único vindos de auth.prompt. Nunca há token aqui. */
private data class AuthStep(val provider: String, val url: String?, val code: String?)

private const val AUTH_WAIT_MILLIS = 5 * 60_000L
private const val AUTH_POLL_MILLIS = 2_000L

/**
 * Conectar contas: 1 toque por conta, sem terminal e sem chave de API. O link abre no navegador
 * por onOpenUrl (injetado: aqui não há Intent). Quem hospeda decide como abrir e fechar.
 */
@Composable
fun ConnectAccountsScreen(
    gateway: EngineGateway,
    onClose: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var statuses by remember { mutableStateOf<List<AuthStatus>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableStateOf<AuthStep?>(null) }
    var notice by remember { mutableStateOf<SettingsNotice?>(null) }
    var copied by remember { mutableStateOf(false) }

    suspend fun refresh() {
        statuses = runCatching { gateway.authStatus() }.getOrDefault(emptyList())
        loaded = true
    }

    fun finish(ok: Boolean, text: String) {
        step = null
        starting = null
        copied = false
        notice = SettingsNotice(ok, text)
    }

    LaunchedEffect(gateway) { refresh() }

    LaunchedEffect(gateway) {
        try { gateway.events.collect { e ->
            val p = e.payload["provider"] ?: return@collect
            when (e.type) {
                "auth.prompt" -> if (p == starting || p == step?.provider) {
                    step = AuthStep(p, e.payload["url"], e.payload["code"])
                    copied = false
                }
                "auth.done" -> if (p == starting || p == step?.provider) {
                    refresh()
                    finish(true, "${providerLabel(p)} conectado.")
                }
                "auth.failed" -> if (p == starting || p == step?.provider) {
                    val reason = e.payload["reason"] ?: "Não deu para conectar ${providerLabel(p)}."
                    val fix = e.payload["fix"] ?: "Tente de novo."
                    refresh()
                    finish(false, "$reason $fix")
                }
            }
        } } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Sem eventos: o plano B abaixo confere o estado.
        }
    }

    // Plano B para auth.done perdido: confere o estado de tempos em tempos, até 5 minutos.
    val activeProvider = starting
    LaunchedEffect(activeProvider) {
        if (activeProvider == null) return@LaunchedEffect
        var waited = 0L
        while (waited < AUTH_WAIT_MILLIS) {
            delay(AUTH_POLL_MILLIS)
            waited += AUTH_POLL_MILLIS
            refresh()
            if (statuses.firstOrNull { it.provider == activeProvider }?.state == AuthState.Connected) {
                finish(true, "${providerLabel(activeProvider)} conectado.")
                return@LaunchedEffect
            }
        }
        finish(false, "O tempo acabou. Toque em Conectar ${providerLabel(activeProvider)} e tente de novo.")
    }

    fun connect(provider: String) {
        notice = null
        starting = provider
        step = null
        scope.launch {
            val r = runCatching { gateway.startAuth(provider) }.getOrNull()
            if (r is AuthStartResult.Failed) finish(false, "${r.reason} ${r.fix}")
            else if (r == null) finish(false, "O motor não respondeu. $ENGINE_DOWN_HINT")
        }
    }

    fun install(provider: String) {
        notice = null
        scope.launch {
            val label = providerLabel(provider)
            val job = runCatching {
                gateway.submit("install_cli_$provider", JobRequest("system", "Instalar o programa de $label"))
            }.getOrNull()
            notice = if (job != null) SettingsNotice(true, "Pedido para instalar $label criado. Aprove em Atividade.")
            else SettingsNotice(false, ENGINE_DOWN_HINT)
        }
    }

    Column(modifier.fillMaxSize().background(AuraColors.Bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 8.dp, end = AuraSpacing.ScreenGutter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AuraButtonText("Voltar", onClose)
            Text(
                "Contas", style = AuraType.Section,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = AuraSpacing.ScreenGutter, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Use as assinaturas que você já paga. Não precisa de chave de API.", style = AuraType.Secondary)
            val n = notice
            if (n != null) {
                Text(
                    n.text,
                    style = AuraType.SecondarySmall.copy(color = if (n.ok) AuraColors.Ready.fg else AuraColors.Risk.fg),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            val s = step
            if (s != null) {
                PromptCard(
                    step = s, copied = copied,
                    onCopy = { s.code?.let { clipboard.setText(AnnotatedString(it)); copied = true } },
                    onOpen = { s.url?.let(onOpenUrl) },
                    onCancel = { finish(false, "Conexão cancelada.") },
                )
            } else if (starting != null) {
                Text("Preparando o login de ${providerLabel(starting!!)}…", style = AuraType.Secondary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (!loaded) {
                Text("Carregando contas…", style = AuraType.Secondary)
            } else if (statuses.isEmpty()) {
                Text("Não consegui ver suas contas. $ENGINE_DOWN_HINT", style = AuraType.Secondary)
                AuraButtonSecondary("Tentar de novo", { scope.launch { refresh() } })
            } else {
                SettingsGroup {
                    statuses.forEachIndexed { i, st ->
                        if (i > 0) RowDivider()
                        AccountRow(
                            st = st, busy = starting != null,
                            onConnect = { connect(st.provider) }, onInstall = { install(st.provider) },
                        )
                    }
                }
            }
            Text(
                "Chaves de API (ANTHROPIC_API_KEY, GEMINI_API_KEY) são opcionais e ficam em Detalhes.",
                style = AuraType.SecondarySmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
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

/** Passo único: o botão principal da tela é Abrir link. */
@Composable
private fun PromptCard(step: AuthStep, copied: Boolean, onCopy: () -> Unit, onOpen: () -> Unit, onCancel: () -> Unit) {
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

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, heightDp = 900)
@Composable
private fun ConnectAccountsPreview() {
    AuraTheme { ConnectAccountsScreen(FakeEngineGateway(), onClose = {}, onOpenUrl = {}) }
}
