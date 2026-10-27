# Design do AURA AI (UI e UX)

Fonte visual: canvas "AURA AI Interface" (claude.ai, artefato Design). Estes arquivos são a cópia versionada para o repositório ser a fonte de verdade.

- `telas/*.dc.html`: uma tela por arquivo. São HTML com estilos inline: leia valores exatos (cores, espaços, raios, tamanhos) direto do código. Telefone = 390x844.
- `canvas.json`: posição de cada tela no canvas e as notas de leitura.
- `tokens.json`: tokens para o tema Compose (cores, tipo, raios, espaço).
- Fluxo do usuário: `telas/Fluxo-do-usuario.dc.html`. Contrato Android↔Termux (proposta): `telas/Contrato.dc.html`. Progresso: `telas/Progresso.dc.html`.

## Regras que valem para o código
1. Um botão principal por tela (pílula preta). Texto do botão = verbo + objeto, igual à palavra do aviso.
2. Cor é significado: gradiente só quando o AURA está vivo; âmbar = precisa de você; vermelho = risco; verde = prova.
3. Resultado sempre em 4 blocos: Feito, Comprovado, Falta, Próximo passo.
4. Teclado: composer ancorado, barra inferior some, texto preservado (`adjustResize`/insets). O espaço do teclado nas telas é vazio de propósito.
5. Alvos de toque >= 44 dp, texto >= 12 sp, contraste >= 4.5:1, foco visível, respeitar "reduzir movimento".
6. Código e logs só nos Bastidores. Nada de terminal na tela principal.

## Limites conhecidos
- Textos de exemplo (Minhas Finanças, Vitor, números de teste) são ilustrativos.
- Nomes de intent do Termux no contrato vêm de memória: conferir na documentação oficial antes de implementar.
- Teste com pessoa real: não feito. Código do app: 0%.
- Antigravity CLI (agy): o instalador oficial tem build "android" para Termux; compatibilidade real no aparelho ainda não verificada.
