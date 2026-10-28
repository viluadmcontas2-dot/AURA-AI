package ai.aura.app.ui.screens.sessions

import ai.aura.app.offline.LinkState
import ai.aura.app.offline.LocalStore
import ai.aura.app.offline.OfflineReason
import ai.aura.app.ui.components.AuraButtonDestructive
import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.components.AuraButtonText
import ai.aura.app.ui.components.AuraCard
import ai.aura.app.ui.components.EmptyState
import ai.aura.app.ui.components.StatusChip
import ai.aura.app.ui.components.StatusKind
import ai.aura.app.ui.components.auraFocusRing
import ai.aura.app.ui.components.engineActionLabel
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.ProcessInfo
import ai.aura.app.ui.engine.SessionInfo
import ai.aura.app.ui.screens.devices.BackHeader
import ai.aura.app.ui.screens.devices.SectionTitle
import ai.aura.app.ui.screens.rememberAuraStore
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraSpacing
import ai.aura.app.ui.theme.AuraType
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers

/** Eventos do motor depois dos quais a lista pode ter mudado (uma resposta terminou, o motor voltou). */
private val REFRESH_EVENTS = setOf("chat.done", "chat.error", "connection.restored")

/**
 * Sessões: as conversas do Claude Code no Termux (as do AURA e as do terminal) e o que está rodando no
 * celular agora. Tocar numa conversa abre o chat nela; renomear, esconder e parar ficam num toque a mais.
 * Abre sempre: sem motor mostra o aviso com "Iniciar o motor" e as conversas que já tinha visto.
 */
@Composable
fun SessionsScreen(
    gateway: EngineGateway,
    link: LinkState,
    onEngineAction: (OfflineReason) -> Unit,
    onOpenSession: (String) -> Unit,
    onNewChat: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    store: LocalStore = rememberAuraStore(),
    nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    // Estado de tela só se escreve na thread principal, mesmo quando uma etapa passou por Dispatchers.IO.
    val scope = rememberCoroutineScope { Dispatchers.Main.immediate }
    val c = remember(gateway, store) { SessionsController(gateway, scope, store) }
    // Lê ao abrir e de novo quando o motor liga ou desliga (o aviso global muda).
    LaunchedEffect(c, link) { c.load() }
    LaunchedEffect(c) {
        try {
            gateway.events.collect { if (it.type in REFRESH_EVENTS) c.reload() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Sem eventos: o botão Tentar de novo e a próxima abertura leem de novo.
        }
    }
    val now = nowMillis()

    Box(modifier.fillMaxSize().background(AuraColors.Bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            BackHeader("Sessões", "Conversas do Claude Code neste celular", onBack)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = AuraSpacing.ScreenGutter, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                c.notice?.let { n ->
                    Text(
                        n.text,
                        style = AuraType.SecondarySmall.copy(color = if (n.ok) AuraColors.Ready.fg else AuraColors.Risk.fg),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (c.offline) {
                    OfflineNotice(link, c.problem, hasSaved = c.sessions.isNotEmpty(), onEngineAction, onRetry = c::reload)
                }
                SectionTitle("Conversas")
                when {
                    c.loading && c.sessions.isEmpty() -> Text("Carregando sessões…", style = AuraType.Secondary)
                    c.sessions.isEmpty() && !c.offline -> EmptyState(
                        title = "Nenhuma conversa ainda. Escreva algo para começar.",
                        body = "As conversas do AURA e do terminal do Claude Code aparecem aqui.",
                        actionText = "Escrever no chat",
                        onAction = onNewChat,
                        glyph = "…",
                    )
                    else -> c.sessions.forEach { s ->
                        SessionCard(
                            session = s, now = now, panel = c.panel?.takeIf { it.id == s.id }, controller = c,
                            onOpen = { onOpenSession(s.id) },
                        )
                    }
                }
                if (!c.offline) {
                    SectionTitle("No celular agora", Modifier.padding(top = 8.dp))
                    if (c.processes.isEmpty()) {
                        Text("Nada rodando agora.", style = AuraType.Secondary)
                    } else {
                        c.processes.forEach { p -> ProcessRow(p, c.sessions, now, onStop = { c.stopProcess(p.pid, processLabel(p.kind)) }) }
                    }
                }
            }
        }
    }
}

/** Motor desligado: o que houve, o que está na tela e UMA ação (a mesma palavra do aviso global). */
@Composable
private fun OfflineNotice(
    link: LinkState,
    problem: String?,
    hasSaved: Boolean,
    onEngineAction: (OfflineReason) -> Unit,
    onRetry: () -> Unit,
) {
    // Só "no ar" é certeza de que o motor responde; qualquer outro estado oferece ligar o motor.
    val reason = (link as? LinkState.Offline)?.reason ?: if (link == LinkState.Online) null else OfflineReason.EngineStopped
    AuraCard(Modifier.fillMaxWidth(), tint = AuraColors.NeedsYou.bg) {
        Text(
            if (reason != null) "O motor está desligado." else "Não consegui ler as conversas agora.",
            style = AuraType.BodyStrong.copy(color = AuraColors.NeedsYou.fg),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            when {
                reason != null && hasSaved -> "Estas são as conversas que você já viu. Ligue o motor para ver as novas."
                reason != null -> "Ligue o motor para ver as suas conversas."
                problem != null -> problem
                else -> "Tente de novo em instantes."
            },
            style = AuraType.SecondarySmall.copy(color = AuraColors.NeedsYou.fg),
        )
        if (reason != null) {
            AuraButtonSecondary(engineActionLabel(reason), { onEngineAction(reason) }, Modifier.padding(top = 8.dp))
        } else {
            AuraButtonSecondary("Tentar de novo", onRetry, Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun SessionCard(
    session: SessionInfo,
    now: Long,
    panel: SessionPanel?,
    controller: SessionsController,
    onOpen: () -> Unit,
) {
    AuraCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val source = remember { MutableInteractionSource() }
            Column(
                Modifier.weight(1f).heightIn(min = AuraSpacing.TouchTarget)
                    .clip(AuraShapes.Card)
                    .auraFocusRing(source, AuraShapes.Card)
                    .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onOpen),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(session.title, style = AuraType.BodyStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = listOf(session.project, agoLabel(session.updatedAtMillis, now)).filter { it.isNotEmpty() }.joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = AuraType.SecondarySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (session.fromTerminal) StatusChip(StatusKind.Neutral, "No terminal") else StatusChip(StatusKind.Info, "No AURA")
                    if (session.running) StatusChip(StatusKind.Ready, "Respondendo agora")
                }
            }
            MoreButton(session.title, onClick = { controller.toggleActions(session.id) })
        }
        when (panel) {
            is SessionPanel.Actions -> ActionsRow(session, controller)
            is SessionPanel.Rename -> RenamePanel(session, controller)
            is SessionPanel.ConfirmHide -> HidePanel(session, controller)
            null -> Unit
        }
    }
}

/** Três pontos, 44 dp: abre as ações da conversa. */
@Composable
private fun MoreButton(title: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier.size(AuraSpacing.TouchTarget).clip(AuraShapes.Circle)
            .auraFocusRing(source, AuraShapes.Circle)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Ações de $title" },
        contentAlignment = Alignment.Center,
    ) { Text("⋯", style = AuraType.TabTitle, modifier = Modifier.clearAndSetSemantics { }) }
}

@Composable
private fun ActionsRow(session: SessionInfo, c: SessionsController) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        AuraButtonText("Renomear", { c.startRename(session.id) })
        AuraButtonText("Esconder", { c.askHide(session.id) })
        if (session.running) AuraButtonText("Parar resposta", { c.stop(session.id) }, enabled = !c.busy)
    }
}

@Composable
private fun RenamePanel(session: SessionInfo, c: SessionsController) {
    var name by remember(session.id) { mutableStateOf(session.title) }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name, onValueChange = { name = it }, singleLine = true,
            label = { Text("Novo nome") }, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AuraButtonSecondary("Salvar nome", { c.rename(session.id, name) }, enabled = !c.busy)
            AuraButtonText("Cancelar", c::closePanel)
        }
    }
}

@Composable
private fun HidePanel(session: SessionInfo, c: SessionsController) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (session.fromTerminal) {
                "Isso só esconde a conversa no AURA. No terminal ela continua como está."
            } else {
                "O AURA deixa de mostrar esta conversa e esquece as mensagens que guardou. O histórico do Claude Code no Termux não é apagado."
            },
            style = AuraType.SecondarySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AuraButtonDestructive("Esconder conversa", { c.hide(session) }, enabled = !c.busy)
            AuraButtonText("Cancelar", c::closePanel)
        }
    }
}

@Composable
private fun ProcessRow(p: ProcessInfo, sessions: List<SessionInfo>, now: Long, onStop: () -> Unit) {
    val label = processLabel(p.kind)
    val chat = p.sessionId?.let { id -> sessions.firstOrNull { it.id == id }?.title }
    val detail = listOfNotNull(
        if (p.kind == "engine") "Fica ligado para o AURA funcionar." else null,
        chat?.let { "Conversa: $it" },
        p.startedAtMillis?.let { agoLabel(it, now) }?.takeIf { it.isNotEmpty() },
    ).joinToString(" · ")
    AuraCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = AuraType.BodyStrong)
                if (detail.isNotEmpty()) Text(detail, style = AuraType.SecondarySmall)
            }
            if (canStopProcess(p.kind)) AuraButtonSecondary("Parar $label", onStop)
        }
    }
}
