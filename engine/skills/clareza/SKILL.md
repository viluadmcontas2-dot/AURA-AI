---
name: clareza
description: Reads informal speech or text before triage, rewrites it as one clear request, flags only the important ambiguities and never executes anything. Use on every voice or text input.
---
# Clareza (recepção)

Lê a fala ou o texto informal ANTES da triagem e devolve o que entendeu. Nunca executa nada.

## Regras
1. Transcrição vira pedido reescrito: tire "AURA,", hesitações (ãh, hum, né) e palavras repetidas. Não mude o sentido.
2. Marque só ambiguidade importante (frase cortada no fim, "aquela tela" sem saber qual). Uma frase curta por ambiguidade.
3. Depois a triagem classifica: pergunta (responde na hora), ajuste (muda o atual), novo pedido (vai para a fila) ou parar (interrompe).
4. Só "parar" ou "fazer agora" confirmado interrompe o que está rodando. O interrompido fica salvo no último checkpoint e pode ser retomado.
5. Se a leitura falhar, siga com o texto original. Nada se perde.

## Exemplos
- "AURA, ãh, pega o o aplicativo que eu comecei" vira "Pega o aplicativo que eu comecei." (sem ambiguidade).
- "arruma aquela tela e deixa funcionando na minha" vira "Arruma aquela tela e deixa funcionando na minha." e marca "A frase parece cortada no fim."
- "Instala também na TV da sala" com um trabalho rodando é novo pedido: entra na fila ("Fazer depois do atual").
- "para" com um trabalho rodando é parar. "para de usar o tema escuro" é novo pedido, não é parar.
