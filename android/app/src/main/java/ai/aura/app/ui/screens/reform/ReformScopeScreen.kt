package ai.aura.app.ui.screens.reform

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraSwitch
import ai.aura.app.ui.components.EmptyState
import ai.aura.app.ui.components.auraFocusRing
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraType
import ai.aura.app.ui.theme.StatusColors

/** Reformar 2 · Escopo. Com app ou APK escolhido, entrega o plano por [onStart]. */
@Composable
fun ReformScopeScreen(
    app: ReformApp?,
    onBack: () -> Unit,
    onStart: (ReformPlan) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scope by rememberSaveable(app?.id) { mutableStateOf(ReformScope.default) }
    var carMode by rememberSaveable(app?.id) { mutableStateOf(true) }

    Column(modifier.fillMaxSize().background(AuraColors.Bg).statusBarsPadding()) {
        ReformKit.Header(
            onBack = onBack, backDescription = "Voltar para a escolha do app",
            title = "Escopo da reforma", subtitle = app?.label,
            titleStyle = AuraType.Display.copy(fontSize = 22.sp, lineHeight = 26.sp),
        )
        when {
            app == null -> Blocked(
                "Nenhum app escolhido",
                "Volte e escolha um app ou APK para definir o escopo.",
                "Escolher app", onBack, glyph = "✦",
            )
            else -> {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReformScope.entries.forEach { ScopeCard(it, selected = it == scope) { scope = it } }
                    }
                    CardRow(Modifier.heightIn(min = 56.dp).padding(start = 16.dp, end = 12.dp)) {
                        Text("Modo carro: botões grandes e texto maior", style = ROW_TEXT, modifier = Modifier.weight(1f))
                        AuraSwitch(carMode, { carMode = it }, "Modo carro")
                    }
                    CardRow(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("Meta de fidelidade", style = ROW_TEXT)
                        GoalBar(REFORM_FIDELITY_TARGET_PERCENT, Modifier.weight(1f))
                        Text(
                            "$REFORM_FIDELITY_TARGET_PERCENT%",
                            style = AuraType.Body.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold),
                            textAlign = TextAlign.End, modifier = Modifier.width(40.dp),
                        )
                    }
                }
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ReformKit.GlyphView(ReformKit.Glyph.Clock, tint = AuraColors.Ink2, size = 14.dp)
                        Text(
                            scope.estimate, modifier = Modifier.padding(start = 6.dp),
                            style = AuraType.SecondarySmall.copy(fontSize = 13.sp, lineHeight = 16.sp),
                        )
                    }
                    AuraButtonPrimary(
                        "Travar escopo e começar",
                        {
                            onStart(
                                ReformPlan(
                                    appId = app.id, appLabel = app.label, scope = scope, carMode = carMode,
                                    fidelityTargetPercent = REFORM_FIDELITY_TARGET_PERCENT, apkUri = app.apkUri,
                                ),
                            )
                        },
                        Modifier.fillMaxWidth(), large = true,
                    )
                }
            }
        }
    }
}

private val ROW_TEXT = AuraType.Secondary.copy(fontWeight = FontWeight.SemiBold, color = AuraColors.Ink)

@Composable
private fun Blocked(title: String, body: String, action: String, onAction: () -> Unit, glyph: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(title, body, action, onAction, glyph = glyph)
    }
}

/** Cartão branco de uma linha do design (borda #E6E3DC, raio 24). */
@Composable
private fun CardRow(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(AuraShapes.Card).background(AuraColors.Surface)
            .border(1.dp, AuraColors.Line, AuraShapes.Card).then(modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun ScopeCard(scope: ReformScope, selected: Boolean, onSelect: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Column(
        Modifier.fillMaxWidth().heightIn(min = 76.dp).clip(AuraShapes.Card).background(AuraColors.Surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) AuraColors.Ink else AuraColors.Line, AuraShapes.Card)
            .auraFocusRing(source, AuraShapes.Card)
            .selectable(
                selected = selected, interactionSource = source, indication = null,
                role = Role.RadioButton, onClick = onSelect,
            )
            .padding(if (selected) 13.dp else 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(24.dp).border(2.dp, if (selected) AuraColors.Ink else AuraColors.LineStrong, AuraShapes.Circle),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Box(Modifier.size(12.dp).clip(AuraShapes.Circle).background(AuraColors.Ink))
            }
            Text(scope.title, style = ReformKit.CardTitle, modifier = Modifier.weight(1f))
            if (scope.recommended) ReformKit.Tag("Recomendado", AuraColors.Info)
        }
        Row(
            Modifier.padding(start = 34.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ReformKit.Tag(scope.durationLabel, AuraColors.Neutral)
            ReformKit.Tag(scope.risk.label, riskColors(scope.risk))
            scope.note?.let {
                Text(
                    it, modifier = Modifier.weight(1f, fill = false),
                    style = AuraType.SecondarySmall.copy(fontSize = 13.sp, lineHeight = 16.sp),
                )
            }
        }
    }
}

private fun riskColors(risk: ReformRisk): StatusColors = when (risk) {
    ReformRisk.Low -> AuraColors.Ready
    ReformRisk.Medium -> AuraColors.NeedsYou
    ReformRisk.High -> AuraColors.Risk
}

/** Meta, não progresso: barra com marcador no valor; o leitor de tela lê "Meta de 90 por cento". */
@Composable
private fun GoalBar(percent: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.height(24.dp).semantics {
            contentDescription = "Meta de $percent por cento"
            role = Role.Image
        },
        contentAlignment = Alignment.CenterStart,
    ) {
        ReformKit.Bar(percent / 100f, Modifier.fillMaxWidth())
        Box(Modifier.fillMaxWidth(percent / 100f), contentAlignment = Alignment.CenterEnd) {
            Box(
                Modifier.offset(x = 12.dp).size(24.dp).clip(AuraShapes.Circle)
                    .background(AuraColors.Surface).border(2.dp, AuraColors.Ink, AuraShapes.Circle),
            )
        }
    }
}
