package ai.aura.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType

/** Os 4 vazios de Vazios.dc.html: cor da ilustração, título, uma frase e UM botão (verbo + objeto). */
enum class EmptyKind(val title: String, val body: String, val action: String, internal val tint: Color) {
    Projects(
        "Nenhum projeto ainda", "Um projeto guarda o código, os aparelhos e o histórico de um app.",
        "Criar primeiro projeto", AuraColors.CategoryEngineering,
    ),
    Devices(
        "Nenhum aparelho conectado", "Conecte o celular, a TV ou a multimídia para o AURA controlar.",
        "Conectar um aparelho", AuraColors.CategoryAndroid,
    ),
    Activity(
        "Nada rodando agora", "Quando o AURA estiver trabalhando, cada passo aparece aqui com a prova.",
        "Pedir algo ao AURA", AuraColors.CategoryLilac,
    ),
    Artifacts(
        "Seus APKs e relatórios aparecem aqui", "Tudo que o AURA gera fica guardado, com as versões anteriores.",
        "Criar um aplicativo", AuraColors.CategoryApricot,
    ),
}

/** Vazio pronto do design: `EmptyState(EmptyKind.Projects, ::criarProjeto)`. */
@Composable
fun EmptyState(kind: EmptyKind, onAction: () -> Unit, modifier: Modifier = Modifier) {
    EmptyState(kind.title, kind.body, kind.action, onAction, modifier, tint = kind.tint)
}

/**
 * Tela vazia que convida a agir: ilustração de 148 dp, título curto, uma frase, UM botão principal (verbo + objeto).
 * Ex.: EmptyState("Nenhum projeto ainda", "Crie o primeiro a partir de uma ideia.", "Criar projeto", ::criar).
 * shortcut: a ilustração é um bloco colorido com um símbolo, não o desenho vetorial do design; trocar quando o dono pedir.
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    actionText: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: String = "✦",
    tint: Color = AuraColors.CategoryLilac,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.widthIn(max = 280.dp).fillMaxWidth()
                .clip(AuraShapes.Card).background(AuraColors.Surface).border(1.dp, AuraColors.Line, AuraShapes.Card)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.testTag("empty-illustration").fillMaxWidth().height(148.dp)
                    .clip(RoundedCornerShape(20.dp)).background(tint).clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) { Text(glyph, style = AuraType.Display) }
            Text(title, style = AuraType.Section, textAlign = TextAlign.Center)
            Text(body, style = AuraType.Secondary, textAlign = TextAlign.Center)
            AuraButtonPrimary(actionText, onAction)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1)
@Composable
private fun EmptyStatePreview() {
    AuraTheme { EmptyState(EmptyKind.Projects, {}) }
}
