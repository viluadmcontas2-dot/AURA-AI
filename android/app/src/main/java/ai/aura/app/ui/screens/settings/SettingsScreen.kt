package ai.aura.app.ui.screens.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.components.AuraButtonText
import ai.aura.app.ui.components.AuraCard
import ai.aura.app.ui.components.EmptyState
import ai.aura.app.ui.components.auraFocusRing
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraSpacing
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType

/**
 * Ajustes: autorização, sem limites, gastos, modelos e histórico da política.
 * O app não impõe limite: tudo é preferência do dono, lida e gravada no motor (GET/PUT /v1/policy
 * e /v1/models). Não faz parte da navegação do AuraApp; quem hospeda decide como abrir e fechar.
 */
@Composable
fun SettingsScreen(
    gateway: EngineGateway,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenUrl: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val state = remember(gateway) { SettingsState(gateway, scope) }
    LaunchedEffect(state) { state.reload() }
    LaunchedEffect(gateway) {
        try { gateway.events.collect { state.onEvent(it) } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { /* sem eventos; o botão Tentar de novo recarrega */ }
    }
    var showAccounts by rememberSaveable { mutableStateOf(false) }
    // Voltar em Contas volta aos Ajustes (não fecha os Ajustes inteiros).
    BackHandler(enabled = showAccounts) { showAccounts = false }
    if (showAccounts) {
        ConnectAccountsScreen(gateway, onClose = { showAccounts = false }, onOpenUrl = onOpenUrl, modifier = modifier)
        return
    }

    Column(modifier.fillMaxSize().background(AuraColors.Bg).statusBarsPadding()) {
        SettingsHeader(onClose = onClose, onStopAll = state::stopAll)
        when (state.load) {
            SettingsLoad.Loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Carregando ajustes…", style = AuraType.Secondary)
            }
            SettingsLoad.Unavailable -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState("Motor indisponível", state.unavailableHint, "Tentar de novo", state::reload, glyph = "!")
            }
            SettingsLoad.Ready -> SettingsBody(state, { showAccounts = true }, Modifier.weight(1f))
        }
        if (state.load == SettingsLoad.Ready) SaveBar(state)
        // "Parar tudo" responde em qualquer estado: a barra de salvar só existe com o motor no ar.
        if (state.load != SettingsLoad.Ready) state.notice?.let { n ->
            Text(
                n.text,
                style = AuraType.SecondarySmall.copy(color = if (n.ok) AuraColors.Ready.fg else AuraColors.Risk.fg),
                modifier = Modifier.padding(AuraSpacing.ScreenGutter).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }

    if (state.confirmAllowAll) {
        AllowAllDialog(
            rootDetail = state.rootCapability.let { rc ->
                when {
                    rc == null -> "O motor não informou se há root."
                    rc.available -> null
                    else -> rc.detail ?: "Este aparelho não tem root."
                }
            },
            onConfirm = state::confirmAllowAllAndSave,
            onCancel = { state.confirmAllowAll = false },
        )
    }
}

@Composable
private fun SettingsBody(state: SettingsState, onOpenAccounts: () -> Unit, modifier: Modifier) {
    val policy = state.policy ?: return
    val models = state.models ?: return
    val key = state.savedPolicy?.version to state.savedModels?.version
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = AuraSpacing.ScreenGutter, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsGroup {
            SettingRow(
                "Contas", subtitle = "Claude, Gemini, Codex, GitHub e Drive, sem chave de API.",
                trailing = { AuraButtonSecondary("Conectar contas", onOpenAccounts) },
            )
        }
        AuthorizationSection(policy, state::editPolicy)
        NoLimitsSection(
            p = policy,
            rootAvailable = state.rootCapability?.available,
            rootDetail = state.rootCapability?.detail,
            onAllowAll = { on -> if (on) state.confirmAllowAll = true else state.disallowAll() },
            onEdit = state::editPolicy,
        )
        SpendSection(policy, state.spend, key, state::editPolicy)
        ModelsSection(models, key, state.lastModelUse, state::editModels)
        HistorySection(state.history, state.savedPolicy?.version, state::revertTo)
        Text(
            "Tudo que roda sozinho fica em Atividade.",
            style = AuraType.SecondarySmall,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun SettingsHeader(onClose: () -> Unit, onStopAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 8.dp, end = AuraSpacing.ScreenGutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val source = remember { MutableInteractionSource() }
        Box(
            Modifier.size(AuraSpacing.TouchTarget).clip(AuraShapes.Circle)
                .auraFocusRing(source, AuraShapes.Circle)
                .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClose)
                .semantics { contentDescription = "Voltar" },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(24.dp)) {
                val p = Path().apply {
                    moveTo(size.width * 0.625f, size.height * 0.75f)
                    lineTo(size.width * 0.375f, size.height * 0.5f)
                    lineTo(size.width * 0.625f, size.height * 0.25f)
                }
                drawPath(p, AuraColors.Ink, style = Stroke(1.75.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Text("Ajustes", style = AuraType.Section.copy(fontSize = 17.sp, lineHeight = 22.sp),
            modifier = Modifier.weight(1f).semantics { heading() })
        StopAllButton(onStopAll)
    }
}

/** Sempre visível. Vermelho contornado: é parada, não o botão principal da tela. */
@Composable
private fun StopAllButton(onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .heightIn(min = AuraSpacing.TouchTarget)
            .clip(AuraShapes.Pill)
            .background(AuraColors.Risk.bg)
            .border(1.dp, AuraColors.RiskSolid, AuraShapes.Pill)
            .auraFocusRing(source, AuraShapes.Pill)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Parar tudo que está rodando" }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Parar tudo", style = AuraType.Button.copy(color = AuraColors.Risk.fg))
    }
}

/** Único botão principal da tela. Mostra a versão devolvida pelo motor, nunca presume salvo. */
@Composable
private fun SaveBar(state: SettingsState) {
    Column(
        Modifier.fillMaxWidth().background(AuraColors.Bg).imePadding().navigationBarsPadding()
            .padding(horizontal = AuraSpacing.ScreenGutter, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val n = state.notice
        val line = when {
            n != null -> n.text
            state.dirty -> "Alterações ainda não salvas."
            else -> null
        }
        if (line != null) {
            Text(
                line,
                style = AuraType.SecondarySmall.copy(
                    color = when {
                        n == null -> AuraColors.Ink2
                        n.ok -> AuraColors.Ready.fg
                        else -> AuraColors.Risk.fg
                    },
                ),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        AuraButtonPrimary(
            "Salvar alterações", onClick = state::save, modifier = Modifier.fillMaxWidth(),
            enabled = state.dirty && !state.saving, large = true,
        )
    }
}

/** Uma única confirmação, mostrando o que passa a valer. */
@Composable
private fun AllowAllDialog(rootDetail: String?, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel) {
        AuraCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Permitir tudo?", style = AuraType.Section, modifier = Modifier.semantics { heading() })
                Text("Passa a valer agora:", style = AuraType.Secondary)
                listOf(
                    "Modo Total: nada pergunta antes.",
                    "Ler, escrever, gastar e ação crítica: automático.",
                    "Terminal, adb, arquivos, GitHub e root: automático.",
                    "Qualquer pasta que o Termux alcance.",
                    "Seus limites de gasto continuam como estão.",
                ).forEach { Text("•  $it", style = AuraType.Body) }
                if (rootDetail != null) {
                    Text("Root: só funciona em aparelho com root. $rootDetail", style = AuraType.SecondarySmall.copy(color = AuraColors.NeedsYou.fg))
                } else {
                    Text("Root: só funciona em aparelho com root; este tem.", style = AuraType.SecondarySmall)
                }
                Text("Você pode voltar atrás quando quiser; fica no histórico.", style = AuraType.SecondarySmall)
                AuraButtonPrimary("Permitir tudo", onConfirm, Modifier.fillMaxWidth())
                AuraButtonText("Cancelar", onCancel, Modifier.fillMaxWidth())
            }
        }
    }
}
