# AURA Engine interface contract v1

Device-local engine process in Termux. Loopback-only HTTP, default `127.0.0.1:8765`. No externally bound address. Android launcher connects with Termux RUN_COMMAND integration requiring user-granted `com.termux.permission.RUN_COMMAND` and `allow-external-apps=true`. A random 256-bit token exists at `~/.aura/engine/token` mode 0600; ALL engine endpoints require `Authorization: Bearer <token>`. A future Android-native handshake must securely transfer this token; do not copy it into screenshots or logs.

## JSON commands
POST /v1/route
Body: `{"text":"...","workspace":"default","preferred_provider":"auto"}`. Responds with typed `request`, `reason`, `confidence` and potential `requires_model`. No job is started.

POST /v1/jobs
Body: `{"request_id":"unique-uuid","request":{"kind":"claude_cli","prompt":"...","workspace":"default"}}`.
Kind enum: `system`, `shell`, `adb`, `files`, `github`, `claude_cli`, `gemini_cli`, `agy_cli`, `claude_api`, `gemini_api`, `skills`.
Response: `{"id":"...","status":"queued|awaiting_approval|running|completed|failed|cancelled|interrupted","risk":"read|write|spend|critical",...}`.
Identical `request_id` returns same job. Different payload for the same id -> conflict.

GET /v1/jobs?limit=50, GET /v1/jobs/{id}, POST /v1/jobs/{id}/approve, POST /v1/jobs/{id}/cancel.
Jobs with side effects require approval by an authenticated user action. Each approval is tied to immutable stored payload. Read-only commands may run automatically. Approval is not inherited by spawned jobs.

GET /v1/events?since=0: durable ordered events with IDs, type, payload, timestamp. Clients poll by `since`; no lost progress on Activity page restart.
GET /v1/capabilities: real installed tools and API-key presence (never actual credentials). Missing providers explicit.
GET /v1/skills: scanned local SKILL.md manifests, never executed automatically merely by enumeration.
POST /v1/memory, GET /v1/memory?workspace=...&q=...: scoped durable notes, no secret storage.
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

### Codigos de erro novos
- 400 `invalid_request`: enum invalido, campo desconhecido ou tipo errado em `/v1/policy` e `/v1/models`; `provider` invalido em `/v1/auth/start`; `to_version` invalido ou nada para desfazer; root nao permitido pela politica; modelo negado ou acima do teto no POST do job.
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
