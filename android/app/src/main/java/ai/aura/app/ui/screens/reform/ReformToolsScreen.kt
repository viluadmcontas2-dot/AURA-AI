package ai.aura.app.ui.screens.reform

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import ai.aura.app.ui.components.AuraButtonText
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.components.AuraProgressBar
import ai.aura.app.ui.components.StatusChip
import ai.aura.app.ui.components.StatusKind
import ai.aura.app.ui.screens.activity.IncidentBanner
import ai.aura.app.ui.screens.activity.IncidentLevel
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraType

/**
 * Reformar 5 · Ferramentas. O motor conta o estado de cada ferramenta ([state]); sem dado, as seis aparecem como
 * "Não verificado". O botão principal nunca fica mudo: instalar, esperar (desligado, dizendo o quê) ou voltar.
 * Com problema, a única ação é a correção.
 */
@Composable
fun ReformToolsScreen(
    state: ReformToolsState,
    onBack: () -> Unit,
    onInstall: () -> Unit,
    onResume: () -> Unit,
    onFix: (ReformFix) -> Unit,
    modifier: Modifier = Modifier,
) {
    val problem = state.problem
    val action = state.action
    Column(modifier.fillMaxSize().background(AuraColors.Bg).statusBarsPadding()) {
        ReformKit.Header(onBack = onBack, backDescription = "Voltar ao projeto")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                Text("Ferramentas da reforma", style = AuraType.TabTitle, modifier = Modifier.semantics { heading() })
                Text(
                    "O AURA instala sozinho no Termux e guarda o estado.",
                    style = AuraType.SecondarySmall, modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (problem != null) IncidentBanner(IncidentLevel.NeedsYou, problem.title, problem.body)
            state.waitMessage?.let { ReformKit.Note(it) }
            if (state.downloadNotices.isNotEmpty()) DownloadNotice(state.downloadNotices)
            Column(
                Modifier.fillMaxWidth().clip(AuraShapes.Card).background(AuraColors.Surface)
                    .border(1.dp, AuraColors.Line, AuraShapes.Card).padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                state.rows.forEachIndexed { i, tool ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(AuraColors.Line))
                    ToolRow(tool)
                }
            }
            ReformKit.Note("O JADX usa muita memória. Se faltar, o AURA divide o trabalho em partes menores.")
            SessionCard(state.session, onResume)
        }
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp)) {
            if (problem != null) {
                AuraButtonPrimary(problem.fix.label, { onFix(problem.fix) }, Modifier.fillMaxWidth(), large = true)
            } else {
                AuraButtonPrimary(
                    action.label, { if (action == ReformToolsAction.Done) onBack() else onInstall() },
                    Modifier.fillMaxWidth(), enabled = action != ReformToolsAction.Busy, large = true,
                )
            }
        }
    }
}

/**
 * Aviso pequeno de download grande (>100 MB). Não bloqueia nada: os botões da tela seguem ativos.
 * Toque mostra o tamanho em MB; "Entendi" (ou um novo toque no texto) fecha. Some até o motor avisar de novo.
 */
@Composable
private fun DownloadNotice(notices: List<ai.aura.app.integration.ReformDownloadNotice>) {
    var open by rememberSaveable(notices) { mutableStateOf(false) }
    var closed by rememberSaveable(notices) { mutableStateOf(false) }
    if (closed) return
    val shape = AuraShapes.Chip
    if (!open) {
        Text(
            "Download grande · toque para ver o tamanho",
            style = AuraType.Chip.copy(color = AuraColors.Info.fg),
            modifier = Modifier.heightIn(min = 44.dp).clip(shape).background(AuraColors.Info.bg)
                .clickable(role = Role.Button) { open = true }
                .padding(horizontal = 12.dp, vertical = 12.dp),
        )
    } else {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(AuraColors.Info.bg).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            notices.forEach { n ->
                val label = REFORM_REQUIRED_TOOLS.firstOrNull { it.id == n.step }?.label ?: n.step
                Text("$label: cerca de ${n.mb} MB", style = AuraType.SecondarySmall.copy(color = AuraColors.Info.fg))
            }
            AuraButtonText("Entendi", { closed = true })
        }
    }
}

/** Uma ferramenta: nome + estado (ponto e texto) e, instalando, a barra com o percentual do motor. */
@Composable
private fun ToolRow(tool: ReformTool) {
    Column(Modifier.padding(vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 28.dp)
                .clearAndSetSemantics { contentDescription = "${tool.label}: ${tool.chipText}" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(tool.label, style = AuraType.Body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            StatusChip(
                when (tool.status) {
                    ReformToolStatus.Ready -> StatusKind.Ready
                    ReformToolStatus.Installing, ReformToolStatus.WaitingWifi -> StatusKind.Info
                    ReformToolStatus.Missing -> StatusKind.NeedsYou
                    ReformToolStatus.Failed -> StatusKind.Risk
                    ReformToolStatus.Queued, ReformToolStatus.Unknown -> StatusKind.Neutral
                },
                tool.chipText,
            )
        }
        val percent = tool.percent
        if (tool.status == ReformToolStatus.Installing && percent != null) {
            AuraProgressBar(percent / 100f, alive = true, contentDescription = "Instalação de ${tool.label}")
        }
    }
}

/** O que já foi feito, para o motor continuar de onde parou. Sem sessão, não há o que retomar (nada de botão mudo). */
@Composable
private fun SessionCard(session: List<ReformSessionEntry>, onResume: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(AuraShapes.Card).background(AuraColors.Surface)
            .border(1.dp, AuraColors.Line, AuraShapes.Card).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Sessão salva", style = AuraType.Section, modifier = Modifier.semantics { heading() })
        if (session.isEmpty()) {
            Text(
                "Ainda não há sessão salva. Quando o AURA instalar algo, o progresso fica guardado aqui.",
                style = AuraType.SecondarySmall,
            )
        } else {
            session.forEach { entry ->
                Row(
                    Modifier.heightIn(min = 30.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier.size(10.dp).clip(AuraShapes.Circle)
                            .background(if (entry.active) AuraColors.Periwinkle else AuraColors.LineStrong),
                    )
                    Text(entry.time, style = AuraType.Mono.copy(fontSize = 13.sp), modifier = Modifier.width(44.dp))
                    Text(entry.text, style = AuraType.SecondarySmall)
                }
            }
            AuraButtonSecondary("Retomar do último", onResume, Modifier.fillMaxWidth())
            Text(
                "Se o Android encerrar o Termux, nada se perde.",
                style = AuraType.SecondarySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
