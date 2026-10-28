package ai.aura.app.ui.screens.capabilities

import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.components.AuraButtonText
import ai.aura.app.ui.components.AuraCard
import ai.aura.app.ui.components.StatusChip
import ai.aura.app.ui.components.StatusKind
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.screens.Reading
import ai.aura.app.ui.screens.devices.BackHeader
import ai.aura.app.ui.screens.devices.GlyphTile
import ai.aura.app.ui.screens.settings.RowDivider
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraSpacing
import ai.aura.app.ui.theme.AuraType
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal fun CapGroup.tint(): Color = when (this) {
    CapGroup.Engineering -> AuraColors.CategoryEngineering
    CapGroup.Clarity -> AuraColors.CategoryClarity
    CapGroup.Android -> AuraColors.CategoryAndroid
    CapGroup.Organization -> AuraColors.CategoryOrganization
}

/** Pílula com o nome curto do grupo, na cor da categoria. */
@Composable
internal fun GroupChip(group: CapGroup, modifier: Modifier = Modifier) {
    Box(modifier.clip(AuraShapes.Chip).background(group.tint()).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(group.chip, style = AuraType.Chip)
    }
}

/**
 * Tela Capacidades: 4 grupos com as skills do motor, "Em uso agora" (derivado dos jobs) e "Importar skill".
 * Recebe estado e ações por parâmetro. Desligar uma capacidade em uso para o que está rodando ([onStop]).
 */
@Composable
fun CapabilitiesScreen(
    state: Reading<CapabilitiesData>,
    jobs: List<Job>,
    onOpen: (Skill) -> Unit,
    onStop: (List<String>) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var importing by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize().background(AuraColors.Bg).statusBarsPadding()) {
        BackHeader("Capacidades", null, onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = AuraSpacing.ScreenGutter).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("O AURA escolhe sozinho. Aqui você vê e ajusta o que ele pode usar.", style = AuraType.Secondary)
            when (state) {
                is Reading.Loading -> Text("Lendo as capacidades…", style = AuraType.Secondary)
                is Reading.Failed -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        state.message, style = AuraType.Body,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    AuraButtonPrimary("Tentar de novo", onRetry)
                }
                is Reading.Loaded -> {
                    if (state.stale) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            StatusChip(StatusKind.NeedsYou, "Última leitura")
                            Text(
                                "O motor não respondeu agora. Isto é o que ficou guardado no celular.",
                                style = AuraType.SecondarySmall,
                            )
                            AuraButtonText("Tentar de novo", onRetry)
                        }
                    }
                    state.value.skills.byGroup().forEach { (group, skills) -> GroupBlock(group, skills, onOpen) }
                    InUseBlock(state.value.skills, jobs, onOpen, onStop)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AuraButtonSecondary("Importar skill", { importing = !importing }, Modifier.fillMaxWidth())
                Text("Importar não libera comandos sozinho.", style = AuraType.SecondarySmall)
                if (importing) {
                    AuraCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Hoje o motor só lê skills que estão na pasta skills dele, no Termux. " +
                                    "Coloque a pasta da skill lá e toque em Atualizar lista. " +
                                    "Cada permissão dela continua pedindo a sua aprovação.",
                                style = AuraType.Body,
                            )
                            AuraButtonText("Atualizar lista", onRetry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupBlock(group: CapGroup, skills: List<Skill>, onOpen: (Skill) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlyphTile(skills.size.toString(), group.tint())
            Column {
                Text(group.title, style = AuraType.BodyStrong, modifier = Modifier.semantics { heading() })
                Text(group.hint, style = AuraType.SecondarySmall)
            }
        }
        if (skills.isEmpty()) {
            Text("Nenhuma capacidade neste grupo ainda.", style = AuraType.SecondarySmall)
        } else {
            AuraCard(Modifier.fillMaxWidth()) {
                skills.forEachIndexed { i, skill ->
                    if (i > 0) RowDivider()
                    NavRow(skill.info().title) { onOpen(skill) }
                }
            }
        }
    }
}

@Composable
private fun InUseBlock(skills: List<Skill>, jobs: List<Job>, onOpen: (Skill) -> Unit, onStop: (List<String>) -> Unit) {
    val using = inUse(jobs).mapNotNull { (id, jobIds) -> skills.firstOrNull { it.id == id }?.let { it to jobIds } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Em uso agora", style = AuraType.Section, modifier = Modifier.semantics { heading() })
        if (using.isEmpty()) {
            Text("Nada em uso agora.", style = AuraType.SecondarySmall)
        } else {
            Text("Desligar uma capacidade para o que ela está fazendo agora.", style = AuraType.SecondarySmall)
            AuraCard(Modifier.fillMaxWidth()) {
                using.forEachIndexed { i, (skill, jobIds) ->
                    if (i > 0) RowDivider()
                    val title = skill.info().title
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            title, style = AuraType.BodyStrong,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                .clickable(role = Role.Button) { onOpen(skill) }
                                .padding(vertical = 12.dp),
                        )
                        Switch(
                            checked = true,
                            onCheckedChange = { if (!it) onStop(jobIds) },
                            modifier = Modifier.semantics { contentDescription = "Parar: $title" },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White, checkedTrackColor = AuraColors.Ink,
                                checkedBorderColor = AuraColors.Ink,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NavRow(title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = AuraType.BodyStrong, modifier = Modifier.weight(1f))
        Text("›", style = AuraType.Section)
    }
}
