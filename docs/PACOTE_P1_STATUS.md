# Pacote P1 — Chat, sessões e motor de conversa (status)

## Feito e provado (testes verdes)
1. **Resposta nunca fica presa em "Pensando"**: a conversa mora no `ChatHub` (vive com a Activity, não com a tela). Um laço
   confere `GET /v1/sessions/{id}/state` a cada 2 s durante a resposta; o fluxo de eventos é só acelerador.
   Testes: `ChatLiveSyncTest` (fluxo morto, motor termina, resposta em até 5 s; sair para Sessões e voltar; "Pensando · N s",
   "Está demorando mais que o normal", "Perdi o contato com o motor"; Copiar diagnóstico sem texto de mensagem).
2. **Sincronização viva**: motor com `/state`, `/messages` paginado (`before`/`after`, ids estáveis, `source_partial`,
   409 `history_changed`), `POST /sessions/{id}/watch` + evento `session.updated`, arquivo >8 MiB lido inteiro, alias de CLI,
   origem "terminal" preservada. App: cache **por conversa** (`chat-h-<id>`), A→B→A completo, sem duplicar em 20 ciclos,
   ON_RESUME, "O Claude está respondendo no terminal…". Testes: `engine/tests/test_sessions.py`, `ChatLiveSyncTest`.
3. **Fila de mensagens**: Enviar nunca bloqueia, Parar é botão separado, "Na fila" + Enviar agora/Cancelar, Parar pausa
   (Continuar/Limpar fila), fila no disco (`chat-q-<id>`) unificada com "aguardando motor", uma por vez, campo opcional
   `executor` por mensagem. Testes: `ChatControllerTest`.
4. **Motor (C1)**: `request_id` idempotente (409 `request_conflict`, consulta por request_id), `chat.status` a cada 5 s com
   fase/seq/elapsed, parada confirmada (202 `stopping` → `interrupted`, SIGKILL após prazo), `resume_failed` e
   `session_not_found` específicos (nunca recomeça conversa escondido), chat sempre do Claude (sem Gemini/agy).
   Testes: `engine/tests/test_chat.py` (19), `test_sessions.py`, `test_primeiro_uso.py` (PU4/PU5 reescritos para o novo contrato).

## NÃO ficou (por falta de tempo)
- **Pílula "Mensagens novas ↓"**: código pronto (`ChatScreen`), mas o teste `lendo mensagens antigas...` está `@Ignore`
  (não consegui provar a rolagem no Robolectric). PENDENTE.
- **Voz inline (item 5)**: não feito. O caminho antigo de triagem/autoenvio continua nas telas de voz.
- **Lista de Sessões** com atualização sozinha, "Atualizada agora" e ponto de novidade: não feito (SessionsScreen intacta).
- **"Criar conversa" explícito na tela Sessões** (`ChatHub.startNewConversation` existe, falta ligar o botão).
- Teste de motor real (E2E) para `.jsonl` alterado por fora: coberto só no motor (`test_sessions.py`) e no fake do app.
- Parar com confirmação = confirmação do motor ("Pedindo para parar…"); não há diálogo de confirmação.

## Observações
- Mudei testes antigos de P1 porque o contrato mudou: cache por conversa (AbasTest lê `chat-h-sess_1`), fila uma por vez,
  conversa sumida não recomeça sozinha, "Pensando · N s".
- Instalações feitas por comandos que o Claude decide rodar não têm mediação possível hoje (decisão 3): sem garantia.
