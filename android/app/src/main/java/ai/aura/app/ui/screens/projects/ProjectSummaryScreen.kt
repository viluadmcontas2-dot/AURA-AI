package ai.aura.app.ui.screens.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType
import androidx.compose.ui.graphics.Color

private val ChipTints = listOf(AuraColors.CategoryEngineering, AuraColors.CategoryAndroid, AuraColors.CategoryClarity, AuraColors.CategoryOrganization)

/**
 * Passo 2 (Projeto-Novo.dc.html): resumo organizado. Só aparece DEPOIS da ideia. Se faltar o
 * essencial, [question] aparece uma única vez; responder é opcional ("Criar projeto" segue ativo).
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun ProjectSummaryScreen(
    idea: String,
    draft: Project,
    question: String?,
    needsApproval: Boolean,
    busy: Boolean,
    error: String?,
    onAnswer: (String) -> Unit,
    onCreate: () -> Unit,
    onAdjust: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().imePadding()) {
        ProjHeader("Ideia vira projeto", onAdjust, backDescription = "Voltar para a ideia")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.align(Alignment.End).widthIn(max = 310.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp))
                    .background(AuraColors.Ink).padding(horizontal = 16.dp, vertical = 12.dp),
            ) { Text(idea, style = AuraType.Body.copy(color = Color.White, lineHeight = 23.sp)) }

            Column(
                Modifier.fillMaxWidth().clip(AuraShapes.Card).background(AuraColors.Surface)
                    .border(1.dp, AuraColors.Line, AuraShapes.Card).padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(20.dp).clip(AuraShapes.Circle).background(AuraColors.Periwinkle))
                    SectionLabel("AURA")
                }
                Text("Organizei sua ideia", style = AuraType.TabTitle.copy(fontSize = 22.sp, lineHeight = 26.sp), modifier = Modifier.padding(top = 6.dp))
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FactRow("Nome", draft.name, FontWeight.Bold)
                    FactRow("Objetivo", draft.goal, FontWeight.SemiBold)
                    FactRow("Plataforma", draft.platform, FontWeight.SemiBold)
                }
                Box(Modifier.padding(vertical = 12.dp).fillMaxWidth().height(1.dp).background(AuraColors.Line))
                if (draft.screens.isNotEmpty()) {
                    SectionLabel("Telas")
                    FlowRow(Modifier.padding(top = 6.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        draft.screens.forEachIndexed { i, s ->
                            Box(Modifier.heightIn(min = 30.dp).clip(AuraShapes.Chip).background(ChipTints[i % ChipTints.size]).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                                Text(s, style = AuraType.SecondarySmall.copy(fontWeight = FontWeight.SemiBold, color = AuraColors.Ink))
                            }
                        }
                    }
                }
                SectionLabel("Pronto quando")
                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    draft.doneWhen.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(20.dp).clip(AuraShapes.Circle).background(AuraColors.Ready.bg), contentAlignment = Alignment.Center) {
                                PIconView(PIcon.Check, tint = AuraColors.Ready.fg, size = 12.dp)
                            }
                            Text(c, style = AuraType.Secondary.copy(color = AuraColors.Ink))
                        }
                    }
                }
                SectionLabel("Entregas", Modifier.padding(top = 12.dp))
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    draft.deliveries.forEachIndexed { i, d ->
                        Row(
                            Modifier.weight(1f).heightIn(min = 36.dp).clip(AuraShapes.Chip).background(AuraColors.SurfaceSunken).padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("${i + 1}", style = AuraType.Chip.copy(color = AuraColors.Ink))
                            Text(d, style = AuraType.Chip.copy(color = AuraColors.Ink, fontWeight = FontWeight.SemiBold), maxLines = 1)
                        }
                    }
                }
            }

            if (question != null) QuestionCard(question, onAnswer)
            if (needsApproval) NoteBanner("Criar o repositório privado ${draft.repo} no GitHub precisa da sua aprovação.", AuraColors.NeedsYou, PIcon.Lock)
            if (error != null) NoteBanner(error, AuraColors.Risk, PIcon.Lock)
        }
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AuraButtonPrimary(if (busy) "Criando…" else "Criar projeto", onCreate, Modifier.weight(1f), enabled = !busy, large = true)
            AuraButtonSecondary("Ajustar", onAdjust, enabled = !busy)
        }
    }
}

@Composable
private fun FactRow(label: String, value: String, weight: FontWeight) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = AuraType.Secondary, modifier = Modifier.width(84.dp))
        Text(value, style = AuraType.Secondary.copy(color = AuraColors.Ink, fontWeight = weight), modifier = Modifier.weight(1f))
    }
}

/** A pergunta única: um campo curto. Enviar a resposta refaz o resumo; ignorar também é válido. */
@Composable
private fun QuestionCard(question: String, onAnswer: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().clip(AuraShapes.Card).background(AuraColors.Surface).border(1.dp, AuraColors.LineStrong, AuraShapes.Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionLabel("Só uma pergunta")
        Text(question, style = AuraType.BodyStrong)
        Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(AuraShapes.Chip).background(AuraColors.SurfaceSunken).padding(horizontal = 12.dp, vertical = 10.dp)) {
            BasicTextField(
                value = text, onValueChange = { text = it }, textStyle = AuraType.Body, cursorBrush = SolidColor(AuraColors.Ink),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Sua resposta" },
                decorationBox = { inner -> Box { if (text.isEmpty()) Text("Responda em poucas palavras", style = AuraType.Body.copy(color = AuraColors.Ink3)); inner() } },
            )
        }
        AuraButtonSecondary("Enviar resposta", { onAnswer(text.trim()) }, enabled = text.isNotBlank())
        Text("Se preferir, toque em Criar projeto e eu sigo assim.", style = AuraType.SecondarySmall)
    }
}
