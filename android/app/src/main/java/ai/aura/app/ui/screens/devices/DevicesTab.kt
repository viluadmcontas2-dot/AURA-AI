package ai.aura.app.ui.screens.devices

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.components.AuraCard
import ai.aura.app.ui.components.EmptyState
import ai.aura.app.ui.components.StatusChip
import ai.aura.app.ui.engine.Capability
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.JobRequest
import ai.aura.app.ui.screens.activity.IncidentBanner
import ai.aura.app.ui.screens.activity.IncidentLevel
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraSpacing
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val ROUTE_LIST = "list"
private const val ROUTE_CONNECTIONS = "connections"
private const val ROUTE_DETAIL = "detail:"

private data class Notice(val level: IncidentLevel, val title: String, val body: String)

/**
 * Aba Dispositivos: lista -> detalhe -> conexões, por estado local (sem navigation-compose).
 * Estados vêm de [EngineGateway.capabilities]; ações viram pedidos ao engine (que decide risco e
 * aprovação na aba Atividade). [onSignIn] é o gancho do login pelo navegador (integração fora da UI);
 * sem ele, a UI diz que o login ainda não está disponível em vez de fingir.
 */
@Composable
fun DevicesTab(
    gateway: EngineGateway,
    modifier: Modifier = Modifier,
    onSignIn: ((DeviceEntry) -> Unit)? = null,
) {
    var route by rememberSaveable { mutableStateOf(ROUTE_LIST) }
    var caps by remember { mutableStateOf<List<Capability>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(gateway, reload) {
        try {
            caps = gateway.capabilities()
            loadFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadFailed = true
        }
    }

    BackHandler(enabled = route != ROUTE_LIST) { route = ROUTE_LIST; notice = null }

    fun perform(entry: DeviceEntry, action: DeviceActionKind) {
        if (action == DeviceActionKind.SignIn) {
            if (onSignIn != null) onSignIn(entry) else notice = Notice(
                IncidentLevel.Info, "Login pelo navegador indisponível",
                "Esta versão ainda não abre o login. Nada foi alterado.",
            )
            return
        }
        if (busy) return
        busy = true
        scope.launch {
            try {
                gateway.submit(
                    requestId = "ui-${System.nanoTime()}",
                    request = JobRequest(action.jobKind(entry.group), action.prompt(entry.label)),
                )
                notice = Notice(IncidentLevel.Info, "Pedido enviado", "Acompanhe e aprove na aba Atividade.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = Notice(IncidentLevel.Failed, "Não consegui enviar o pedido", "Confira se o Termux está ativo e toque de novo.")
            } finally {
                busy = false
            }
        }
    }

    val current = caps
    val detailEntry = if (current != null && route.startsWith(ROUTE_DETAIL)) {
        val id = route.removePrefix(ROUTE_DETAIL)
        (listOf(current.phoneEntry()) + current.deviceEntries()).firstOrNull { it.id == id }
    } else null
    Box(modifier.fillMaxSize().background(AuraColors.Bg)) {
        when {
            current == null && loadFailed -> LoadError(onRetry = { reload++ })
            current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Procurando aparelhos…", style = AuraType.Secondary)
            }
            route == ROUTE_CONNECTIONS -> ConnectionsContent(
                caps = current, notice = notice?.let { it.level to (it.title to it.body) },
                onBack = { route = ROUTE_LIST; notice = null }, onAction = ::perform,
            )
            detailEntry != null -> DeviceDetailContent(
                entry = detailEntry, notice = notice?.let { it.level to (it.title to it.body) },
                onBack = { route = ROUTE_LIST; notice = null }, onAction = { perform(detailEntry, it) },
            )
            else -> DevicesListContent(
                phone = current.phoneEntry(), devices = current.deviceEntries(),
                notice = notice?.let { it.level to (it.title to it.body) },
                onOpen = { route = ROUTE_DETAIL + it.id; notice = null },
                onAction = ::perform,
                onAdd = { route = ROUTE_CONNECTIONS; notice = null },
            )
        }
    }
}

@Composable
private fun LoadError(onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(AuraSpacing.ScreenGutter), verticalArrangement = Arrangement.Center) {
        IncidentBanner(
            IncidentLevel.Failed, "Não consegui falar com o AURA neste celular",
            "Abra o Termux e confira se o AURA está ativo. Depois tente de novo.",
            actionText = "Tentar de novo", onAction = onRetry,
        )
    }
}

private fun tintFor(group: DeviceGroup): Color = when (group) {
    DeviceGroup.ThisPhone -> AuraColors.CategoryAndroid
    DeviceGroup.Device -> AuraColors.CategoryEngineering
    DeviceGroup.Tool -> AuraColors.CategoryLilac
}

/** Lista: Este celular, aparelhos ADB e atalhos. O botão "+" é a ação principal da tela. */
@Composable
fun DevicesListContent(
    phone: DeviceEntry,
    devices: List<DeviceEntry>,
    notice: Pair<IncidentLevel, Pair<String, String>>?,
    onOpen: (DeviceEntry) -> Unit,
    onAction: (DeviceEntry, DeviceActionKind) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        TabHeader("Dispositivos", trailing = { AddButton(onAdd) })
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AuraSpacing.ScreenGutter, vertical = AuraSpacing.S2),
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.S3),
        ) {
            if (notice != null) IncidentBanner(notice.first, notice.second.first, notice.second.second)
            DeviceCard(phone, onOpen = { onOpen(phone) }, onAction = { onAction(phone, it) })
            devices.forEach { d -> DeviceCard(d, onOpen = { onOpen(d) }, onAction = { onAction(d, it) }) }
            if (devices.isEmpty()) {
                EmptyState(
                    title = "Nenhum aparelho ADB",
                    body = "Pareie por Wi-Fi em 2 passos para controlar outro Android daqui.",
                    actionText = "Adicionar dispositivo",
                    onAction = onAdd,
                )
            }
            Spacer(Modifier.height(AuraSpacing.S4))
        }
    }
}

@Composable
private fun AddButton(onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(AuraSpacing.TouchTarget)
            .clip(AuraShapes.Circle)
            .background(AuraColors.Ink)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Adicionar dispositivo" },
        contentAlignment = Alignment.Center,
    ) { Text("+", style = AuraType.TabTitle.copy(color = Color.White)) }
}

@Composable
private fun DeviceCard(
    entry: DeviceEntry,
    onOpen: () -> Unit,
    onAction: (DeviceActionKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    AuraCard(modifier.fillMaxWidth(), onClick = onOpen, contentDescription = "${entry.label}, ${entry.chipText}") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlyphTile(entry.label.take(1).uppercase(), tintFor(entry.group))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(entry.label, style = AuraType.BodyStrong.copy(fontSize = 17.sp))
                StatusChip(entry.state.kind, entry.chipText)
                entry.hint?.let { Text(it, style = AuraType.SecondarySmall) }
            }
            Chevron()
        }
        val repair = entry.state.repairAction(entry.group)
        if (repair != null) {
            Spacer(Modifier.height(8.dp))
            AuraButtonSecondary(repair.label, { onAction(repair) }, Modifier.fillMaxWidth())
        }
    }
}

/** Detalhe: estado, UM botão principal quando há reparo, ações só se Conectado. */
@Composable
fun DeviceDetailContent(
    entry: DeviceEntry,
    notice: Pair<IncidentLevel, Pair<String, String>>?,
    onBack: () -> Unit,
    onAction: (DeviceActionKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    val subtitle = when (entry.group) {
        DeviceGroup.ThisPhone -> "Este celular"
        DeviceGroup.Device -> "Android"
        DeviceGroup.Tool -> "Ferramenta"
    }
    Column(modifier.fillMaxSize()) {
        BackHeader(entry.label, subtitle, onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AuraSpacing.ScreenGutter, vertical = AuraSpacing.S3),
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.S4),
        ) {
            if (notice != null) IncidentBanner(notice.first, notice.second.first, notice.second.second)
            StatusChip(entry.state.kind, entry.chipText)
            entry.hint?.let { Text(it, style = AuraType.Secondary) }
            val repair = entry.state.repairAction(entry.group)
            if (repair != null) {
                AuraButtonPrimary(repair.label, { onAction(repair) }, Modifier.fillMaxWidth(), large = true)
            } else {
                val actions = entry.group.connectedActions()
                if (actions.isEmpty()) {
                    Text("Nada a fazer aqui por enquanto.", style = AuraType.Secondary)
                } else {
                    CaptionTitle("Ações")
                    actions.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { a ->
                                AuraButtonSecondary(a.label, { onAction(a) }, Modifier.weight(1f))
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    Text(
                        "Ações que alteram o aparelho pedem a sua aprovação na aba Atividade.",
                        style = AuraType.SecondarySmall,
                    )
                }
            }
        }
    }
}

private val brainRows = listOf(
    "Claude Code" to "claude", "Gemini CLI" to "gemini",
    "Antigravity CLI (agy)" to "agy", "Codex (ChatGPT)" to "codex",
)
private val toolRows = listOf(
    "Termux" to "termux", "Aparelhos ADB" to "adb", "GitHub" to "github",
    "Google: Gemini, Drive, Agenda" to "google",
)

private fun List<Capability>.rowFor(label: String, token: String): DeviceEntry {
    val c = findByToken(token)
    return if (c == null) {
        DeviceEntry(token, label, DeviceGroup.Tool, ConnectionState.NotInstalled, "Não encontrado neste aparelho.")
    } else {
        DeviceEntry(c.id, label, DeviceGroup.Tool, classify(c), c.detail)
    }
}

/** Conexões: cérebros e ferramentas, só com o que o engine reporta (ausente = Não instalado). */
@Composable
fun ConnectionsContent(
    caps: List<Capability>,
    notice: Pair<IncidentLevel, Pair<String, String>>?,
    onBack: () -> Unit,
    onAction: (DeviceEntry, DeviceActionKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        BackHeader("Conexões", null, onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AuraSpacing.ScreenGutter, vertical = AuraSpacing.S2),
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.S3),
        ) {
            if (notice != null) IncidentBanner(notice.first, notice.second.first, notice.second.second)
            SectionTitle("Cérebros")
            brainRows.forEach { (label, token) -> ConnectionRow(caps.rowFor(label, token), onAction) }
            SectionTitle("Ferramentas", Modifier.padding(top = AuraSpacing.S2))
            toolRows.forEach { (label, token) -> ConnectionRow(caps.rowFor(label, token), onAction) }
            AuraCard(Modifier.fillMaxWidth()) {
                Text("Escolha automática do cérebro", style = AuraType.BodyStrong)
                Text("Comando simples não gasta IA; código difícil vai para o Claude.", style = AuraType.SecondarySmall)
            }
            AuraCard(Modifier.fillMaxWidth()) {
                Text("Trava de custo", style = AuraType.BodyStrong)
                Text("Nunca usar API paga sem a sua aprovação.", style = AuraType.SecondarySmall)
            }
            Spacer(Modifier.height(AuraSpacing.S4))
        }
    }
}

@Composable
private fun ConnectionRow(entry: DeviceEntry, onAction: (DeviceEntry, DeviceActionKind) -> Unit) {
    AuraCard(Modifier.fillMaxWidth(), contentDescription = "${entry.label}, ${entry.chipText}") {
        Text(entry.label, style = AuraType.BodyStrong)
        Spacer(Modifier.height(4.dp))
        StatusChip(entry.state.kind, entry.chipText)
        entry.hint?.let { Text(it, style = AuraType.SecondarySmall, modifier = Modifier.padding(top = 4.dp)) }
        val repair = entry.state.repairAction(entry.group)
        if (repair != null) {
            Spacer(Modifier.height(8.dp))
            AuraButtonSecondary(repair.label, { onAction(entry, repair) })
        }
    }
}

private fun sampleCaps() = listOf(
    Capability("termux", "Termux", true, "Termux ativo"),
    Capability("adb", "Multimídia do carro", true, "ADB sem fio"),
    Capability("device_tv", "TV da sala", false, "Sem conexão"),
    Capability("github", "GitHub", false, "Precisa de login"),
)

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, heightDp = 800)
@Composable
private fun DevicesListPreview() {
    val c = sampleCaps()
    AuraTheme { DevicesListContent(c.phoneEntry(), c.deviceEntries(), null, {}, { _, _ -> }, {}) }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, heightDp = 800)
@Composable
private fun DevicesEmptyPreview() {
    AuraTheme { DevicesListContent(emptyList<Capability>().phoneEntry(), emptyList(), null, {}, { _, _ -> }, {}) }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, heightDp = 800)
@Composable
private fun DeviceDetailPreview() {
    AuraTheme { DeviceDetailContent(sampleCaps().deviceEntries().first(), null, {}, {}) }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, heightDp = 800)
@Composable
private fun ConnectionsPreview() {
    AuraTheme { ConnectionsContent(sampleCaps(), null, {}, { _, _ -> }) }
}
