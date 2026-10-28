# Fluxo da mensagem (decisão de arquitetura)

O app é uma ponte: **chat/voz → motor no Termux → Claude Code → resposta no mesmo chat**.

## Quem interpreta?

| Passo | Quem faz | Custo |
|---|---|---|
| 1. Entrada | Teclado ou **voz do Google** (SpeechRecognizer/RecognizerIntent). A voz só preenche o campo de texto: é método de entrada, não um fluxo separado. | zero |
| 2. Comando do app (`/projeto`, `/reformar`, `/conexoes`…) e pedidos de estado ("bateria da TV") | **Regras locais** no app/motor (determinístico). Nunca vai à IA. | zero |
| 3. Qualquer outra mensagem | Vai **direto ao Claude Code** (`claude -p … --resume`). O Claude é a inteligência: ele entende, planeja e executa. Não existe um "intérprete" na frente dele. | assinatura Max |
| 4. Resposta | O motor lê o stream do Claude e entrega **só o texto** (deltas, frases de ferramenta em português, texto final) ao mesmo chat. | zero |
| 5. Sem Claude (não instalado/sem login/limite) | Motor tenta Gemini CLI → agy → erro com ação ("Abrir Conexões"). | assinatura |

## O que NÃO existe de propósito
- Gemini API (ou qualquer modelo) como pré-classificador: custa, atrasa e é mais uma coisa que falha. O Claude já interpreta.
- Cartão "Entendi/Começar", aprovação, jobs, "por quê" visíveis ao usuário. Aprovação só nos modos `ask_*` e só para ação destrutiva, em português simples.
- JSON cru da CLI na tela, nunca.

## Entregas (ordem do plano mestre)
1. Chat + teclado + navegação (app).
2. **Ponte Termux real**: mensagem enviada → resposta do Claude no mesmo chat, comprovada por teste que sobe o motor de verdade.
3. Intenções + voz + roteamento econômico.
4. Dispositivos/projetos/GitHub/skills.
5. Autonomia e acabamento.

Regra: nenhuma entrega seguinte começa sem a anterior provada ponta a ponta.
