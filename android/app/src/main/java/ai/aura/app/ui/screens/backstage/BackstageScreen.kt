package ai.aura.app.ui.screens.backstage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.aura.app.ui.components.EmptyKind
import ai.aura.app.ui.components.EmptyState
import ai.aura.app.ui.engine.EngineEvent
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobRisk
import ai.aura.app.ui.engine.JobStatus
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// shortcut: cores da tela escura que não estão em tokens.json (ok, aviso, falha, botão e bloco de saída); mover para o tema se outra tela usar.
private val OkGreen = Color(0xFF6EE7A8)
private val FailRed = Color(0xFFFF8A8A)
private val WarnAmber = Color(0xFFFFC76B)
private val ButtonDark = Color(0xFF1F1E28)
private val OutputBlock = Color(0xFF0C0B12)

/**
 * Bastidores.dc.html: o que o AURA fez por baixo, uma linha por pedido (comando), com a saída ao tocar.
 * O motor só entrega pedido, resultado e eventos; nada aqui é inventado. Sem pedidos: convida a agir.
 * shortcut: sem abas Logs/Mudanças (os registros do pedido aparecem dentro da linha), sem tempo de execução, sem voz.
 * [onRerun] e [onExplain] recebem o pedido tocado; [onCopy] recebe o comando e a saída em texto.
 */
@Composable
fun BackstageScreen(
    jobs: List<Job>,
    events: List<EngineEvent>,
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
    onRerun: (Job) -> Unit,
    onExplain: (Job) -> Unit,
    onAsk: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var abertoId by remember { mutableStateOf<String?>(null) }
    Column(
        modifier.fillMaxSize().background(AuraColors.DarkBackstage).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clickable(role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = "Voltar para o resultado" },
                contentAlignment = Alignment.Center,
            ) { Text("‹", style = AuraType.TabTitle.copy(color = AuraColors.DarkText)) }
            Text("Bastidores", style = AuraType.TabTitle.copy(color = AuraColors.DarkText))
        }
        if (jobs.isEmpty()) {
            EmptyState(EmptyKind.Activity, onAsk)
        } else {
            Text(
                "É o que o AURA fez por baixo. Você não precisa entender, mas pode conferir tudo.",
                style = AuraType.Secondary.copy(color = AuraColors.DarkText2),
            )
            Text(
                "O motor ainda não guarda cada comando em separado. Aqui aparecem o pedido, o resultado e os registros dele.",
                style = AuraType.SecondarySmall.copy(color = AuraColors.DarkText2),
            )
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (job in jobs) {
                    CommandRow(
                        job, events.filter { it.jobId == job.id }, aberto = abertoId == job.id,
                        onToggle = { abertoId = if (abertoId == job.id) null else job.id },
                        onCopy = onCopy, onRerun = onRerun, onExplain = onExplain,
                    )
                }
            }
            Box(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(AuraShapes.Pill).background(AuraColors.DarkPanel)
                    .border(1.dp, AuraColors.DarkLine, AuraShapes.Pill)
                    .clickable(role = Role.Button, onClick = onAsk).padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) { Text("Pergunte sobre este comando…", style = AuraType.Secondary.copy(color = AuraColors.DarkText2)) }
        }
    }
}

@Composable
private fun CommandRow(
    job: Job,
    registros: List<EngineEvent>,
    aberto: Boolean,
    onToggle: () -> Unit,
    onCopy: (String) -> Unit,
    onRerun: (Job) -> Unit,
    onExplain: (Job) -> Unit,
) {
    val comando = (if (job.kind == "shell" || job.kind == "adb") "\$ " else "› ") + job.title
    val saida = remember(job) {
        ((job.result?.lines()?.filter { it.isNotBlank() } ?: emptyList()) + listOfNotNull(job.errorCode?.let { "erro: $it" })).take(200)
    }
    val (situacao, corSituacao) = when {
        job.status == JobStatus.Completed -> "ok" to OkGreen
        job.status == JobStatus.Failed -> "falhou" to FailRed
        job.status == JobStatus.Running -> "rodando" to AuraColors.DarkFocus
        job.status.isTerminal -> "parou" to AuraColors.DarkText2
        else -> "esperando" to AuraColors.DarkText2
    }
    val acesso = when (job.risk) {
        JobRisk.Read -> "Só lê"
        JobRisk.Write -> "Altera arquivos"
        JobRisk.Spend -> "Gasta dinheiro"
        JobRisk.Critical -> "Ação crítica"
    }
    Column(
        Modifier.fillMaxWidth().clip(AuraShapes.Chip).background(AuraColors.DarkPanel)
            .border(1.dp, if (aberto) AuraColors.DarkFocus else AuraColors.DarkLine, AuraShapes.Chip).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Só o cabeçalho é clicável: a saída e os botões ficam fora dele para não serem engolidos.
        Column(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onToggle),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(comando, style = AuraType.Mono.copy(color = AuraColors.DarkText, fontSize = 13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(situacao, style = AuraType.Chip.copy(color = corSituacao))
                Text(acesso, style = AuraType.Chip.copy(color = if (job.risk == JobRisk.Read) AuraColors.DarkText2 else WarnAmber))
            }
        }
        if (aberto) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(OutputBlock)
                    .border(1.dp, AuraColors.DarkLine, RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (saida.isEmpty()) {
                    val vazio = if (job.status.isTerminal) "Este comando não imprimiu nada." else "Ainda sem saída."
                    Text(vazio, style = AuraType.Mono.copy(color = AuraColors.DarkText2))
                } else {
                    for (linha in saida) Text(linha, style = AuraType.Mono.copy(color = AuraColors.DarkText2))
                }
            }
            if (registros.isNotEmpty()) {
                Text("Registros", style = AuraType.Caption.copy(color = AuraColors.DarkText2))
                for (e in registros) {
                    Text(
                        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(e.timestampMillis)) + "  " + e.type,
                        style = AuraType.Mono.copy(color = AuraColors.DarkText2),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var copiado by remember { mutableStateOf(false) }
                DarkButton(
                    if (copiado) "Copiado" else "Copiar",
                    { onCopy((listOf(job.title) + saida).joinToString("\n")); copiado = true },
                    Modifier.weight(1f),
                )
                DarkButton("Rodar de novo", { onRerun(job) }, Modifier.weight(1f), enabled = job.status.isTerminal)
                DarkButton("Explicar simples", { onExplain(job) }, Modifier.weight(1.2f), destaque = true)
            }
        }
    }
}

/** Botão da tela escura: o botão padrão do AURA é preto e some no fundo escuro. */
@Composable
private fun DarkButton(texto: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, destaque: Boolean = false) {
    Box(
        modifier.heightIn(min = 44.dp).clip(AuraShapes.Button).background(if (destaque) AuraColors.DarkText else ButtonDark)
            .border(1.dp, AuraColors.DarkLine, AuraShapes.Button)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            texto,
            style = AuraType.Button.copy(color = if (destaque) AuraColors.Ink else AuraColors.DarkText, fontSize = 13.sp),
            textAlign = TextAlign.Center,
            modifier = Modifier.alpha(if (enabled) 1f else 0.4f),
        )
    }
}
