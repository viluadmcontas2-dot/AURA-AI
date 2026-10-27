# AURA AI — Implementation evidence ledger

Date: 2026-10-27. Source of truth: `viluadmcontas2-dot/AURA-AI` on `main`.

## Implemented in canonical repository (source-level evidence)

- Policy: risk classification, workspace confinement, token creation and Android pairing support.
- Durable engine: SQLite queue, idempotency, events, scoped memory, schedules, recover-on-restart and bounded parallel workers.
- Execution adapters: native/system/files/shell, Android ADB, GitHub (gh/git), Drive (rclone), Claude Code, Gemini CLI, Antigravity CLI, Claude/Gemini APIs and portable Agent Skills.
- Runtime interface: loopback HTTP with bearer authentication, job lifecycle, approval/cancel, capabilities, event polling, memory, schedules and actual reported usage.
- Novice experience support: local deterministic routing, available skill selection, doctor with actionable warnings and native-app handoff.
- Test suite: `engine/tests/test_engine.py` contains 31 named tests as of the source inspection; CI workflow runs compile + test on Python 3.12 and 3.14.
- Portable skills: 3 bundled `SKILL.md` workflows with lazy metadata discovery.

## Explicitly NOT proven

1. GitHub Actions outcomes: the connected GitHub interface returned **zero status entries** for commits queried and its PR-only workflow lookup returned zero runs. This is **not** proof that CI passed or failed. A CI run with an exit code is needed before marking checks green.
2. No live Termux Android device run or proof of Claude/Gemini/agy authentication in this runtime session.
3. No real ADB pairing/command, Drive sync, GitHub write, or paid provider request performed as part of these engineering changes.
4. No native Android APK, keyboard/voice flow, storage grant, foreground service, or perceptual screenshot was implemented/verified by the engine team. Claude Design Systems owns that separate surface.
5. Streaming per-token live CLI events, provider quota enforcement, Android-specific secure token exchange callback, and production-grade monitoring need end-to-end integration/QA before release.

## Required verification / hard stop gates

- Run GitHub Actions `Engine contract CI` on the exact final SHA and capture its job result, test count, failures and logs.
- Execute `python -m unittest discover -s tests -v` on Termux and confirm failure modes using real Android permissions.
- Inspect/certify the Android client integration and screenshots against AURA+lifeOS reference. Never substitute a CLI webview or claim an APK exists without building it.
- For all providers, use no-spend doctor and auth/status checks before explicit opt-in to billing/execution.
- Review destructive GitHub/ADB/files actions, race conditions, secret exposure, and real-device interruption prior to declaring production-ready.

## Deliverable split

**Motor:** this repository, `engine/` and `docs/ENGINE_CONTRACT.md` with 31 test specifications.
**Interface:** Claude's separate UI work, to integrate by `docs/ANDROID_HANDOFF.md` without rewriting the engine.

## Engineering verdict

Source integration: **partial, published and remotely inspected**.
Test pass: **unverified**.
Device and UX: **unverified**.
Production readiness: **not yet demonstrated**.

This ledger deliberately separates claim, proof, owner and next verification, consistent with AGENTS.md and Superpowers.
