# Requisito do dono: escolher quem responde (Antigravity, Gemini ou Claude Code)

Pedido: usar ao máximo o Antigravity CLI (e o Gemini) para executar o trabalho; o Claude Code só quando o dono quiser, para não gastar o limite dele. Poder escolher isso direto no chat e trocar a qualquer hora, conforme o contexto.

## Escolha no chat
- Seletor discreto no campo de mensagem: "Quem responde: Automático ▾". Opções: **Automático** (Antigravity, depois Gemini, depois Claude Code), **Antigravity**, **Gemini**, **Claude Code**.
- Vale para a PRÓXIMA mensagem e vira o padrão da conversa; trocar a qualquer momento (inclusive no meio da fila de mensagens: cada mensagem enfileirada guarda o assistente escolhido).
- Padrão geral em Ajustes ("Assistente padrão"). Valor inicial: Automático.
- Toda resposta mostra, pequeno, quem respondeu ("Antigravity", "Claude Code"). Em cada resposta: ação **"Refazer com outro assistente"** (reenvia o mesmo pedido a outro executor).
- Limite atingido (o motor já distingue erro de limite/uso): "O Claude Code atingiu o limite. Continuar com o Antigravity?" [Trocar e enviar] [Esperar]. Nunca troca sozinho em silêncio no modo escolhido manualmente; só o modo Automático encadeia, e mostra quem respondeu.
- Conexões mostra por assistente: instalado / entrou na conta / testado ("Antigravity: pronto"), com a ação certa. Escolher um assistente não instalado ou sem login: mensagem em PT-BR com botão ("Instalar" passa pelo consentimento, "Entrar").

## Continuidade ao trocar (decisão de projeto)
Cada CLI guarda a sua própria memória (session id próprio). A conversa do AURA é UMA (um id), com um mapa `executor -> session da CLI`. Ao trocar de assistente no meio da conversa, a primeira mensagem para o novo assistente leva um resumo compacto do que já foi dito (últimas ~20 mensagens, texto puro, sem ferramentas), marcado como contexto. Dizer ao dono, uma vez, em Detalhes: "Cada assistente lembra do que ele mesmo fez; o AURA passa um resumo ao trocar." Não prometer memória idêntica.

## Motor
- `POST /v1/chat` já aceita `executor` (auto|claude|gemini|agy). Acrescentar: `executor` por mensagem na fila; mapa por conversa `cli_sessions`; contexto de troca; evento/campo `executor` em chat.done e nas mensagens (`GET .../messages`) para a etiqueta "quem respondeu".
- Streaming: Claude tem stream-json. agy e gemini podem só devolver o resultado no fim (agy `-p --output-format json`); o app mostra "Pensando · N s" com o heartbeat e o texto inteiro ao terminar. Se o Gemini CLI suportar `--output-format stream-json`, usar.
- Autonomia total mantida (flags já existem em adapters.run_cli). Erro de limite por executor: código `usage_limit` com mensagem PT-BR e `action_pt`.
- Detecção de "pronto" por assistente: `GET /v1/assistants` -> [{id, name, installed, signed_in, verified, last_check}], com `verified` só depois de uma chamada real de teste ("responda ok").

## Risco honesto (não esconder)
- O `agy -p` sem tela NUNCA foi provado aqui (o instalador é bloqueado neste ambiente da nuvem); há relato de travar sem TTY e de exigir arquivo de permissões. O Gemini CLI no Termux também nunca foi provado. Os testes do motor usam CLIs falsas. Só o celular do dono prova de verdade.
- Por isso: "Automático" nunca pode ficar mudo; se o Antigravity falhar ou demorar, mensagem clara e um toque para "Refazer com Claude Code".
- Valor de produto: se agy/Gemini não funcionarem no Termux, o seletor continua útil entre os que funcionam.

## Critérios de aceitação
(a) escolher Antigravity envia `executor:"agy"` e a resposta traz a etiqueta "Antigravity"; (b) trocar no meio da conversa leva o resumo ao novo assistente e mantém um só histórico; (c) mensagens enfileiradas guardam o assistente escolhido; (d) assistente não instalado/sem login mostra ação correta e NÃO envia; (e) erro de limite do Claude oferece [Trocar e enviar]; (f) "Refazer com outro assistente" reenvia sem duplicar a bolha do usuário; (g) Automático encadeia na ordem e mostra quem respondeu; (h) Conexões mostra "pronto" só com verificação real.
