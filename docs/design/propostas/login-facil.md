# Login fácil das contas (proposta para o motor)

Objetivo: o dono conecta Claude, Gemini, Antigravity, Codex, GitHub e Google Drive com **1 toque em "Conectar"**, usando as **assinaturas** dele. Sem terminal, sem chave de API paga. `ANTHROPIC_API_KEY` e `GEMINI_API_KEY` existem só como opção avançada, escondida em Detalhes.

## Fluxo

1. App chama `POST /v1/auth/start {provider}`. Ids: `claude`, `gemini`, `agy`, `codex`, `github`, `gdrive`.
2. O motor roda o login oficial do CLI no Termux, em modo código/link (sem abrir navegador no Termux):

| provider | comando no Termux |
|---|---|
| claude | `claude auth login` (ou `claude` e comando de login) |
| gemini | `gemini` com login Google (link + código) |
| agy | `agy` login (nunca escolher "Use a Google Cloud project": cobra por uso) |
| codex | `codex login --device-auth` |
| github | `gh auth login --web` |
| gdrive | `rclone authorize drive` |

3. O motor lê só o que o CLI imprime de **link e código** e emite `auth.prompt {provider, url, code?}`. O texto bruto do terminal nunca vai ao app.
4. O app abre `url` no navegador (Custom Tab, via callback `onOpenUrl`) e mostra `code` grande com botão **Copiar código**.
5. O motor detecta sucesso (saída do processo 0 e `auth status` do CLI) e emite `auth.done {provider}`; em erro, `auth.failed {provider, reason, fix}`, com `reason` e `fix` em português, curtos e acionáveis.
6. `GET /v1/auth/status` devolve `[{provider, state, installable?, detail?}]`, com `state` em `conectado | desconectado | expirado | indisponivel`. Nunca inclui token, cookie ou conteúdo de `auth.json`.

`POST /v1/auth/start` é idempotente por provedor: segundo toque durante um login em andamento devolve o mesmo `auth.prompt`. Timeout de 5 minutos emite `auth.failed {reason: "tempo_esgotado"}`. Login é leitura de identidade, não gasto: risco `read`, sem aprovação.

## Estados na tela

- `desconectado`: botão **Conectar X**.
- `expirado` (refresh token vencido): chip "Expirado" e botão **Reconectar X**.
- `indisponivel` com `installable=true` (CLI ausente): botão **Instalar X**; o app cria um job `system` "Instalar o programa de X" que passa pela aprovação normal.
- `indisponivel` sem `installable`: chip "Indisponível" e `detail` explicando por quê.

## Falhas: o que dizer (`fix`)

- Sem internet: "Conecte à internet e toque em Conectar de novo."
- Código recusado ou vencido: "O código venceu. Toque em Conectar para gerar outro."
- Dono cancelou no navegador: "Login cancelado. Toque em Conectar quando quiser."
- CLI ausente: "Falta o programa de X. Toque em Instalar."

## Riscos

1. **CLI sem device flow**: se algum CLI só aceitar callback em `localhost`, o motor deve expor o callback pelo loopback e passar o link (`url`) igual; se nem isso, `indisponivel` + `detail`. Validar cada CLI no aparelho real (invariante 9).
2. **Expiração de refresh token**: o motor revalida `auth status` no início e após cada `auth_required`; vira `expirado`, nunca "conectado" por cache.
3. **CLI ausente**: `installable` só é `true` se o motor sabe instalar com versão fixada.
4. **Vazamento**: nunca logar `url` com parâmetros de sessão, `code`, nem `auth.json`; `code` é de uso único e só trafega no evento ao app local (invariante 7).
5. **Custo**: `agy` e Gemini só pelo login de assinatura; se o CLI oferecer cobrança por uso, o motor recusa e diz como escolher a assinatura.
6. **Link falso**: o app só abre `url` recebida por `auth.prompt` de um login que o próprio dono iniciou; `https` obrigatório.

## UI (já feita)

`ui/screens/settings/ConnectAccountsScreen.kt`, acessada por "Conectar contas" em Ajustes. Contrato no app: `EngineGateway.authStatus()` e `startAuth(provider)` (padrão = indisponível), com `FakeEngineGateway` simulando. Eventos: `auth.prompt`, `auth.done`, `auth.failed`. A tela confere `authStatus()` a cada 2 s como plano B se um evento se perder.
