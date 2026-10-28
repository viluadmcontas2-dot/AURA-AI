package ai.aura.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraSpacing
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType

private val ComposerShape = RoundedCornerShape(30.dp)

/**
 * Composer sem estado (valor vem de fora). Ancorado embaixo com imePadding: sobe com o teclado.
 * Texto nunca é apagado por rotação/recomposição; só é limpo por quem chama após enviar.
 * Enviar fica desabilitado com texto vazio. Altura 60 dp, raio 30, sombra suave.
 */
@Composable
fun AuraComposerField(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Peça algo ao AURA…",
    onMic: (() -> Unit)? = null,
    /** Não nulo enquanto o AURA responde: o botão de enviar vira "Parar resposta". */
    onStop: (() -> Unit)? = null,
) {
    val canSend = value.isNotBlank()
    val sendSource = remember { MutableInteractionSource() }
    Box(modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .shadow(10.dp, ComposerShape, clip = false, ambientColor = Color(0x1415141A), spotColor = Color(0x1415141A))
                .clip(ComposerShape)
                .background(AuraColors.Surface)
                .border(1.dp, AuraColors.Line, ComposerShape)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier.weight(1f).defaultMinSize(minHeight = AuraSpacing.TouchTarget).padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Mensagem para o AURA" },
                    textStyle = AuraType.Body.copy(fontSize = 17.sp),
                    cursorBrush = SolidColor(AuraColors.Ink),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                    decorationBox = { inner ->
                        Box {
                            if (value.isEmpty()) Text(placeholder, style = AuraType.Body.copy(fontSize = 17.sp, color = AuraColors.Ink3))
                            inner()
                        }
                    },
                )
            }
            if (onMic != null) {
                val micSource = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .size(AuraSpacing.TouchTarget)
                        .clip(AuraShapes.Circle)
                        .border(1.dp, AuraColors.Line, AuraShapes.Circle)
                        .auraFocusRing(micSource, AuraShapes.Circle)
                        .clickable(interactionSource = micSource, indication = null, role = Role.Button, onClick = onMic)
                        .semantics { contentDescription = "Falar um pedido pelo microfone" },
                    contentAlignment = Alignment.Center,
                ) { MicGlyph() }
            }
            if (onStop != null) {
                Box(
                    Modifier
                        .size(AuraSpacing.TouchTarget)
                        .clip(AuraShapes.Circle)
                        .background(AuraColors.Ink)
                        .auraFocusRing(sendSource, AuraShapes.Circle)
                        .clickable(interactionSource = sendSource, indication = null, role = Role.Button, onClick = onStop)
                        .semantics { contentDescription = "Parar resposta" },
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(Color.White)) }
            } else {
                Box(
                    Modifier
                        .size(AuraSpacing.TouchTarget)
                        .clip(AuraShapes.Circle)
                        .background(if (canSend) AuraColors.Ink else AuraColors.Line)
                        .auraFocusRing(sendSource, AuraShapes.Circle)
                        .clickable(
                            interactionSource = sendSource, indication = null, enabled = canSend,
                            role = Role.Button, onClick = onSend,
                        )
                        .semantics { contentDescription = "Enviar mensagem" },
                    contentAlignment = Alignment.Center,
                ) { Text("↑", style = AuraType.Section.copy(color = if (canSend) Color.White else AuraColors.Ink3)) }
            }
        }
    }
}

/** Microfone desenhado (sem emoji nem ícone externo). */
@Composable
private fun MicGlyph() {
    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
        val ink = AuraColors.Ink
        val w = size.width
        val h = size.height
        drawRoundRect(ink, topLeft = androidx.compose.ui.geometry.Offset(w * 0.36f, h * 0.08f),
            size = androidx.compose.ui.geometry.Size(w * 0.28f, h * 0.5f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.14f))
        drawArc(ink, 0f, 180f, false, topLeft = androidx.compose.ui.geometry.Offset(w * 0.22f, h * 0.3f),
            size = androidx.compose.ui.geometry.Size(w * 0.56f, h * 0.45f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(1.6.dp.toPx()))
        drawLine(ink, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.75f), androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.92f), 1.6.dp.toPx())
    }
}

/** Composer com texto guardado por rememberSaveable (sobrevive a rotação e à troca de aba). */
@Composable
fun AuraComposer(
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Peça algo ao AURA…",
) {
    var text by rememberSaveable { mutableStateOf("") }
    AuraComposerField(
        value = text,
        onValueChange = { text = it },
        onSend = { val t = text.trim(); if (t.isNotEmpty()) { onSend(t); text = "" } },
        modifier = modifier,
        placeholder = placeholder,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1)
@Composable
private fun ComposerPreview() {
    AuraTheme {
        Column {
            AuraComposerField("", {}, {})
            AuraComposerField("Corrigir tela de login", {}, {})
        }
    }
}
