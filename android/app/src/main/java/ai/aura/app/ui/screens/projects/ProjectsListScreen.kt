package ai.aura.app.ui.screens.projects

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.components.AuraCard
import ai.aura.app.ui.components.AuraFilterChip
import ai.aura.app.ui.components.EmptyState
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType

/** Aba Projetos (Projetos.dc.html): filtros Agora/Triagem/Arquivo, cartões de projeto, artefatos recentes. */
@Composable
fun ProjectsListScreen(
    projects: List<Project>,
    jobs: Map<String, Job>,
    artifacts: List<Artifact>,
    loadError: String?,
    onRetry: () -> Unit,
    onNewProject: () -> Unit,
    onOpenProject: (String) -> Unit,
    onOpenArtifacts: () -> Unit,
    onOpenArtifact: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tabName by rememberSaveable { mutableStateOf("") } // vazio = o dono ainda não escolheu
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    val rows = projects.map { it to phaseOf(it, jobs) }
    val byTab = rows.groupBy { (p, ph) -> tabOf(p, ph) }
    // Sem escolha (ou nome desconhecido), abre na primeira aba que tem projeto: sem motor, nada fica escondido.
    val tab = ProjectTab.entries.firstOrNull { it.name == tabName }
        ?: ProjectTab.entries.firstOrNull { !byTab[it].isNullOrEmpty() } ?: ProjectTab.Now
    val shown = (byTab[tab] ?: emptyList()).filter { (p, _) -> query.isBlank() || p.name.contains(query.trim(), ignoreCase = true) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Projetos", style = AuraType.TabTitle)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (projects.isNotEmpty()) IconTap(PIcon.Search, "Buscar projetos", { searching = !searching; if (!searching) query = "" })
                IconTap(PIcon.Plus, "Novo projeto", onNewProject, dark = true)
            }
        }
        if (projects.isEmpty() && loadError == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "Nenhum projeto ainda",
                    body = "Conte sua ideia e o AURA organiza o resto.",
                    actionText = "Criar projeto",
                    onAction = onNewProject,
                )
            }
            return@Column
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (loadError != null) {
                NoteBanner(loadError, AuraColors.Risk, PIcon.Lock)
                AuraButtonSecondary("Tentar de novo", onRetry)
            }
            if (searching) SearchField(query, { query = it })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProjectTab.entries.forEach { t ->
                    val n = byTab[t]?.size ?: 0
                    AuraFilterChip("${t.label} $n", selected = t == tab, onClick = { tabName = t.name })
                }
            }
            if (shown.isEmpty()) {
                Text(
                    if (query.isNotBlank()) "Nenhum projeto com \"${query.trim()}\". Tente outro nome."
                    else "Nada em ${tab.label} agora.",
                    style = AuraType.Secondary, modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    shown.forEach { (p, ph) -> ProjectCard(p, ph, onClick = { onOpenProject(p.id) }) }
                }
            }
            if (artifacts.isNotEmpty()) RecentArtifacts(artifacts.take(6), onOpenArtifacts, onOpenArtifact)
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(AuraShapes.Pill).background(AuraColors.Surface)
            .border(1.dp, AuraColors.LineStrong, AuraShapes.Pill).padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = AuraType.Body, cursorBrush = SolidColor(AuraColors.Ink),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Buscar projetos pelo nome" },
            decorationBox = { inner ->
                Box { if (value.isEmpty()) Text("Buscar pelo nome", style = AuraType.Body.copy(color = AuraColors.Ink3)); inner() }
            },
        )
    }
}

@Composable
internal fun ProjectCard(project: Project, phase: ProjectPhase, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val (tint, icon) = categoryOf(project.name)
    AuraCard(modifier.fillMaxWidth(), onClick = onClick, contentDescription = "${project.name}, ${phase.label}. ${phase.subtitle}") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(48.dp).clip(AuraShapes.Chip).background(tint), contentAlignment = Alignment.Center) {
                PIconView(icon, size = 24.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(project.name, style = CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    PhaseChip(phase)
                }
                Text(phase.subtitle, style = AuraType.SecondarySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PIconView(PIcon.Branch, tint = AuraColors.Ink3, size = 12.dp)
                    Text("${project.repo} · ${project.branch}", style = AuraType.Caption.copy(color = AuraColors.Ink3, letterSpacing = 0.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun RecentArtifacts(items: List<Artifact>, onSeeAll: () -> Unit, onOpen: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Artefatos recentes", style = AuraType.Section)
            Row(
                Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onSeeAll),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Ver todos", style = AuraType.SecondarySmall.copy(color = AuraColors.Link, fontWeight = FontWeight.Bold))
                PIconView(PIcon.ChevronRight, tint = AuraColors.Link, size = 18.dp)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items.forEach { a ->
                AuraCard(Modifier.width(150.dp), onClick = { onOpen(a.id) }, contentDescription = "${a.name}, ${artifactLine(a)}") {
                    Box(Modifier.size(36.dp).clip(AuraShapes.Chip).background(kindTint(a.kind)), contentAlignment = Alignment.Center) {
                        PIconView(kindIcon(a.kind), size = 20.dp)
                    }
                    Text(a.name, style = AuraType.SecondarySmall.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(artifactLine(a), style = AuraType.Caption.copy(fontWeight = FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

internal fun artifactLine(a: Artifact): String = when {
    a.generating != null -> "Gerando agora"
    a.proofs.isNotEmpty() -> a.proofs.first().text
    else -> a.subtitle
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, heightDp = 780)
@Composable
private fun ProjectsListPreview() {
    AuraTheme { ProjectsListScreen(ExampleData.projects, ExampleData.jobMap, ExampleData.artifacts, null, {}, {}, {}, {}, {}) }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, heightDp = 600)
@Composable
private fun ProjectsListEmptyPreview() {
    AuraTheme { ProjectsListScreen(emptyList(), emptyMap(), emptyList(), null, {}, {}, {}, {}, {}) }
}
