# Voz e fila de pedidos

Telas: `telas/Voz-1..4-*.dc.html`. Eventos: `telas/Contrato.dc.html`.

1. Voz em tela cheia só abre por toque explícito (orbe do Início ou botão "Abrir voz").
2. Dentro de uma tarefa a voz fica compacta no composer (orbe mini + "Ouvindo"). Ouvir nunca interrompe o trabalho.
3. Toda fala ou texto vira uma entrada classificada: pergunta (responde na hora), ajuste (muda o atual), novo pedido (vai para a fila), parar (interrompe).
4. Só "parar" ou "fazer agora" confirmado interrompe. O interrompido fica salvo no último checkpoint e pode ser retomado.
5. Padrão do app: aplicar a recomendação sozinho em 5 s, com botão "Mudar" (menos atrito).
6. Android: microfone em segundo plano exige serviço em primeiro plano iniciado por ação do usuário. Padrão: apertar para falar; mãos livres é opcional (modo carro).
