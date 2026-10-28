# AURA Engine interface contract v1

Device-local engine process in Termux. Loopback-only HTTP, default `127.0.0.1:8765`. No externally bound address. Android launcher connects with Termux RUN_COMMAND integration requiring user-granted `com.termux.permission.RUN_COMMAND` and `allow-external-apps=true`. A random 256-bit token exists at `~/.aura/engine/token` mode 0600; ALL engine endpoints require `Authorization: Bearer <token>`. A future Android-native handshake must securely transfer this token; do not copy it into screenshots or logs.

## JSON commands
POST /v1/route
Body: `{"text":"...","workspace":"default","preferred_provider":"auto"}`. Responds with typed `request`, `reason`, `confidence` and potential `requires_model`. No job is started.

POST /v1/jobs
Body: `{"request_id":"unique-uuid","request":{"kind":"claude_cli","prompt":"...","workspace":"default"}}`.
Kind enum: `system`, `shell`, `adb`, `files`, `github`, `claude_cli`, `gemini_cli`, `agy_cli`, `claude_api`, `gemini_api`, `skills`, `rea`.
Response: `{"id":"...","status":"queued|awaiting_approval|running|completed|failed|cancelled|interrupted","risk":"read|write|spend|critical",...}`.
Identical `request_id` returns same job. Different payload for the same id -> conflict.

GET /v1/jobs?limit=50, GET /v1/jobs/{id}, POST /v1/jobs/{id}/approve, POST /v1/jobs/{id}/cancel.
Jobs with side effects require approval by an authenticated user action. Each approval is tied to immutable stored payload. Read-only commands may run automatically. Approval is not inherited by spawned jobs.

GET /v1/events?since=0: durable ordered events with IDs, type, payload, timestamp. Clients poll by `since`; no lost progress on Activity page restart.
GET /v1/capabilities: real installed tools and API-key presence (never actual credentials). Missing providers explicit.
GET /v1/skills: scanned local SKILL.md manifests, never executed automatically merely by enumeration.
POST /v1/memory, GET /v1/memory?workspace=...&q=..., DELETE /v1/memory/{id}, DELETE /v1/memory: scoped durable notes with a type (`kind`) and an origin (`source`); forget one or forget all (see "Memoria"). No secret storage.
POST /v1/schedules, GET /v1/schedules: scheduled commands, no automatic bypass of risk approval.
GET /v1/health: status and schema version.

## Request semantics
- `system`: capabilities, pwd, health.
- `shell`: argv list and optional workspace; explicit approval mandatory. Reject relative binary/executable paths and `shell=True`.
- `adb`: devices, getprop, screenshot, connect, shell, install; write/risky actions require approval; inspect actual Android-tool availability.
- `files`: list, read, write, delete, mkdir under allowed project workspace; traversal/symlink escape denied.
- `github`: status, list, clone, fetch, commit, push, create_repo, delete_repo via `gh` / `git`; login via official interactive `gh auth login` outside engine, never password API.
- CLI: `claude -p` and `gemini -p` in workspace, no transcript scraping, output/usage parsed when supported.
- API: official HTTPS Messages and Gemini generateContent endpoints, environment credentials only, provider charges surfaced.
- `skills`: inventory and load manifest and permitted instructions. Execution delegated through explicitly consented CLI job.

## Android UI rendering
Use `status`, `risk`, `result`, `error.code`, `events` to paint the interface. Never parse terminal output to guess progress or permission. No work succeeds on process launch alone.

## Failures and recovery
- Process crash: persisted running jobs become interrupted on restart; do NOT automatically replay side effects.
- Network/provider offline, expired credentials and missing executable: distinct errors and actionable next step.
- Cancellation: best effort terminates subprocess and persists cancelled. A provider response arriving late must not overwrite cancelled status.
- Secrets: redacted before event persistence where known; no stdout of login/auth commands.

## Additional verified protocol interfaces
GET `/v1/usage`: aggregate actually reported model input/output/cache tokens, with no guessed money values.
Request kind `drive`: `list`, `download`, `upload` against a user-configured rclone remote. Upload needs critical approval.
The engine uses a bounded parallel worker pool, default 2 and max 4. Cancelling a job targets its own process only.
One-time Android pairing runs `python -m aura_engine.pair --emit-token` **only** through the user-authorised Termux RUN_COMMAND result channel. The token must be stored privately in the Android application; plain `python -m aura_engine.pair` never prints it.


## Login, politica, modelos e gasto (lote A)
Todos os endpoints abaixo exigem o Bearer token, como os demais. Estado gravado em `~/.aura/engine/` (modo 0600, escrita atomica). Nenhuma resposta, evento ou erro devolve token, cookie ou caminho de credencial.

### Login (auth)
GET `/v1/auth/status` -> `{"providers":[{provider,state,installable?,detail?}]}` para `claude, gemini, agy, codex, github, google` (alias de entrada `gdrive` so em `/v1/auth/start`). `state`: `conectado|desconectado|expirado|indisponivel`. Revalidado a cada chamada, sem cache. `indisponivel` traz `installable:true` e `detail` (falta o programa). `gemini` e `agy` nao tem comando de status confiavel: so aparecem `conectado` depois de um login feito por este motor (`detail` explica); `google` vale `conectado` se existir o remote `gdrive:` no rclone.
POST `/v1/auth/start {"provider":"..."}` roda o login oficial do CLI em modo codigo/link (nunca senha, nunca OAuth proprio) e responde `{provider,state:"em_andamento",prompt?}`. CLI ausente -> `{provider,state:"indisponivel",fix:"Instalar"}` (e evento `auth.failed` com `reason:"cli_ausente"`). Provedor desconhecido -> 400. Idempotente por provedor: chamar de novo durante o login devolve o mesmo `em_andamento` com o `prompt` ja emitido. Timeout de 5 min.
Eventos, na ordem:
- `auth.prompt {provider,url,code?}`: link https (ou loopback http no rclone) e codigo curto, quando o CLI mostra.
- `auth.done {provider}`: login concluido. Para `claude, codex, github, google` so sai depois de reconfirmar `conectado`; senao vira `auth.failed` com `nao_confirmado`.
- `auth.failed {provider,reason,fix}`: `reason` em `cli_ausente|sem_internet|codigo_recusado|tempo_esgotado|cancelado|nao_confirmado|falhou`; `fix` e texto pronto em portugues para a tela.
Quando o login termina (ok ou falha), o codigo de uso unico some dos `auth.prompt` ja gravados e o link perde query e fragmento; leia o `auth.prompt` enquanto o login esta em andamento.

### Politica de aprovacao
GET `/v1/policy` -> documento atual. Campos: `version` (int), `mode` (`pergunta|equilibrado|autonomo|total`), `by_risk` (`read|write|spend|critical` -> `auto|ask`), `by_kind` (categoria `[a-z_]{1,30}` -> `auto|ask`, ate 40), `root {enabled,via:["su","tsu"]}`, `scopes [{path,write:"auto|ask"}]` (ate 200), `spend_cap {per_job,per_day,per_month,on_reach}` (numero USD >= 0 ou `null`; `on_reach`: `avisar|pausar`), `auto_countdown_seconds` (inteiro 0 a 3600), `notify` (`so_se_precisar|sempre|nunca`). Padrao (versao 1): `mode:"autonomo"`, `read` auto, demais `ask`, root desligado, sem limites, `notify:"so_se_precisar"`. Arquivo ausente ou invalido volta ao padrao.
PUT `/v1/policy` aceita campos parciais; em `by_risk`, `by_kind`, `root` e `spend_cap` o objeto enviado e mesclado com o atual. Responde o documento novo com `version+1`. `version` no corpo, se enviado, deve ser a atual, senao 409 (`error.code:"conflict"`): leia de novo antes de salvar. Valor fora do enum, campo desconhecido ou tipo errado -> 400 (`error.code:"invalid_request"`), sem gravar nada; a mensagem nao repete o valor recebido. Evento `policy.changed {version,changed,by,mode,restored_from?}`: `changed` lista os campos de topo alterados; `by` e sempre `"user"`.
GET `/v1/policy/history?limit=50` -> `{"current":N,"history":[{version,parent,at,changed,mode,by,restored_from}]}`, em ordem crescente de versao, `limit` de 1 a 500 (guarda as ultimas 500). `at` e epoch em segundos; `null` e `by:"sistema"` para a versao-base anterior ao primeiro PUT. `parent` e a versao de onde esta nasceu (no desfazer, o pai da versao restaurada). `restored_from` so existe em versoes criadas por desfazer. So a politica tem historico (veja "Nao aplicado ainda").
POST `/v1/policy/undo {"to_version"?}` restaura o conteudo de uma versao anterior como uma NOVA versao (`version+1`; o historico nunca e reescrito) e responde o documento novo; evento `policy.changed` com `restored_from`. Sem `to_version`, volta ao `parent` da versao atual. 400: nada para desfazer, `to_version` nao inteiro, igual a atual, maior que a atual ou ausente/corrompida no historico.

### Decisao de aprovacao por job
A politica e relida a cada POST `/v1/jobs`. O evento `job.created` traz `{id,status,risk,policy_version,decision,why}`: `decision` e `auto` (vai para `queued`) ou `ask` (fica `awaiting_approval`); `why` e a frase pronta para a tela (ex.: "política v3 (autonomo): risco write pede aprovação"); `policy_version` e a versao usada naquele job. A decisao e gravada na criacao: mudar a politica depois nao altera jobs ja criados, e `POST /v1/jobs/{id}/approve` continua sendo a unica forma de liberar um job `awaiting_approval`.
Regras: `total` -> tudo `auto`. `pergunta` -> so `read` e `auto`. `equilibrado` -> `spend` e `critical` sempre `ask`; demais seguem `by_risk`. `autonomo` -> segue `by_risk`; se o risco nao for `read`, `by_kind[kind]` diferente de `auto` tambem pede (kind sem entrada vale `auto`).
`kind` `root` ou `argv[0]` `su`/`tsu` em `shell`/`adb`: com `root.enabled:false` ou comando fora de `root.via`, o POST e recusado com 400 (`"root não permitido pela política"`) e nenhum job e criado. Com root ligado, entra no fluxo acima e `by_kind.root` tambem vale.
Jobs de modelo sao recusados no POST com 400 se `model` estiver em `deny` ou acima do teto (ver Modelos).

### Modelos
GET `/v1/models` -> documento atual. Campos: `version`, `deny` (padroes; ate 100), `ceiling` (provedor -> `familia:nivel`, ex. `{"claude":"sonnet:medium"}`), `routes [{task,use:[...]}]` (ate 100; `task` aceita alternativas `a|b`; `use` e lista ordenada de ate 20 modelos como `haiku:medium`, `codex_cli`, `subagente:haiku:medium`), `prefer_subscription` (bool), `api_fallback` (`never|ask`). Padrao: `deny:["fable*"]`, teto `claude: sonnet:medium`, rotas de triagem, codigo mecanico, UI/planejar e pesquisa ampla, `prefer_subscription:true`, `api_fallback:"never"`.
PUT `/v1/models`: mesmas regras do PUT da politica (parcial, `version+1`, `version` velha -> 409, invalido -> 400 sem gravar). Aqui os campos de topo sao substituidos por inteiro (sem mesclar). Travas: `deny` precisa conter `fable*` (Fable nunca e permitido); `ceiling.claude` ate `sonnet:medium` (`opus` ou nivel `high` no teto -> 400); `ceiling` e `routes.use` nao podem citar modelo ja negado nem modelo Claude acima do teto. Evento `models.changed {version,changed,by}`.
Relido a cada job. No POST `/v1/jobs`, `claude_cli`, `claude_api`, `gemini_cli`, `gemini_api`, `agy_cli` e `skills/run` (que delega ao CLI de `provider`) com `model` negado ou acima do teto Claude -> 400 antes de criar o job. Para `claude_api`, o modelo padrao do ambiente tambem e checado.
Escolha do modelo ao rodar (so `claude_cli` sem `model`): `task` do pedido casa uma rota -> usa o primeiro item `haiku|sonnet|opus` que cabe no teto e nao e negado; rota sem item aplicavel segue sem trocar modelo. Sem rota, usa a familia do teto (nunca o padrao do CLI). Os outros provedores usam o padrao deles: `model_used:"não informado"`. Se a checagem falhar de novo na execucao (models.json mudou entre criar e rodar), o job termina `failed` com `error.code:"model_denied"`.
Evento `job.model {id,model_used,why}` emitido quando o job comeca a rodar, antes do adaptador; `why` e frase pronta em portugues.

### Gasto
GET `/v1/spend` -> `{"today":W,"month":W}` com `W = {date,providers,total_usd,unreported_jobs}`. `date` e `AAAA-MM-DD` (hoje) ou `AAAA-MM` (mes), no fuso do aparelho. `providers.<p>` (`claude|gemini|agy`) -> `{jobs,cost_usd,unreported_jobs,models:{<m>:{jobs,cost_usd,unreported_jobs}}}`. Conta jobs `completed` de provedores de modelo. So soma o que o provedor informou (`total_cost_usd` ou `cost_usd`); sem custo informado, `cost_usd` e `total_usd` ficam `"não informado"` (texto, nao zero) e o job entra em `unreported_jobs`. Modelo desconhecido aparece como `"não informado"`. Nunca estima dinheiro.
Limites (`policy.spend_cap`), so para jobs de modelo, nunca alteram a politica:
- Evento `spend.cap_reached {scope,cap,spent,action,phase,...}`: `scope` `per_job|per_day|per_month`; `action` copia `on_reach`.
- `phase:"antes"` (no POST do job; traz `job_id` e `kind`): `per_job` dispara se o pedido declara `max_cost_usd` maior que o limite; `per_day`/`per_month` disparam se o gasto informado ja chegou ao limite. Com `on_reach:"pausar"` o job vai a `awaiting_approval` (`job.created` com `decision:"ask"`, `why:"limite de gasto atingido (pausar)"`) e o job ganha `note`; com `avisar` o job segue e so o evento avisa.
- `phase:"depois"` (traz `job_id`): emitido quando o custo de um job concluido faz o gasto cruzar o limite; nao muda o job.

### GET /v1/jobs: campo note
Cada job (em `GET /v1/jobs`, `GET /v1/jobs/{id}` e na resposta do POST) tem `note`: `null` ou frase pronta para a tela. Hoje so e preenchida quando um limite de gasto pausou o job para aprovacao (texto: limite por tarefa/dia/mes atingido, gasto X de Y, aprovar so se quiser continuar). Nao e gravada quando o limite so avisa.

### POST /v1/route: model_used e why
Alem de `request`, `reason` e `requires_model` (o codigo atual nao devolve `confidence`, apesar do texto acima), quando a rota exige modelo a resposta traz `provider_installed_or_configured` e, se houver escolha a mostrar, `model_used` e `why` (mesma logica de "Escolha do modelo", sem criar job). `model_used` e a familia escolhida ou `"não informado"`; `why` explica em portugues. Com `preferred_provider` `claude_api`/`gemini_api`, `prefer_subscription:true`, `api_fallback:"never"` e o CLI irmao instalado, a rota troca para `claude_cli`/`gemini_cli` e `why` comeca com "assinatura primeiro". `model_used` e `why` podem faltar (rotas deterministicas sem modelo, ou pedido cujo modelo seria recusado).

### Memoria: tipo, origem, esquecer uma, esquecer tudo
Todas as rotas exigem o Bearer token (401 sem ele). Uma lembranca vive em um `workspace` (padrao `default`) e tem uma `key` unica dentro dele. Fica na tabela `memory` do SQLite do motor (`engine.sqlite3`).

GET `/v1/memory?workspace=default&q=` -> `{"entries":[ITEM, ...]}`. No maximo 50 itens, da ultima gravacao para a mais antiga (`updated`); `q` filtra por trecho de `key` ou `value`. Cada ITEM:
`{"id":"0b6f3c1e-8a52-4d0e-9c1a-5f2b7e4d9a10","kind":"preference","text":"Respostas curtas, em portugues simples","source":"user","createdAt":1730000000.5,"key":"estilo","value":"Respostas curtas, em portugues simples","updated":1730000000.5}`
- `id` (texto): unico e estavel (UUID, 36 caracteres: letras minusculas, digitos e `-`; cabe em `[a-zA-Z0-9_-]{1,100}`, o que o app aceita na URL). Nao muda ao atualizar a lembranca nem ao reiniciar o motor.
- `kind` (texto): `preference|project|device|rule`. Sem informar, vale `preference`.
- `text` (texto): a lembranca, para mostrar na tela. E o mesmo texto de `value`.
- `source` (texto, nunca `null`): de onde veio a lembranca, 1 a 60 caracteres, texto livre que o motor nao valida nem interpreta. Valores que o app usa: `user` (o dono pediu ou escreveu), `repository` (aprendida lendo o repositorio), `device` (aprendida ao conectar o aparelho). A tela mapeia para a frase de origem: `user` -> "você disse em <data de `createdAt`>" (com `kind` `rule`: "você definiu"), `repository` -> "aprendido no repositório", `device` -> "aprendido ao conectar"; valor fora da lista: mostra o texto cru. Sem informar, vale `user`.
- `createdAt` (numero): epoch em segundos, com fracao, UTC, como `created_at` dos jobs. Quando a lembranca nasceu; nao muda ao atualizar.
- `key`, `value`, `updated`: campos de antes, com os mesmos valores (`updated` = ultima gravacao). Codigo novo usa `id`, `kind`, `text`, `source` e `createdAt`.

POST `/v1/memory` `{"workspace"?,"key","value","kind"?,"source"?}` -> 200 `{"stored":true}`. `key` (1 a 120 caracteres) e `value` (ate 50000) sao obrigatorios, como antes. `kind` e `source` sao opcionais (ausentes ou `null` = padrao). Gravar de novo o mesmo `workspace`+`key` atualiza no lugar: mesmo `id`, mesmo `createdAt`; `kind` e `source` omitidos ficam como estavam (nao voltam ao padrao). Nao devolve o `id`: leia pelo GET.

DELETE `/v1/memory/{id}` -> 204, sem corpo e sem `Content-Length` (nao leia JSON da resposta). Se nao existe (id errado ou ja esquecida; repetir a chamada da 404) -> 404 `{"error":{"code":"not_found","message":"Lembrança não encontrada. Talvez já tenha sido esquecida."}}`. A mensagem nao repete o id.

DELETE `/v1/memory` -> 200 `{"deleted":N}`: esquece TODAS as lembrancas de TODOS os workspaces (nao so as 50 da lista) e diz quantas; `{"deleted":0}` se ja estava vazio (nao e erro). `?workspace=nome` limita a esse workspace. So o caminho exato apaga tudo: `/v1/memory/` (barra no fim, id vazio) e `/v1/memory;x` respondem 404 e nao apagam nada. Query desconhecida, `workspace` vazio ou invalido -> 400 `invalid_request`, sem apagar nada. Nao ha desfazer.

Os dois DELETE apagam de verdade: o texto sai da lista e tambem dos arquivos do motor (o banco zera o conteudo apagado e o log `-wal` e esvaziado em seguida; se outro programa estiver lendo o banco naquele instante, o log so esvazia no proximo checkpoint). So vale para esquecer: trocar o texto de uma lembranca (POST na mesma `key`) pode deixar o texto antigo no log ate o proximo checkpoint.

Compatibilidade: um motor anterior a esta versao guardava a tabela sem `id`, `kind`, `source` e `created`. Ao iniciar, o motor completa cada linha numa transacao so (tudo ou nada): `id` novo, `kind:"preference"`, `source:"user"`, `createdAt` igual a `updated` (a data da ultima gravacao era a unica que existia). Nenhuma lembranca se perde e os ids ficam os mesmos nos proximos inicios. Se a conversao falhar, a base fica como estava e o motor nao inicia.
Nao existe: filtro por `kind` no GET (a tela filtra), evento no log de eventos, desfazer.

### Codigos de erro novos
- 400 `invalid_request`: enum invalido, campo desconhecido ou tipo errado em `/v1/policy` e `/v1/models`; `provider` invalido em `/v1/auth/start`; `to_version` invalido ou nada para desfazer; root nao permitido pela politica; modelo negado ou acima do teto no POST do job; `kind` fora de `preference|project|device|rule` ou `source` que nao e texto de 1 a 60 caracteres no POST de `/v1/memory`; `workspace` vazio ou invalido, ou query desconhecida, no DELETE de `/v1/memory`.
- 404 `not_found`: `DELETE /v1/memory/{id}` com id que nao existe.
- 409 `conflict`: `version` velha em PUT de `/v1/policy` ou `/v1/models`; chave de idempotencia reusada com outro pedido.
- `error.code:"model_denied"` no job (status `failed`): a politica de modelos passou a recusar o modelo entre a criacao e a execucao.
Formato: `{"error":{"code":"...","message":"..."}}`. A mensagem nunca repete o valor recebido.

### Nao aplicado ainda (validado e gravado, sem efeito)
- `policy.scopes`: validado e gravado, mas NAO lido por nenhuma decisao. Nao restringe nem libera escrita por pasta.
- `policy.auto_countdown_seconds`: validado e gravado, mas NAO aplicado. Nenhum job e aprovado sozinho depois da contagem; so `POST /v1/jobs/{id}/approve` aprova.
- `policy.notify`: validado e gravado; o motor nao emite notificacao por ele (a UI decide o que mostrar).
- Kind `root`: a politica ja o trata (liga/desliga, `by_kind.root`), mas NAO ha adaptador. Um job `root` aprovado termina `failed` com `error.code:"unsupported_action"`. `su`/`tsu` via `shell`/`adb` passam pela mesma regra de politica.
- Historico e desfazer existem so para a politica. `models.json` nao tem historico nem undo (cada PUT apenas incrementa `version` e emite `models.changed`).
- `by_kind` so afeta jobs com risco diferente de `read`, e so nos modos `autonomo` e `equilibrado`.

## Entradas, triagem e fila (T20)
A recepção (regras da skill `clareza`, locais, sem modelo) lê a fala ou o texto ANTES da triagem. Nada executa: só gera eventos e anota `queue.json`.
- `POST /v1/inputs` `{"text","source":"voice|text"}` -> `{id, kind: question|adjust|new_task|stop, confidence, clean, ambiguities}`. Se a limpeza falhar, `clean` é o texto original. `question`: devolve `answer` (status do job em curso) ou `request` (proposta de `/v1/route`, nada roda). `stop`: interrompe o job em curso na hora e devolve `applied` (false se nada rodava). `adjust` e `new_task`: ficam pendentes e trazem `recommended` (`merge` ou `later`). `adjust` só existe com job rodando; sem job vira `new_task`. Confiança é a da regra, não probabilidade medida.
- `POST /v1/inputs/{id}/apply` `{"mode":"later|merge|now","confirm":true}` -> fila atual + `applied`. `later` (padrão do `new_task`): vai para o fim da fila. `merge` (padrão do `adjust`, "Juntar no trabalho atual"): cancela o atual e cria um job novo com o mesmo pedido mais `adjustments` (e o texto no `prompt`, se houver). `now`: exige `confirm:true` (senão 400); o atual fica salvo e o novo começa. A contagem de 5 s e o botão Mudar são do app.
- `GET /v1/queue` e `queue.updated`: `{running:{jobId,title}|null, queued:[{taskId,text}], saved:[{taskId,jobId,title,checkpointId,resumable}]}`. A fila é em série: o próximo vira job só quando nada roda nem espera worker (ao fim de cada job e a cada volta do worker). Persistida em `queue.json`.
- `POST /v1/queue/{taskId}/resume`: tira de "salvo" e põe à frente da fila. Só o tipo `rea` tem checkpoint; os outros recomeçam do início.
- Eventos: `input.interpreted {taskId, raw, clean, intent, ambiguities[], skill}` (sempre antes de `input.received`), `input.received {taskId, kind, confidence, source}`, `queue.updated`, `task.interrupted {taskId, jobId, checkpointId, resumable}`.
- Limites: depois de reiniciar o motor, o job que estava rodando fica `interrupted` e não vai para "salvo" (a fila segue sozinha); a skill original de clareza não foi recebida, as regras vêm de `voz-e-fila.md`.

## Cadeia REA no Termux (T20, job `kind: rea`)
- `{"action":"setup"}` (risco `write`, pede aprovação): passos `node`, `jdk`, `jadx`, `apktool`, `adb`, `rea`, `mcp`, nesta ordem, por `pkg install -y` (`nodejs-lts` com `npm`, que desde o Node 24.13 é pacote à parte, `openjdk-21`, `apktool`, `android-tools`), `curl` do jar JADX headless 0.7.1 com SHA-256 conferido por `sha256sum -c`, `npm install -g rea-agents@6.8.0` e, no fim, `tools/list` real do servidor MCP (`rea-agents mcp`, JSON-RPC por stdio). Checkpoint por passo em `<home>/rea/checkpoint.json`; rodar de novo pula o que já tem checkpoint (vale também depois de o Android matar o motor). Falha: job `failed`, `error.code:"rea_step_failed"` com o nome do passo. Eventos `tool.install {id, name, state: installing|ready|failed, pct 0|100}`.
- `{"action":"tools"}` (risco `read`): `{tools:[{name, risk}]}` do `tools/list` real. Ferramenta com `mutatesDevice` (no topo, em `annotations`, ou "device-mutating" na descrição) tem `risk:"write"`, nunca `read`. Sem paginação do `tools/list`.
- `{"action":"measure","reversal_pct","rebuild_pct","fidelity_pct","real_test":{"device","flows_total","flows_passed"}}` (risco `read`): devolve `{reversalPct, rebuildPct, fidelityPct}`; `fidelityPct` é `null` sem `real_test` (aparelho e fluxos). Os números vêm de quem mediu; o motor não calcula. Emite `reform.progress {id, reversalPct, rebuildPct, fidelityPct, stage, checkpointId}`.
- Não verificado em aparelho (fica para o T18): nomes dos pacotes do Termux, versão exata do Node aceita pelo REA (22.23.4+/24.22.1+/26.12.1+, o passo `node` não confere), onde o REA põe `mutatesDevice` e se o `tools/list` pagina.

## Autonomia total dos CLIs, delegação e instalação (motor Python)
Root/su: `kind` `root` ou `argv[0]` `su`/`tsu` NUNCA são recusados (decisão do dono, root aberto; substitui o texto antigo de recusa 400 acima). `root.enabled`/`root.via` são gravados mas não bloqueiam; o padrão de `root.enabled` agora é `true`. Só pergunta se o dono escolher um modo que pergunta.

Com `autonomy:"total"` (padrão de `/v1/settings/autonomy`) os jobs de CLI rodam sem pedir permissão. Em `ask_destructive`/`ask_all` nada disso é passado (comportamento antigo, inclusive o erro `permission_required`).
- `claude_cli`: `claude -p ... --output-format json --dangerously-skip-permissions` (igual a `--permission-mode bypassPermissions`, doc oficial; como root o motor põe `IS_SANDBOX=1`). Sem `--allowedTools` por padrão (subagentes e `Task` livres). Campos opcionais do pedido: `allowed_tools` (lista de nomes, vira `--allowedTools a,b`) e `max_turns` (inteiro 1..1000, vira `--max-turns`).
- `gemini_cli`: `--yolo`. `agy_cli`: `agy --dangerously-skip-permissions -p ...` (a flag vem antes do `-p`); antes de rodar, o motor junta `command(*) read_file(*) write_file(*) mcp(*)` a `permissions.allow` de `~/.gemini/antigravity-cli/settings.json` (G5; nada que o dono já tenha lá é apagado; JSON inválido não é tocado).
- `codex_cli` (novo): `codex exec --dangerously-bypass-approvals-and-sandbox --skip-git-repo-check [-m modelo] <prompt>`. `result.response = {text}` (texto, não JSON).
- Tempo: padrão 3600 s (`AURA_CLI_TIMEOUT`, em segundos; o job pode mandar `timeout`; teto 7200 s).
- Delegação: o Claude orquestra com as próprias ferramentas (Bash chama `agy -p`, `gemini -p`, `codex exec` já instalados e logados) ou o app envia jobs `agy_cli`/`gemini_cli`/`codex_cli`. `routing.choose_executor` segue: tool > agy > gemini_api > claude_min.

### Instalar CLIs no Termux
`POST /v1/tools/install {"provider":"claude|codex|gemini|agy|gh|rclone","request_id"?}` -> 202 + job (`kind:"tools", action:"install"`, risco `write`). Resultado: `{provider, state:"ready"|"waiting_wifi", already_installed, via:"npm"|"glibc-runner"|"installer"|"pkg"|null, download_notices}`. Eventos `tool.install {id,name,state,pct}` e `download.notice {id,step,mb}` (>100 MB, nunca bloqueia). Dados móveis (`/v1/network metered:true`): não baixa, devolve `waiting_wifi`; rodar de novo retoma. Já instalado e `--version` ok: `ready` sem baixar. Ligue o botão "Instalar" do `/v1/auth/status` (`installable:true`) a este endpoint.
- npm: `pkg install -y nodejs-lts git` (só se faltar) e `npm install -g @anthropic-ai/claude-code | @openai/codex | @google/gemini-cli`, validado com `<cli> --version`.
- claude: se o npm falhar ou o binário não rodar (a Anthropic só publica binário glibc), cai no caminho A do projeto MIT `ferrumclaudepilgrim/claude-code-android` (glibc-runner + binário linux-arm64 remendado): baixa o `install.sh` dele e responde "n/n" às 2 perguntas. Erro final `setup_failed`.
- codex: sem fonte confirmada para Termux; se o `npm`/`--version` falhar, erro `indisponivel_no_termux`.
- agy: `curl -fsSL https://antigravity.google/cli/install.sh` e `bash`. gh e rclone: `pkg install -y`.

### Login do Claude: código colado e token
`claude auth login` imprime uma URL de OAuth e espera o CÓDIGO de volta. Fluxo: `POST /v1/auth/start {"provider":"claude"}` -> evento `auth.prompt {url}` -> o app abre a URL no navegador -> o dono cola o código no app -> `POST /v1/auth/code {"provider":"claude","code":"..."}` -> `{provider,state:"em_andamento"}` (400 se não há login em andamento; o código nunca volta em resposta, evento ou log) -> `auth.done`. Alternativa: `claude setup-token` e `POST /v1/auth/token {"provider":"claude","token":"..."}` -> `{state:"conectado"}`; o token fica em `~/.aura/engine/claude_oauth_token` (0600), vira `CLAUDE_CODE_OAUTH_TOKEN` e é redigido das saídas. Codex: `codex login --device-auth` (já em `/v1/auth/start`; o código vale 15 min).

### Reverse engineering de ponta a ponta
Job `{"kind":"rea","action":"reverse_engineer","apk_path":"...","goal"?:100,"mode"?,"executor"?,"reform_id"?,"idempotency_key"?}` (risco `write`). Cria a reforma (ou retoma `reform_id`) e encadeia no MCP do REA: `binary_session` -> `inventory_artifact` -> `extract_artifact` -> `build_reconstruction_obligation_ledger` -> reconstrução pelo executor (`agy_cli` > `gemini_api` > `claude_cli`, ou o `executor` pedido; texto do APK sempre cercado como dado não confiável) -> `evaluate_reconstruction_coverage`. Cada passo vira `tool_call`/`evidence`/`step_done` no ledger (`GET /v1/reform/{id}/ledger`). Resultado: `{reform_id, steps_run, chain_ok, reversal, rebuild, fidelity}`. Argumentos reais das ferramentas (`path`, `session_id`) não verificados com o REA de verdade.

### Motor vivo
Ao iniciar, o motor chama `termux-wake-lock` se existir (best effort, sem falhar).


## Chat nativo (conversa em texto, sem jobs)
O chat NAO cria job, NAO pede aprovacao e NUNCA devolve JSON cru da CLI. Todos os endpoints exigem Bearer. Roda `claude -p <texto> --output-format stream-json --verbose --include-partial-messages` no workspace, com `--session-id <uuid>` na 1a mensagem e `--resume <uuid>` nas seguintes, `--dangerously-skip-permissions` quando a politica e `total` e `--model` pelo teto de `models.json`. Sem `claude`, cai para `gemini`, depois `agy` (texto simples, sem streaming real: um unico `chat.delta`; o motor leva o fim da conversa no prompt). Com `executor` explicito e ausente, nao troca de assistente: `chat.error` `cli_missing`.

POST `/v1/chat` `{text, session_id?, workspace?, executor?}` -> 202 `{chat_id, session_id}`. `text` obrigatorio (ate 100000); `session_id` UUID (omitido = conversa nova; o motor devolve o id para reusar); `executor`: `auto|claude|gemini|agy` (ou `*_cli`). Uma execucao por vez por `session_id`: mensagem enviada com outra rodando entra na fila, em ordem (`state:"queued"`). Erros de pedido: 400.
Eventos (em `/v1/events`, mesmo formato dos `job.*`; `payload` sempre tem `chat_id`):
- `chat.delta {chat_id, text}`: incremento de texto, em ordem. Entre mensagens do assistente separadas por ferramenta o motor insere `"\n\n"`.
- `chat.tool {chat_id, summary}`: frase curta em portugues ("Lendo arquivos…", "Rodando comando…", "Editando app.py", "Procurando no código…", "Trabalhando…"); nunca traz argumentos.
- `chat.done {chat_id, text, session_id[, cost]}`: `text` = resposta completa (soma dos deltas); `cost` so existe se a CLI informou (USD). Use `session_id` nas proximas mensagens.
- `chat.error {chat_id, code, message_pt, action_pt}`; `code`: `cli_missing` ("Instalar o Claude em Conexões"), `no_executor`, `not_logged_in` ("Entrar no Claude em Conexões"), `timeout` (15 min), `network`, `stopped` (o usuario tocou Parar: mostre sem alarme), `failed` ("Tentar de novo"). Um chat termina com exatamente um `chat.done` ou `chat.error`.

GET `/v1/chat/{chat_id}` -> `{chat_id, state:"queued|running|done|error", text, session_id[, error:{code,message_pt,action_pt}]}`; `text` e o acumulado ate agora. 404 se nao existe. Use para recuperar eventos perdidos. Se o motor reiniciar no meio, o chat vira `error` (`interrupted`).
POST `/v1/chat/{chat_id}/stop` (sem corpo) -> `{chat_id, stopped:true}`; mata o processo (grupo inteiro) e emite `chat.error code:"stopped"`. Chat ja encerrado -> `{..., stopped:false}`.
GET `/v1/chats?limit=50` -> `{chats:[{id, title, updated_at}]}` (id = `session_id`; mais recente primeiro; titulo = 1a linha da 1a mensagem, ate 60). GET `/v1/chats/{session_id}/messages` -> `{messages:[{id, chat_id, role:"user|assistant", text, created_at}]}` em ordem. Persistem no sqlite do motor.
POST `/v1/intent` `{text, workspace?}` -> `{kind:"chat|command|reform|project|task", needs_approval:false|true[, request]}`. `chat` e o padrao e nunca pede aprovacao; `command` (atalhos locais deterministicos como listar dispositivos/arquivos) traz `request` pronto para `POST /v1/jobs` e `needs_approval` pela politica (`total` -> false); `reform|project|task` sao palavras-chave para o app abrir o fluxo proprio. Nao devolve `reason`.
