package ai.aura.app.ui.screens.capabilities

import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraCard
import ai.aura.app.ui.components.StatusChip
import ai.aura.app.ui.components.StatusKind
import ai.aura.app.ui.engine.Capability
import ai.aura.app.ui.screens.devices.BackHeader
import ai.aura.app.ui.screens.settings.RowDivider
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraSpacing
import ai.aura.app.ui.theme.AuraType
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Ids de [Capability] dos três programas de IA (GET /v1/capabilities). */
private val WORKS_WITH = listOf("claude_cli", "gemini_cli", "agy_cli")

/**
 * Detalhe de uma capacidade: modo por permissão (toque troca sempre / perguntar antes / desligado) e
 * "Salvar permissões", que grava de uma vez na política do motor ([onSave]). Só o root tem "desligado".
 * [modes] são os modos já gravados; [error] é a resposta do último salvar.
 */
@Composable
fun CapabilityDetailScreen(
    skill: Skill,
    modes: Map<String, Mode>,
    stale: Boolean,
    providers: List<Capability>,
    error: String?,
    onSave: (Map<String, Mode>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val info = skill.info()
    var draft by remember(modes) { mutableStateOf(emptyMap<String, Mode>()) }
    Column(modifier.fillMaxSize().background(AuraColors.Bg).statusBarsPadding()) {
        BackHeader(info.title, "Capacidade", onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = AuraSpacing.ScreenGutter),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (stale) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusChip(StatusKind.NeedsYou, "Última leitura")
                    Text(
                        "O motor não respondeu agora. Salvar só vale quando ele voltar.",
                        style = AuraType.SecondarySmall,
                    )
                }
            }
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GroupChip(info.group)
                Text(info.summary, style = AuraType.Secondary, modifier = Modifier.weight(1f))
            }
            if (info.examples.isNotEmpty()) {
                Section("Quando o AURA usa") {
                    info.examples.forEach {
                        Text(
                            it, style = AuraType.Body,
                            modifier = Modifier.fillMaxWidth().clip(AuraShapes.Button)
                                .background(AuraColors.SurfaceSunken).padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            Section("O que ela pode fazer") {
                if (info.perms.isEmpty()) {
                    Text(
                        "Esta capacidade não tem permissões próprias aqui. As regras gerais ficam em Ajustes.",
                        style = AuraType.SecondarySmall,
                    )
                } else {
                    Text(
                        "Toque numa linha para trocar o modo. Só vale depois de Salvar permissões.",
                        style = AuraType.SecondarySmall,
                    )
                    AuraCard(Modifier.fillMaxWidth()) {
                        info.perms.forEachIndexed { i, perm ->
                            if (i > 0) RowDivider()
                            val shown = draft[perm.key] ?: modes[perm.key] ?: Mode.Ask
                            PermRow(perm, shown) {
                                val options = perm.modes
                                val next = options[(options.indexOf(shown) + 1) % options.size]
                                draft = if (next == modes[perm.key]) draft - perm.key else draft + (perm.key to next)
                            }
                        }
                    }
                }
            }
            Section("Funciona com") {
                val found = providers.filter { it.id in WORKS_WITH }
                if (found.isEmpty()) {
                    Text("O motor ainda não informou quais programas estão instalados.", style = AuraType.SecondarySmall)
                } else {
                    AuraCard(Modifier.fillMaxWidth()) {
                        found.forEachIndexed { i, provider ->
                            if (i > 0) RowDivider()
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(provider.label, style = AuraType.BodyStrong, modifier = Modifier.weight(1f))
                                StatusChip(
                                    if (provider.available) StatusKind.Ready else StatusKind.NeedsYou,
                                    if (provider.available) "instalado" else "não instalado",
                                )
                            }
                        }
                    }
                }
            }
            Text(
                "Origem: " + if (skill.origin == "bundled") "vem com o AURA" else "colocada por você",
                style = AuraType.SecondarySmall,
            )
            if (error != null) {
                Text(
                    error, style = AuraType.SecondarySmall.copy(color = AuraColors.Risk.fg),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        if (info.perms.isNotEmpty()) {
            AuraButtonPrimary(
                "Salvar permissões", { onSave(draft) },
                Modifier.fillMaxWidth().padding(horizontal = AuraSpacing.ScreenGutter, vertical = 8.dp),
                enabled = draft.isNotEmpty(), large = true,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = AuraType.Section, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun PermRow(perm: Perm, mode: Mode, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Mudar modo de ${perm.label}: ${mode.label}" }
            .padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(perm.label, style = AuraType.BodyStrong, modifier = Modifier.weight(1f))
        StatusChip(
            when (mode) {
                Mode.Always -> StatusKind.Ready
                Mode.Ask -> StatusKind.NeedsYou
                Mode.Off -> StatusKind.Neutral
            },
            mode.label,
        )
        Text("›", style = AuraType.Section)
    }
}
