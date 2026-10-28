# AURA AI — Implementation evidence ledger

Date: 2026-10-28 (UTC). Source of truth: `viluadmcontas2-dot/AURA-AI` on `main`. Evidence from PR heads is recorded separately from evidence on `main`.

## Implemented in canonical repository (source-level evidence)

- Policy: risk classification, workspace confinement, token creation and Android pairing support.
- Durable engine: SQLite queue, idempotency, events, scoped memory, schedules, recover-on-restart and bounded parallel workers.
- Execution adapters: native/system/files/shell, Android ADB, GitHub (gh/git), Drive (rclone), Claude Code, Gemini CLI, Antigravity CLI, Claude/Gemini APIs and portable Agent Skills.
- Runtime interface: loopback HTTP with bearer authentication, job lifecycle, approval/cancel, capabilities, event polling, memory, schedules and actual reported usage.
- Novice experience support: local deterministic routing, available skill selection, doctor with actionable warnings and native-app handoff.
- Test suite: `engine/tests/test_engine.py` plus `test_lote_a.py` and `test_lote_c.py`; `engine-ci.yml` executes compile + unittest on Python 3.12 and 3.14. The exact tested SHA matters.
- Portable skills: 3 bundled `SKILL.md` workflows with lazy metadata discovery.

## Explicitly NOT proven

1. O PR #2 tem Actions verificadas no SHA `8a8631dc05820ecc39709520abb259dfa0cc1f0c`: Android job `android`, [run 40608936901](https://github.com/viluadmcontas2-dot/AURA-AI/actions/runs/40608936901), verde; engine jobs `engine-tests (3.12)` e `(3.14)`, [run 40608936975](https://github.com/viluadmcontas2-dot/AURA-AI/actions/runs/40608936975), verdes; design-coverage modo comum [run 40608936886](https://github.com/viluadmcontas2-dot/AURA-AI/actions/runs/40608936886), verde. **Nenhum desses resultados comprova um SHA diferente nem valida 66/66.** Os workflows do SHA final de `main` precisam ser conferidos separadamente.
2. No live Termux Android device run or proof of Claude/Gemini/agy authentication in this runtime session.
3. No real ADB pairing/command, Drive sync, GitHub write, or paid provider request performed as part of these engineering changes.
4. Um APK **debug interno** foi guardado como artefato `12782173282` do run Android do PR #2, mas não foi liberado. Onboarding físico, teclado/voz, armazenamento, foreground service, capturas e instalação/atualização real continuam sem prova de aparelho.
5. Streaming per-token live CLI events, provider quota enforcement, Android-specific secure token exchange callback, and production-grade monitoring need end-to-end integration/QA before release.

## Required verification / hard stop gates

- Run GitHub Actions `Engine contract CI` on the exact final SHA and capture its job result, test count, failures and logs.
- Execute `python -m unittest discover -s tests -v` on Termux and confirm failure modes using real Android permissions.
- Inspect/certify the Android client integration and screenshots against AURA+lifeOS reference. Never substitute a CLI webview or claim an APK exists without building it.
- For all providers, use no-spend doctor and auth/status checks before explicit opt-in to billing/execution.
- Review destructive GitHub/ADB/files actions, race conditions, secret exposure, and real-device interruption prior to declaring production-ready.

## Deliverable split

**Motor:** this repository, `engine/` and `docs/ENGINE_CONTRACT.md` with 31 test specifications.
**Interface:** design do PR #1 já integrado em `main`; a implementação adicional continua em PRs paralelos, a reconciliar com `docs/ANDROID_HANDOFF.md` sem reescrever o engine.

## Engineering verdict

Source integration: **partial, published and remotely inspected**.
Test pass: **verificado somente no SHA `8a8631d` do PR #2 para os jobs citados; SHA final de `main` ainda requer Actions específicas**.
Device and UX: **unverified**.
Production readiness: **not yet demonstrated**.

This ledger deliberately separates claim, proof, owner and next verification, consistent with AGENTS.md and Superpowers.

## Rastreabilidade mecanicamente validada

- `docs/remote-traceability.json` liga requisito a código, teste, workflow/job ou motivo de “somente aparelho”.
- `scripts/check_traceability.py` valida essa declaração sem parsing de Markdown, com testes adversariais no GitHub Actions.
- Falha RED reproduzida em [run 40621057381](https://github.com/viluadmcontas2-dot/AURA-AI/actions/runs/40621057381); correção GREEN em [run 40621104383](https://github.com/viluadmcontas2-dot/AURA-AI/actions/runs/40621104383), commit `17ad0d991b950f789c1d11ef4c6174721927991f`.
- O gate comprova apenas existência e estrutura dos vínculos, não o comportamento de hardware nem o cumprimento do design.
