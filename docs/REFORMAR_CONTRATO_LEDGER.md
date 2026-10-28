# Reformar apps: contrato, ledger e roteamento de modelos

Decisão: o AURA não "confia" no que o modelo diz. Toda etapa da reforma vira uma entrada num **ledger** (livro-razão) append-only com hash encadeado. Os números que o usuário vê (Reversão, Reconstrução, Fidelidade) são **calculados do ledger**, nunca digitados.

## 1. Ferramenta: REA (nomes reais, lidos em docs/mcp-contracts.md do morluto/rea)
Sessão: `binary_session`, `open_binary`, `close_binary`. Inventário: `inventory_artifact`, `extract_artifact`, `inspect_artifact`, `inspect_apktool_client`, `inspect_adb_client`. Análise: `analyze_function`, `decompile_all`, `trace_call_path`, `trace_application_feature`. Evidência: `get_evidence_bundle`, `export_evidence_bundle`. **Reconstrução:** `build_reconstruction_obligation_ledger`, `evaluate_reconstruction_coverage`. Incógnitas: `record_unknown`, `update_unknown` (com `expected_revision`), `list_unknowns`. Captura: `capture_process_scenario` (executa e observa), `capture_browser_scenario`. Efeito no aparelho: operações marcadas `mutatesDevice` (instalar, desinstalar, push, start, stop).
Nota honesta: os termos "verify reconstruction" e "outbound process capture" NÃO aparecem como nomes de ferramenta na parte lida do catálogo (113 mil caracteres, lidos todos). Mapeamento adotado: verificação = `evaluate_reconstruction_coverage` + comparações; captura de processo = `capture_process_scenario`. A lista oficial é sempre `tools/list` no Termux (o engine já faz isso em `rea_chain.list_tools`); o contrato abaixo usa os nomes que o `tools/list` devolver.

## 2. Ledger (por projeto de reforma)
Arquivo `reform/<projeto>/ledger.jsonl` (append-only) + índice SQLite. Cada linha: `seq, ts, kind, idempotency_key, prev_hash, hash, payload`. `hash = sha256(prev_hash + json canônico)`. Adulteração = cadeia quebrada = reforma marcada "inválida".

Tipos (`kind`): `scope_locked` (modo, app, aceite "sou dono/tenho permissão", meta de fidelidade) · `obligation_added` (da REA) · `tool_call` (ferramenta, hash da entrada, `mutates_device`, `risk`, `cost_class`) · `evidence` (evidence_id da REA, sha256, arquivo) · `unknown` (id, revision, estado) · `step_done` (passo, prova) · `coverage_evaluated` (satisfeitas/total, vindo da REA) · `rebuild_progress` (telas prontas/total) · `model_call` (provedor, classe de custo, tokens, aprovado?) · `approval` (alvo, consequência, quem aprovou) · `fidelity_measured` (id do teste real, aprovadas/total) · `checkpoint`.

## 3. Invariantes (o motor recusa se violar)
1. Sem `scope_locked` com aceite de permissão, nada roda. App do sistema/câmera de ré: recusado.
2. Reversão % = obligations satisfeitas ÷ total do **último `coverage_evaluated`** (REA). Sem avaliação = "ainda não medida".
3. Reconstrução % = telas prontas ÷ telas mapeadas (`rebuild_progress`).
4. **Fidelidade = null** até existir `fidelity_measured` ligado a um teste real; nunca estimar.
5. Ferramenta com `mutates_device` exige `approval` anterior e `risk` ≥ médio. O app original nunca é apagado; instala ao lado.
6. Chamada de modelo com `cost_class="paid"` exige `approval` específico. Padrão: nunca.
7. Idempotência: repetir com a mesma `idempotency_key` devolve o resultado anterior (retomada após o Android matar o processo = reprocessar o ledger, pular passos com `step_done`).
8. Cada afirmação mostrada ao usuário aponta para `seq` do ledger (auditável nos Bastidores).

## 4. Modelos: login do Antigravity e Gemini, sem gastar à toa
- **Primeiro:** CLI `agy` (Antigravity) em subprocesso, com o login do próprio usuário (assinatura/cota dele). O AURA nunca lê, copia nem guarda token do agy: só chama o comando. `cost_class="subscription"`.
- **Gemini API:** só se existir chave E for plano gratuito/cota liberada E `approval` do usuário. Caso contrário `paid` → bloqueado (trava de custo). Não confundir: login do Antigravity ≠ chave de API do Gemini.
- Determinístico primeiro: ler/decompilar/inventariar (REA, JADX, Apktool) não usa modelo. Modelo só para: nomear telas/fluxos, redigir a interface nova, resumir evidência.
- Cache: resultado de ferramenta com mesmo `sha256` de entrada não roda de novo (`tool_call` com mesma chave → reaproveita `evidence`).
- Cada `model_call` registra tokens e custo; o app mostra o consumo por projeto.

## 5. Contratos HTTP do engine (v1, estáveis em inglês)
`POST /v1/reform` (trava escopo) · `POST /v1/reform/{id}/steps/{step}` · `GET /v1/reform/{id}` (medidores calculados do ledger) · `GET /v1/reform/{id}/ledger?since=seq` · `POST /v1/reform/{id}/approve` · `POST /v1/reform/{id}/verify` (roda o teste real e grava `fidelity_measured`).
