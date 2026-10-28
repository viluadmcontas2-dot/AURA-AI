package ai.aura.app.ui.screens.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.voice.LocalVoiceInput
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraSpacing
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType

/** Sugestões de toque (Projeto-Ideia.dc.html). Só preenchem o campo; o usuário ainda confirma. */
private val IdeaStarters = listOf("Controle de gastos", "Player de música para o carro", "Lista de tarefas")

/** Passo 1 de criar app: "Qual é a sua ideia?" (falar/escrever, exemplos, UM botão principal). */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun ProjectIdeaScreen(
    idea: String,
    onIdeaChange: (String) -> Unit,
    onOrganize: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var voiceHint by remember { mutableStateOf(false) }
    val voice = LocalVoiceInput.current
    val fieldFocus = remember { FocusRequester() }
    Column(modifier.fillMaxSize().imePadding()) {
        ProjHeader("Novo aplicativo", onBack, backDescription = "Voltar para Projetos")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column {
                Text("Qual é a sua ideia?", style = AuraType.Display.copy(fontSize = 34.sp, lineHeight = 38.sp))
                Text("Conte do seu jeito, em uma frase. Eu organizo o resto.", style = AuraType.Secondary.copy(fontSize = 16.sp, lineHeight = 23.sp), modifier = Modifier.padding(top = 8.dp))
            }
            Column(
                Modifier.fillMaxWidth().clip(AuraShapes.Card).background(AuraColors.Surface)
                    .border(1.dp, AuraColors.LineStrong, AuraShapes.Card).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
            ) {
                BasicTextField(
                    value = idea, onValueChange = onIdeaChange,
                    textStyle = AuraType.Body.copy(fontSize = 19.sp, lineHeight = 28.sp),
                    cursorBrush = SolidColor(AuraColors.Link),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).focusRequester(fieldFocus).semantics { contentDescription = "Sua ideia de aplicativo" },
                    decorationBox = { inner ->
                        Box {
                            if (idea.isEmpty()) Text("Ex.: um app para controlar meus gastos do mês", style = AuraType.Body.copy(fontSize = 19.sp, lineHeight = 28.sp, color = AuraColors.Ink3))
                            inner()
                        }
                    },
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Pode falar ou escrever", style = AuraType.SecondarySmall.copy(fontSize = 13.sp, lineHeight = 18.sp))
                    Box(
                        Modifier.heightIn(min = AuraSpacing.TouchTarget).clip(AuraShapes.Circle).background(AuraColors.SurfaceSunken)
                            .clickable(role = Role.Button) {
                                if (voice != null) {
                                    // Fala pelo reconhecimento do Android; o texto entra no campo e o usuário ainda confirma.
                                    voiceHint = false
                                    voice { spoken -> onIdeaChange(if (idea.isBlank()) spoken else "$idea $spoken") }
                                } else voiceHint = true
                            }
                            .semantics { contentDescription = "Falar a ideia" }
                            .padding(11.dp),
                        contentAlignment = Alignment.Center,
                    ) { PIconView(PIcon.Mic, size = 22.dp) }
                }
                if (voiceHint) Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Falar ainda não está disponível neste aparelho.", style = AuraType.SecondarySmall.copy(color = AuraColors.Risk.fg))
                    AuraButtonSecondary("Escrever a ideia", { voiceHint = false; fieldFocus.requestFocus() })
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Sem ideia ainda? Toque em uma")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    IdeaStarters.forEach { s ->
                        Box(
                            Modifier.heightIn(min = AuraSpacing.TouchTarget).clip(AuraShapes.Pill).background(AuraColors.Surface)
                                .border(1.dp, AuraColors.LineStrong, AuraShapes.Pill)
                                .clickable(role = Role.Button) { onIdeaChange(s) }.padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(s, style = AuraType.Button.copy(fontWeight = FontWeight.SemiBold)) }
                    }
                }
            }
            Text("Não precisa de detalhes. Se faltar algo importante, eu pergunto uma vez só.", style = AuraType.SecondarySmall)
        }
        Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp)) {
            AuraButtonPrimary("Organizar minha ideia", onOrganize, Modifier.fillMaxWidth(), enabled = idea.isNotBlank(), large = true)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, heightDp = 780)
@Composable
private fun IdeaPreview() {
    AuraTheme { ProjectIdeaScreen("Um app de finanças para eu controlar meus gastos e metas do mês", {}, {}, {}) }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, heightDp = 780)
@Composable
private fun IdeaEmptyPreview() {
    AuraTheme { ProjectIdeaScreen("", {}, {}, {}) }
}
