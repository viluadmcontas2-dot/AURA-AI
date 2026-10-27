# Proposta: política editável, roteamento de modelos e skill de reformar apps

Status: PROPOSTA do lado UI (Claude). Dono do motor: GPT. Nada aqui altera o contrato até o GPT aceitar. Pedido do dono (27/10): mudar permissão/autonomia sem recompilar; autonomia máxima, sem limite imposto (ele decide o que é seguro); modelo certo por custo-benefício (nunca Fable; teto Sonnet médio; maioria Haiku médio/alto); subagentes; skill de design para refazer apps nativos mais modernos e leves.

## 1. Regra de ouro: configuração é dado, não código
O app Android **não decide** permissão nem modelo. Ele lê e grava dois documentos no motor (arquivos em `~/.aura/engine/`, mode 0600, gravação atômica, versionados, com histórico). Mudar = editar na tela Ajustes (ou o arquivo) → vale na hora, sem novo APK.

- `GET/PUT /v1/policy` → `policy.json`
- `GET/PUT /v1/models` → `models.json`
- Cada PUT: valida schema, grava `version+1`, emite evento `policy.changed` / `models.changed`, guarda quem mudou (ação do usuário autenticada). O motor relê a cada job (sem cache longo).

Limite honesto: permissões **do Android** (microfone, notificações, RUN_COMMAND do Termux, instalar APK) são do sistema operacional; o app só pode pedir, o usuário concede. Isso não muda sem o usuário.

## 2. `policy.json` (o dono decide o que é seguro; o app não impõe limite)
```
{ "version": 1,
  "mode": "total",               // pergunta | equilibrado | autonomo | total
  "by_risk": { "read":"auto", "write":"auto", "spend":"auto", "critical":"auto" },
  "by_kind": { "shell":"auto", "adb":"auto", "files":"auto", "github":"auto", "root":"auto" },
  "root": { "enabled": true, "via": ["su","tsu"] },  // só funciona se o aparelho tiver root
  "scopes":  [ {"path":"*","write":"auto"} ],        // inclui /, /data, /root, qualquer caminho que o Termux alcance
  "spend_cap": null,             // null = sem limite (decisão do dono)
  "auto_countdown_seconds": 0,
  "notify": "so_se_precisar" }
```
- **Decisão do dono (27/10): nenhum limite imposto pelo app.** Tudo que o Termux consegue fazer, o app consegue pedir: shell, `su`/`tsu`, ADB, arquivos em qualquer caminho, GitHub, instalar/remover apps, gasto. `mode` vai de `pergunta` a `total`; cada categoria pode ser `auto` ou `ask` e muda na hora.
- **Isso substitui** a ideia de "piso de segurança" da primeira versão e o invariante 6 do AGENTS.md ("destrutivo e gasto exigem aprovação explícita"): para o dono, aprovação vira **opção dele**, não regra fixa. O GPT deve ajustar o AGENTS.md só quando o dono confirmar por escrito; até lá o padrão do motor pode continuar `autonomo`, mas o JSON precisa aceitar `total`.
- **O que continua sendo verdade, sem ser limite do app**: (1) o Android e o Termux têm as próprias fronteiras (sandbox do app, RUN_COMMAND só com permissão do usuário, root só em aparelho com root); o app nunca contorna isso. (2) Cada ação automática fica registrada com payload completo no evento ("executado por política vN"), para o dono poder ver e desfazer o que der. (3) Um botão "Parar tudo" cancela jobs em andamento. Registro e parada não são travas, são visibilidade.
- Segredos nunca em `policy.json`.

## 3. `models.json` (custo-benefício por padrão)
```
{ "version": 1,
  "deny": ["fable*"],                       // nunca
  "ceiling": { "claude":"sonnet:medium" },  // teto duro, o motor recusa acima disso
  "routes": [
    {"task":"triagem|classificar|resumir|status", "use":["haiku:medium"]},
    {"task":"codigo_mecanico|testes|refatoracao_simples", "use":["codex_cli","agy_cli","haiku:high"]},
    {"task":"ui_compose|planejar|revisar", "use":["sonnet:medium"]},
    {"task":"pesquisa_ampla", "use":["subagente:haiku:medium"]}
  ],
  "prefer_subscription": true,              // CLI logada antes de API paga
  "api_fallback": "never" }                 // padrão do dono: sem API paga; ele muda aqui quando quiser
```
- Ordem de escolha: CLI da assinatura (Codex/agy/Claude Code) → modelo mais barato que cumpre a tarefa → escalar **uma** vez para o teto (Sonnet médio) → parar e avisar. Nunca sobe além do `ceiling`/`deny`.
- A rota escolhida e o motivo aparecem no evento do job (`model_used`, `why`), para a tela Atividade mostrar "usei Haiku: tarefa simples".
- Subagentes: o motor ou a skill podem abrir jobs filhos; cada filho herda `policy` do pai (aprovação **não** é herdada, regra do contrato) e o `ceiling`.

## 4. Entrada: receber e entender pedidos
Já existe `POST /v1/route` (determinístico, sem job). Proposta de ordem: (1) regras locais/baratas; (2) só se `confidence` baixa, Haiku médio para classificar em `pergunta | ajuste | novo_pedido | parar` (docs/design/voz-e-fila.md); (3) a skill de clareza (dyslex.ai, conteúdo ainda não recebido) entra **antes** da triagem. O resultado traz `model_used` e custo estimado. Pergunta de volta ao dono só quando falta algo importante, uma vez (regra do dono para o app todo).

## 5. Skill "reformar-apps" (Entrega 3, depois do motor estável)
Objetivo: refazer e melhorar apps nativos de mídia (e apps para outros aparelhos) de forma mais moderna e leve. Fluxo já desenhado em `docs/design/reformar-apps.md` e telas `Reformar-1..6`.
- Entrada: APK/app alvo → inventário (REA, `morluto/rea`: Node 22.23+, JDK, JADX sem tela; **não** mencionado para Termux, validar no aparelho) → relatório de fidelidade.
- Saída: projeto novo (Compose) com a skill `frontend-design` + tokens do app, escopo e reversão escolhidos pelo dono, instalar só com aprovação.
- Empacotar como Agent Skill portátil em `engine/skills/reformar-apps/SKILL.md` (pasta do GPT). Este documento é o briefing; o GPT decide o formato final.
- Restrições: só apps que o dono tem direito de modificar; nada de contornar proteção/licença; instalar/substituir app = ação `critical` com aprovação.

## 6. O que cada lado faz
- **GPT**: `policy.json`/`models.json` + endpoints + validação + eventos; aplicar o piso; `model_used/why`; carregar `ceiling/deny`; adaptador em `integration/`; testes.
- **Claude (UI)**: tela Ajustes (Autonomia, Modelos, Zona de risco), tela Atividade mostrando `model_used`, leitura via `EngineGateway.policy()/models()`; nunca mantém política em código.
- **Critério de pronto**: mudar `policy.json` no aparelho e ver o próximo job obedecer **sem reinstalar o APK**; teste com Fable na lista `deny` sendo recusado; PUT inválido rejeitado.
