package ai.aura.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.components.AuraButtonText
import ai.aura.app.ui.components.StatusChip
import ai.aura.app.ui.components.StatusKind
import ai.aura.app.ui.engine.Gate
import ai.aura.app.ui.engine.ModelsDoc
import ai.aura.app.ui.engine.PolicyDoc
import ai.aura.app.ui.engine.PolicyMode
import ai.aura.app.ui.engine.PolicyVersion
import ai.aura.app.ui.engine.POLICY_KIND_KEYS
import ai.aura.app.ui.engine.POLICY_RISK_KEYS
import ai.aura.app.ui.engine.SpendCap
import ai.aura.app.ui.engine.SpendResult
import ai.aura.app.ui.theme.AuraType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PtBr = Locale("pt", "BR")

private val GateOptions = listOf(Gate.Auto to "Auto", Gate.Ask to "Perguntar")

private fun riskLabel(key: String) = when (key) {
    "read" -> "Ler"
    "write" -> "Escrever"
    "spend" -> "Gastar"
    "critical" -> "Ação crítica"
    else -> key
}

private fun kindLabel(key: String) = when (key) {
    "shell" -> "Terminal"
    "adb" -> "Controle do Android (adb)"
    "files" -> "Arquivos"
    "github" -> "GitHub"
    "root" -> "Root (su, tsu)"
    else -> key
}

private fun modeLabel(m: PolicyMode) = when (m) {
    PolicyMode.Pergunta -> "Pergunta" to "Pede o seu ok antes de agir."
    PolicyMode.Equilibrado -> "Equilibrado" to "Lê sozinho; pergunta antes de gastar ou apagar."
    PolicyMode.Autonomo -> "Autônomo" to "Faz sozinho e avisa; pergunta só o crítico."
    PolicyMode.Total -> "Total" to "Faz tudo sozinho, sem perguntar."
}

internal fun formatUsd(v: Double): String = String.format(PtBr, "US$ %.2f", v)

internal fun parseMoney(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }

@Composable
internal fun AuthorizationSection(p: PolicyDoc, onEdit: ((PolicyDoc) -> PolicyDoc) -> Unit) {
    SectionLabel("Autorização")
    SettingsGroup {
        PolicyMode.entries.forEachIndexed { i, m ->
            if (i > 0) RowDivider()
            val (title, sub) = modeLabel(m)
            ChoiceRow(title, sub, selected = p.mode == m, onClick = { onEdit { it.copy(mode = m) } })
        }
    }
    SettingsGroup {
        Text("Por tipo de ação", style = AuraType.Caption, modifier = Modifier.padding(top = 6.dp))
        POLICY_RISK_KEYS.forEach { k ->
            RowDivider()
            SettingRow(riskLabel(k)) {
                Segmented(GateOptions, p.byRisk[k] ?: Gate.Ask, { g -> onEdit { it.copy(byRisk = it.byRisk + (k to g)) } },
                    groupLabel = "${riskLabel(k)}: automático ou perguntar", modifier = Modifier.width(184.dp))
            }
        }
    }
    SettingsGroup {
        Text("Por ferramenta", style = AuraType.Caption, modifier = Modifier.padding(top = 6.dp))
        POLICY_KIND_KEYS.forEach { k ->
            RowDivider()
            SettingRow(kindLabel(k)) {
                Segmented(GateOptions, p.byKind[k] ?: Gate.Ask, { g -> onEdit { it.copy(byKind = it.byKind + (k to g)) } },
                    groupLabel = "${kindLabel(k)}: automático ou perguntar", modifier = Modifier.width(184.dp))
            }
        }
    }
}

@Composable
internal fun NoLimitsSection(
    p: PolicyDoc,
    rootAvailable: Boolean?,
    rootDetail: String?,
    onAllowAll: (Boolean) -> Unit,
    onEdit: ((PolicyDoc) -> PolicyDoc) -> Unit,
) {
    SectionLabel("Sem limites")
    SettingsGroup {
        SwitchRow(
            "Permitir tudo (inclui root)", checked = p.isAllowAll(), onChange = onAllowAll,
            subtitle = "O AURA faz sem perguntar. Você decide o que é seguro.",
        )
        RowDivider()
        SwitchRow(
            "Usar root (su, tsu)", checked = p.root.enabled,
            onChange = { on -> onEdit { it.copy(root = it.root.copy(enabled = on)) } },
        )
        when (rootAvailable) {
            true -> StatusChip(StatusKind.Ready, "Root detectado neste aparelho", Modifier.padding(bottom = 6.dp))
            else -> Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusChip(StatusKind.NeedsYou, "Só funciona em aparelho com root")
                Text(
                    rootDetail ?: if (rootAvailable == null) "O motor não informou se há root." else "Este aparelho não tem root.",
                    style = AuraType.SecondarySmall,
                )
            }
        }
    }
}

@Composable
internal fun SpendSection(
    p: PolicyDoc,
    spend: SpendResult?,
    resetKey: Any?,
    onEdit: ((PolicyDoc) -> PolicyDoc) -> Unit,
) {
    SectionLabel("Gastos")
    SettingsGroup {
        when (spend) {
            is SpendResult.Ok ->
                if (spend.summary.lines.isEmpty()) {
                    Text("O motor ainda não trouxe gastos.", style = AuraType.Secondary, modifier = Modifier.padding(vertical = 8.dp))
                } else spend.summary.lines.forEachIndexed { i, l ->
                    if (i > 0) RowDivider()
                    val name = if (l.model != null) "${l.provider} · ${l.model}" else l.provider
                    val today = l.todayUsd?.let(::formatUsd) ?: "não informado"
                    val month = l.monthUsd?.let(::formatUsd) ?: "não informado"
                    SettingRow(name, subtitle = "Hoje: $today · Mês: $month")
                }
            else -> Text(
                "Gastos indisponíveis agora. O motor não informou valores.",
                style = AuraType.Secondary, modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
    SettingsGroup {
        Text("Limites (opcional)", style = AuraType.Caption, modifier = Modifier.padding(top = 6.dp))
        MoneyField("Limite por tarefa", p.spendCap.perJob, resetKey) { v -> onEdit { it.copy(spendCap = it.spendCap.copy(perJob = v)) } }
        MoneyField("Limite por dia", p.spendCap.perDay, resetKey) { v -> onEdit { it.copy(spendCap = it.spendCap.copy(perDay = v)) } }
        MoneyField("Limite por mês", p.spendCap.perMonth, resetKey) { v -> onEdit { it.copy(spendCap = it.spendCap.copy(perMonth = v)) } }
        DraftField(
            label = "Avisar quando chegar em", initial = p.spendCap.alertPercent?.toString() ?: "", resetKey = resetKey,
            placeholder = "Sem alerta", suffix = "%", numeric = true,
            onText = { t ->
                val v = t.trim()
                if (v.isEmpty()) onEdit { it.copy(spendCap = it.spendCap.copy(alertPercent = null)) }
                else v.toIntOrNull()?.takeIf { it in 1..100 }?.let { n -> onEdit { it.copy(spendCap = it.spendCap.copy(alertPercent = n)) } }
            },
        )
        SettingRow("Ao atingir o limite") {
            Segmented(
                listOf("avisar" to "Avisar", "pausar" to "Pausar"), p.spendCap.onReach,
                { v -> onEdit { it.copy(spendCap = it.spendCap.copy(onReach = v)) } },
                groupLabel = "Ao atingir o limite: avisar ou pausar", modifier = Modifier.width(184.dp),
            )
        }
        Text(
            "Campo vazio = sem limite. O AURA nunca cria nem sobe limite sozinho.",
            style = AuraType.SecondarySmall, modifier = Modifier.padding(bottom = 6.dp),
        )
    }
}

@Composable
private fun MoneyField(label: String, value: Double?, resetKey: Any?, onValue: (Double?) -> Unit) {
    DraftField(
        label = label, initial = value?.let { String.format(PtBr, "%.2f", it) } ?: "", resetKey = resetKey,
        placeholder = "Sem limite", prefix = "US$", numeric = true,
        onText = { t -> if (t.isBlank()) onValue(null) else parseMoney(t)?.let(onValue) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelsSection(
    m: ModelsDoc,
    resetKey: Any?,
    lastUse: ModelUse?,
    onEdit: ((ModelsDoc) -> ModelsDoc) -> Unit,
) {
    SectionLabel("Modelos")
    SettingsGroup {
        Text("Bloqueados (nunca usar)", style = AuraType.Caption, modifier = Modifier.padding(top = 6.dp))
        if (m.deny.isEmpty()) {
            Text("Nenhum bloqueio.", style = AuraType.Secondary, modifier = Modifier.padding(vertical = 6.dp))
        } else FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            m.deny.forEach { d ->
                TinyAction("$d  ✕", "Remover bloqueio $d", { onEdit { it.copy(deny = it.deny - d) } }, danger = true)
            }
        }
        var newDeny by remember(resetKey) { mutableStateOf("") }
        DraftField("Bloquear modelo", "", "$resetKey/${m.deny.size}", { newDeny = it }, placeholder = "ex.: fable*")
        AuraButtonSecondary(
            "Adicionar bloqueio", enabled = newDeny.isNotBlank(),
            onClick = { val v = newDeny.trim(); onEdit { it.copy(deny = (it.deny + v).distinct()) }; newDeny = "" },
            modifier = Modifier.fillMaxWidth(),
        )
        RowDivider()
        Text("Teto por provedor", style = AuraType.Caption, modifier = Modifier.padding(top = 6.dp))
        m.ceiling.forEach { (provider, level) ->
            DraftField(
                "Teto de $provider (sonnet:medium = Sonnet médio)", level, resetKey,
                onText = { t -> onEdit { it.copy(ceiling = it.ceiling + (provider to t.trim())) } },
            )
        }
        RowDivider()
        Text("Rotas por tarefa", style = AuraType.Caption, modifier = Modifier.padding(top = 6.dp))
        m.routes.forEachIndexed { i, r ->
            DraftField(
                r.task.replace("|", ", ").replace("_", " "), r.use.joinToString(", "), resetKey,
                onText = { t ->
                    val use = t.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    onEdit { d -> d.copy(routes = d.routes.mapIndexed { j, x -> if (j == i) x.copy(use = use) else x }) }
                },
            )
        }
        RowDivider()
        SwitchRow(
            "Preferir assinatura", m.preferSubscription,
            { on -> onEdit { it.copy(preferSubscription = on) } },
            subtitle = "Usa a CLI logada antes de qualquer API paga.",
        )
        RowDivider()
        SettingRow("API paga") {
            Segmented(
                listOf("never" to "Nunca", "allow" to "Se preciso"), m.apiFallback,
                { v -> onEdit { it.copy(apiFallback = v) } },
                groupLabel = "API paga: nunca ou se preciso", modifier = Modifier.width(184.dp),
            )
        }
        if (lastUse != null) {
            RowDivider()
            Text(
                "Último trabalho: usei ${lastUse.model}" + (lastUse.why?.let { " — $it" } ?: ""),
                style = AuraType.SecondarySmall, modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
internal fun HistorySection(history: List<PolicyVersion>, currentVersion: Int?, onRevert: (PolicyVersion) -> Unit) {
    SectionLabel("Histórico de versões")
    SettingsGroup {
        if (history.isEmpty()) {
            Text("Nenhuma versão anterior.", style = AuraType.Secondary, modifier = Modifier.padding(vertical = 8.dp))
        }
        history.take(10).forEachIndexed { i, v ->
            if (i > 0) RowDivider()
            val whenText = SimpleDateFormat("dd/MM HH:mm", PtBr).format(Date(v.atMillis))
            val what = v.phrase?.let { "“$it”" } ?: "Editado em Ajustes"
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Versão ${v.version} · $whenText", style = AuraType.BodyStrong)
                    Text(what, style = AuraType.SecondarySmall)
                }
                if (v.version == currentVersion) {
                    StatusChip(StatusKind.Ready, "Atual")
                } else if (v.snapshot != null) {
                    AuraButtonText("Voltar para esta versão", { onRevert(v) },
                        contentDescription = "Voltar para a versão ${v.version}")
                }
            }
        }
    }
}
