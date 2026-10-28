package ai.aura.app.ui.screens.home

import ai.aura.app.offline.LinkState
import ai.aura.app.offline.OfflineReason
import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.components.AuraButtonText
import ai.aura.app.ui.components.AuraCard
import ai.aura.app.ui.components.AuraComposerField
import ai.aura.app.ui.components.StatusChip
import ai.aura.app.ui.components.StatusKind
import ai.aura.app.ui.engine.Capability
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobStatus
import ai.aura.app.ui.screens.chat.ChatEntry
import ai.aura.app.ui.screens.chat.ImprovePromptBar
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException

/** Estado do que a aba Início mostra, sempre derivado do engine. */
internal sealed interface HomeData {
    object Loading : HomeData
    class Ready(val jobs: List<Job>, val capabilities: List<Capability>) : HomeData
    /** Engine/Termux não respondeu. */
    object Offline : HomeData
}

@Composable
private fun rememberHomeData(gateway: EngineGateway, attempt: Int): State<HomeData> {
    val state = remember(gateway, attempt) { mutableStateOf<HomeData>(HomeData.Loading) }
    LaunchedEffect(gateway, attempt) {
        suspend fun load() {
            state.value = try {
                HomeData.Ready(gateway.jobs(), gateway.capabilities())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HomeData.Offline
            }
        }
        load()
        try {
            gateway.events.collect { load() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.value = HomeData.Offline
        }
    }
    return state
}

/**
 * Aba Início (Main.dc.html): saudação, orbe, sugestão baseada em tarefas reais, dois atalhos e
 * composer ancorado. [onOpenChat] abre o fluxo de chat (estado mantido por HomeScreen).
 */
@Composable
fun HomeContent(
    gateway: EngineGateway,
    onOpenChat: (ChatEntry) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    initialDraft: String = "",
    onDraftChange: (String) -> Unit = {},
    link: LinkState = LinkState.Unknown,
    onEngineAction: ((OfflineReason) -> Unit)? = null,
    onOpenVoice: (() -> Unit)? = null,
    onOpenReform: (() -> Unit)? = null,
    /** Nulo = os cartões de sugestão abrem o chat, como antes. */
    onOpenActivity: (() -> Unit)? = null,
    /** Abre a tela Sessões (cabeçalho). Nulo = sem o botão. */
    onOpenSessions: (() -> Unit)? = null,
    /** "Abrir Conexões" do aviso de Melhorar pedido (falta a chave do Gemini). */
    onOpenConnections: (() -> Unit)? = null,
) {
    var attempt by remember { mutableIntStateOf(0) }
    val data by rememberHomeData(gateway, attempt)
    var draft by rememberSaveable { mutableStateOf(initialDraft) }
    HomeView(
        data = data,
        draft = draft,
        onDraftChange = { draft = it; onDraftChange(it) },
        onSend = {
            val text = draft.trim()
            if (text.isNotEmpty()) {
                onOpenChat(ChatEntry(open = true, prompt = text))
                draft = ""
                onDraftChange("")
            }
        },
        onOpenChat = onOpenChat,
        onRetry = { attempt++ },
        modifier = modifier,
        onOpenSettings = onOpenSettings,
        link = link,
        onEngineAction = onEngineAction,
        onOpenVoice = onOpenVoice,
        onOpenReform = onOpenReform,
        onOpenActivity = onOpenActivity,
        onOpenSessions = onOpenSessions,
        aboveComposer = {
            ImprovePromptBar(gateway, draft, { draft = it; onDraftChange(it) }, onOpenConnections)
        },
    )
}

@Composable
internal fun HomeView(
    data: HomeData,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onOpenChat: (ChatEntry) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    link: LinkState = LinkState.Unknown,
    onEngineAction: ((OfflineReason) -> Unit)? = null,
    onOpenVoice: (() -> Unit)? = null,
    onOpenReform: (() -> Unit)? = null,
    onOpenActivity: (() -> Unit)? = null,
    onOpenSessions: (() -> Unit)? = null,
    /** Entre o conteúdo e o campo de mensagem (ex.: "Melhorar pedido"). */
    aboveComposer: @Composable () -> Unit = {},
) {
    // Sem motor, o aviso nasce do motivo do elo (ou de a consulta ter falhado) e traz UMA ação.
    val offlineReason = (link as? LinkState.Offline)?.reason
        ?: if (data == HomeData.Offline) OfflineReason.EngineStopped else null
    Column(modifier.fillMaxSize().background(AuraColors.Bg)) {
        HomeHeader(data, offlineReason != null, onOpenSettings, onOpenSessions)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
            ) {
                Column(Modifier.widthIn(max = 236.dp)) {
                    Text("Olá.", style = AuraType.Body.copy(fontSize = 17.sp, color = AuraColors.Ink2))
                    Text(
                        "O que vamos criar hoje?",
                        style = AuraType.Display,
                        modifier = Modifier.padding(top = 4.dp).semantics { heading() },
                    )
                    Text(
                        "Fale ou escreva do seu jeito. Eu cuido do resto.",
                        style = AuraType.Body.copy(color = AuraColors.Ink2, lineHeight = 23.sp),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
                when {
                    offlineReason != null -> OfflineCard(offlineReason, onEngineAction, onRetry)
                    data is HomeData.Ready -> SuggestionCard(data.jobs, onOpenChat, onOpenActivity)
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ShortcutCard(
                        title = "Criar aplicativo", subtitle = "Da ideia ao APK", tint = AuraColors.CategoryEngineering,
                        icon = ShortcutIcon.Plus, modifier = Modifier.weight(1f),
                        onClick = { onOpenChat(ChatEntry(open = true, draft = "Criar um aplicativo: ")) },
                    )
                    ShortcutCard(
                        title = "Controlar Android", subtitle = "TV, celular, multimídia", tint = AuraColors.CategoryAndroid,
                        icon = ShortcutIcon.Phone, modifier = Modifier.weight(1f),
                        onClick = { onOpenChat(ChatEntry(open = true, draft = "Controlar meu aparelho Android: ")) },
                    )
                }
                // Reformar apps abre sem motor (escolher o APK é local): nunca fica escondido atrás do chat.
                if (onOpenReform != null) {
                    Spacer(Modifier.height(12.dp))
                    ReformShortcut(onOpenReform)
                }
            }
            val voice = ai.aura.app.voice.LocalVoiceInput.current
            VoiceOrb(
                available = voice != null || onOpenVoice != null,
                onClick = {
                    if (onOpenVoice != null) onOpenVoice()
                    else if (voice != null) voice { text -> onOpenChat(ChatEntry(open = true, prompt = text)) }
                    else onOpenChat(ChatEntry(open = true))
                }, modifier = Modifier.align(Alignment.TopEnd).offset(x = 72.dp, y = (-6).dp),
            )
        }
        // O microfone é um jeito de digitar: o texto falado cai no campo e a pessoa confirma em enviar.
        val listen = ai.aura.app.voice.LocalVoiceInput.current
        val mic: (() -> Unit)? = if (listen != null) ({ listen { spoken -> onDraftChange((draft.trim() + " " + spoken.trim()).trim()) } }) else onOpenVoice
        aboveComposer()
        AuraComposerField(value = draft, onValueChange = onDraftChange, onSend = onSend, onMic = mic)
    }
}

@Composable
private fun HomeHeader(data: HomeData, offline: Boolean, onOpenSettings: () -> Unit, onOpenSessions: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AuraOrb(Modifier.size(24.dp))
            Text("aura", style = AuraType.Section.copy(fontSize = 22.sp, letterSpacing = (-0.03).sp, fontWeight = FontWeight.SemiBold))
        }
        val (kind, label) = when {
            offline -> StatusKind.NeedsYou to "Motor desligado"
            data is HomeData.Ready ->
                if (data.capabilities.any { it.id == "termux" && !it.available }) StatusKind.NeedsYou to "Termux inativo"
                else StatusKind.Ready to "Termux ativo"
            else -> StatusKind.Neutral to "Verificando…"
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusChip(kind, label)
            if (onOpenSessions != null) SessionsButton(onOpenSessions)
            SettingsButton(onOpenSettings)
        }
    }
}

/** Dois balões de conversa, 48 dp: abre as sessões do Claude Code. Desenhado em Canvas como a engrenagem. */
@Composable
private fun SessionsButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Abrir sessões" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val u = size.width / 24f
            val stroke = Stroke(width = 1.75f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
            drawRoundRect(AuraColors.Ink, Offset(3 * u, 4 * u), Size(14 * u, 10 * u), CornerRadius(3 * u), style = stroke)
            drawLine(AuraColors.Ink, Offset(7 * u, 14 * u), Offset(7 * u, 18 * u), 1.75f * u, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(AuraColors.Ink, Offset(7 * u, 18 * u), Offset(10 * u, 14 * u), 1.75f * u, androidx.compose.ui.graphics.StrokeCap.Round)
            drawRoundRect(AuraColors.Ink, Offset(10 * u, 9 * u), Size(11 * u, 8 * u), CornerRadius(3 * u), style = stroke)
        }
    }
}

/** Engrenagem 48 dp (alvo de toque), desenhada em Canvas como os ícones da barra inferior. */
@Composable
private fun SettingsButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Abrir ajustes" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val u = size.width / 24f
            val c = androidx.compose.ui.geometry.Offset(12 * u, 12 * u)
            val stroke = 1.75f * u
            drawCircle(AuraColors.Ink2, radius = 3.5f * u, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val dx = Math.cos(a).toFloat()
                val dy = Math.sin(a).toFloat()
                drawLine(
                    AuraColors.Ink2,
                    androidx.compose.ui.geometry.Offset(c.x + dx * 7f * u, c.y + dy * 7f * u),
                    androidx.compose.ui.geometry.Offset(c.x + dx * 9.5f * u, c.y + dy * 9.5f * u),
                    stroke, androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
            drawCircle(AuraColors.Ink2, radius = 7.5f * u, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        }
    }
}

/** Orbe de voz: toque abre o reconhecimento de voz do Android; sem ele, abre o chat para escrever. */
@Composable
private fun VoiceOrb(available: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(200.dp)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (available) "Falar com o AURA" else "Voz indisponível neste aparelho. Toque para escrever ao AURA" },
    ) {
        AuraOrb(Modifier.fillMaxSize(), glow = true, breathing = true)
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .offset(x = (-4).dp, y = (-34).dp)
                .heightIn(min = 32.dp)
                .clip(AuraShapes.Chip)
                .background(Color.White.copy(alpha = 0.94f))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { Text(if (available) "Toque para falar" else "Sem voz neste aparelho", style = AuraType.Caption.copy(letterSpacing = 0.sp, color = AuraColors.Ink)) }
    }
}

/** Sugestão só nasce de tarefa real do engine: aguardando aprovação > andando > falhou. */
@Composable
private fun SuggestionCard(jobs: List<Job>, onOpenChat: (ChatEntry) -> Unit, onOpenActivity: (() -> Unit)? = null) {
    var dismissedId by rememberSaveable { mutableStateOf("") }
    val awaiting = jobs.firstOrNull { it.status == JobStatus.AwaitingApproval }
    val active = jobs.firstOrNull { it.status == JobStatus.Running || it.status == JobStatus.Queued }
    val failed = jobs.firstOrNull { it.status == JobStatus.Failed }
    val job = awaiting ?: active ?: failed ?: return
    if (job.id == dismissedId) return
    val (label, body, action) = when {
        job === awaiting -> Triple("PRECISA DE VOCÊ", "Esse pedido está esperando a sua decisão.", "Revisar aprovação")
        job === active -> Triple(
            "EM ANDAMENTO",
            if (job.status == JobStatus.Queued) "Está na fila do AURA." else "O AURA está trabalhando nisso agora.",
            "Acompanhar tarefa",
        )
        else -> Triple("SUGESTÃO DO AURA", "Não terminou da última vez. Posso tentar de novo.", "Tentar de novo")
    }
    val entry = if (job === failed) ChatEntry(open = true, prompt = job.title) else ChatEntry(open = true, jobId = job.id)
    AuraCard(contentDescription = "$label: ${job.title}") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AuraOrb(Modifier.size(24.dp))
            Text(label, style = AuraType.Caption)
        }
        Text(job.title, style = AuraType.Section, modifier = Modifier.padding(top = 6.dp).semantics { heading() })
        Text(body, style = AuraType.Secondary)
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AuraButtonPrimary(action, { if (onOpenActivity != null) onOpenActivity() else onOpenChat(entry) })
            AuraButtonText("Depois", { dismissedId = job.id })
        }
    }
}

@Composable
private fun OfflineCard(reason: OfflineReason, onAction: ((OfflineReason) -> Unit)?, onRetry: () -> Unit) {
    val (title, body, action) = when (reason) {
        OfflineReason.NoTermux -> Triple("O Termux não está instalado", "O AURA precisa do Termux neste celular. Instale e volte aqui.", "Instalar o Termux")
        OfflineReason.NoPermission -> Triple("Falta a permissão do Termux", "Libere o acesso ao Termux para o AURA trabalhar.", "Dar permissão")
        OfflineReason.NoToken -> Triple("Falta conectar o AURA ao Termux", "Faça o pareamento uma vez e o AURA volta a trabalhar.", "Conectar o Termux")
        else -> Triple("Motor desligado", "Abra o Termux e ligue o AURA. O que você pedir fica guardado e vai sozinho quando ele voltar.", "Ligar o motor")
    }
    AuraCard(tint = AuraColors.NeedsYou.bg, contentDescription = "Motor desligado") {
        Text(title, style = AuraType.Section.copy(color = AuraColors.NeedsYou.fg), modifier = Modifier.semantics { heading() })
        Text(body, style = AuraType.Secondary.copy(color = AuraColors.NeedsYou.fg))
        // Sem quem execute a ação do motivo, o botão vira "Tentar de novo", que sempre faz algo.
        Box(Modifier.padding(top = 8.dp)) {
            if (onAction != null) AuraButtonSecondary(action, { onAction(reason) }) else AuraButtonSecondary("Tentar de novo", onRetry)
        }
    }
}

private enum class ShortcutIcon { Plus, Phone, Rebuild }

/** Atalho largo para a reforma de apps (engenharia reversa): um toque até "Escolher APK". */
@Composable
private fun ReformShortcut(onClick: () -> Unit) {
    AuraCard(
        modifier = Modifier.fillMaxWidth(), onClick = onClick, tint = AuraColors.CategoryLilac,
        contentDescription = "Reformar apps. Refaça um app que já existe com o visual do AURA",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(AuraShapes.Circle).background(Color.White), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(20.dp)) { drawRebuildIcon() }
            }
            Column(Modifier.weight(1f)) {
                Text("Reformar apps", style = AuraType.Section.copy(fontSize = 17.sp, lineHeight = 22.sp))
                Text("Refaça um app que já existe", style = AuraType.SecondarySmall)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRebuildIcon() {
    val stroke = Stroke(width = 1.75.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
    drawArc(
        AuraColors.Ink, startAngle = 40f, sweepAngle = 280f, useCenter = false,
        topLeft = Offset(2.dp.toPx(), 2.dp.toPx()), size = Size(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()), style = stroke,
    )
    drawLine(AuraColors.Ink, Offset(size.width - 3.dp.toPx(), 3.dp.toPx()), Offset(size.width - 3.dp.toPx(), 8.dp.toPx()), stroke.width, stroke.cap)
    drawLine(AuraColors.Ink, Offset(size.width - 3.dp.toPx(), 8.dp.toPx()), Offset(size.width - 8.dp.toPx(), 8.dp.toPx()), stroke.width, stroke.cap)
}

@Composable
private fun ShortcutCard(
    title: String,
    subtitle: String,
    tint: Color,
    icon: ShortcutIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AuraCard(modifier = modifier.heightIn(min = 126.dp), onClick = onClick, tint = tint, contentDescription = "$title. $subtitle") {
        Box(Modifier.size(40.dp).clip(AuraShapes.Circle).background(Color.White), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(20.dp)) {
                val stroke = Stroke(width = 1.75.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                when (icon) {
                    ShortcutIcon.Plus -> {
                        drawLine(AuraColors.Ink, Offset(size.width / 2, 2.dp.toPx()), Offset(size.width / 2, size.height - 2.dp.toPx()), stroke.width, stroke.cap)
                        drawLine(AuraColors.Ink, Offset(2.dp.toPx(), size.height / 2), Offset(size.width - 2.dp.toPx(), size.height / 2), stroke.width, stroke.cap)
                    }
                    ShortcutIcon.Rebuild -> drawRebuildIcon()
                    ShortcutIcon.Phone -> drawRoundRect(
                        AuraColors.Ink, topLeft = Offset(size.width * 0.2f, 1.dp.toPx()),
                        size = Size(size.width * 0.6f, size.height - 2.dp.toPx()),
                        cornerRadius = CornerRadius(4.dp.toPx()), style = stroke,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(title, style = AuraType.Section.copy(fontSize = 17.sp, lineHeight = 22.sp))
        Text(subtitle, style = AuraType.SecondarySmall)
    }
}

internal val previewCaps = listOf(Capability("termux", "Termux", true))

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, heightDp = 844, widthDp = 390)
@Composable
private fun HomeEmptyPreview() {
    AuraTheme { HomeView(HomeData.Ready(emptyList(), previewCaps), "", {}, {}, {}, {}) }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, heightDp = 844, widthDp = 390)
@Composable
private fun HomeOfflinePreview() {
    AuraTheme { HomeView(HomeData.Offline, "Corrigir a tela de login", {}, {}, {}, {}) }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, heightDp = 640, widthDp = 360)
@Composable
private fun HomeLoadingSmallPreview() {
    AuraTheme { HomeView(HomeData.Loading, "", {}, {}, {}, {}) }
}
