# Engine constraints / falsification gates

| ID | Non-negotiable invariant | Failing case that must be exercised |
|----|--------------------------|-------------------------------------|
| SEC-01 | Every endpoint authenticates | GET /v1/health without bearer returns 401 |
| SEC-02 | Only loopback server | Start with 0.0.0.0 rejected |
| SEC-03 | Workspace confinement including symlinks | ../escape and symlink to outside fail |
| SEC-04 | Deny-by-default side effects | Arbitrary shell before approval never runs |
| SEC-05 | No secrets in logs | API key / bearer absent from events and exceptions |
| JOB-01 | Idempotent submit | Equal key equal request yields same job |
| JOB-02 | Duplicate key different request is conflict | Reuse UUID with new payload rejected |
| JOB-03 | At-most-once claim | Single queued job cannot run twice |
| JOB-04 | Persistent interruption on restart | running -> interrupted, never replayed |
| JOB-05 | Cancellation races | Completed/cancelled cannot be overwritten |
| EVT-01 | Monotonic durable events | restart still yields events after cursor |
| ADB-01 | No fabricated availability | missing adb is unavailable |
| PROVIDER-01 | Offline/no login is explicit | missing claude/gemini key -> actionable error |
| ROUTE-01 | Cheap deterministic intent | "listar dispositivos" -> adb devices no model |
| ROUTE-02 | Ambiguous complex intent | plan via available model, never shell automatically |
| SCHEDULE-01 | Scheduling never bypasses approvals | critical scheduled command remains awaiting_approval |
| UX-01 | Android client not blocked by stderr | result structured even on non-zero exit |
| FIN-01 | Requests requiring API spend not silent | API job risk=spend |
| FILE-01 | Nonprogrammer cannot lose unrelated files | delete requires approval; no full-device root access |
| TRACE-01 | Every claim links to test/CI/device result | no unsupported status "complete" |

Review scenarios: duplicate replay, concurrent approve + cancel, malicious `SKILL.md`, huge output, stalled CLI, broken USB/WiFi, device offline, token leak, unexpected json shape, API 429, GitHub permissions, Android OS killing app, zero storage, screen rotation/IME inset. Test mandatory for every applicable invariant; unverified device-only cases tracked explicitly.

---

# App Android: barra de qualidade

Revisado em 2026-10-27. O "100%" é a `docs/MATRIZ_DE_TESTES.md`. Cada linha abaixo traz o comando que dá o veredito: sem comando é desejo, não restrição. Não enfraqueça esta tabela para fazer uma mudança passar.

## Piso (sempre, sem configuração)
- Nenhum `@Suppress`, `@SuppressLint`, `@Ignore`, `TODO(` ou `NotImplementedError` novo no diff.
- Nenhum toque mudo: `git grep -nE "onClick\s*=\s*\{\s*\}" -- android/app/src/main` retorna vazio.
- Nenhum token, chave ou senha em log, erro ou teste: `git grep -niE "Log\.[a-z]\(.*(token|senha|password|secret)" -- android/app/src` retorna vazio.
- Nenhum teste apagado ou pulado sem motivo escrito na mensagem do commit.

## Com número
| Dimensão | Regra | Por quê | Verificado por | Quando |
|----------|-------|---------|----------------|--------|
| Modo morto | Com o gateway falhando TODA chamada (IOException): cada aba e rota abre, 0 crash, 0 tela em branco, 0 spinner sem fim | Regra nº 1 do dono | `OfflineMatrixTest` (Robolectric) | fim de tarefa e final |
| Toque mudo | 100% dos nós clicáveis de cada tela mudam a tela, chamam um callback ou abrem um destino | Matriz E | `ClickEverythingTest` | fim de tarefa |
| Testes | 0 falhas, 0 pulados | Sem CI, a prova é local | `scripts/gw.sh :app:testDebugUnitTest --offline` | toda tarefa |
| Lint (externo) | 0 erros; avisos não passam do valor medido | Opinião de fora do nosso código | `scripts/gw.sh :app:lintDebug --offline` | revisão e final |
| Alvo de toque | todo clicável ≥ 44 dp | docs/design/README.md | asserção nos testes de tela | fim de tarefa |
| Contraste | texto sobre fundo ≥ 4,5:1 nos pares de tokens | WCAG AA | `TokensTest` | toda tarefa |
| Aparência de app confiável | release sem `debuggable`; só o launcher exportado; texto claro só para 127.0.0.1; ícone adaptativo; cada permissão perigosa com uso real e texto de motivo | Android/Play Protect desconfia de app que pede o que não usa | `aapt2 dump badging`, `aapt2 dump permissions`, `adb shell dumpsys package ai.aura.app`, `docs/PERMISSOES.md` | final |
| Atualização | v6 instala por cima do v5 (mesma assinatura, versionCode maior) sem perder dados nem login | Matriz F | `adb install -r` com marcador gravado antes | final |

## Medido, ainda sem teto
| Métrica | Hoje | Direção |
|---------|------|---------|
| Testes unitários do app | 19 | só sobe |
| Tamanho do APK debug | a medir no teste de emulador | não crescer sem motivo |
| Partida a frio (`adb shell am start -W`) | a medir no teste de emulador | não piorar |

## Exceções
Nenhuma.
