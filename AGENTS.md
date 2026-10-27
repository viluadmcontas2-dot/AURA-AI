# AURA AI — Agent Engineering Contract

This repository is canonical. Follow Codex Engineering Guardrails and Superpowers, especially brainstorming, writing-plans, test-driven-development, systematic-debugging, verification-before-completion.

## Non-negotiable product invariants

1. Android/Termux performs every device-local execution; no personal PC, remote Claude session, or mandatory cloud execution server.
2. External models, GitHub, Drive and other APIs are optional network services, not process hosts.
3. Claude Design Systems owns visual UI; engine team owns `engine/` and `docs/ENGINE_CONTRACT.md`. Do not independently build a competing interface.
4. User-facing language: Portuguese; command/event protocol: stable English identifiers.
5. A returned result must be grounded in exit status or API response; missing tools must report unavailable.
6. Destructive work and spending require explicit immutable-job approval, with targets and consequences visible.
7. Secrets must not appear in Git, responses, logs, error messages or model prompts.
8. Repository writes use the canonical `main` unless critical isolation is required. Never overwrite concurrent work.
9. Validate on GitHub Actions and, separately, on the actual Android/Termux target. No local test equals device proof.
10. Attach acceptance requirement IDs to tests and document residual risk; nothing is labelled production-ready without evidence.

## Documents

- `docs/PRODUCT_SPEC.md` — product and UX boundaries
- `docs/ENGINE_CONTRACT.md` — interface-independent wire contract
- `docs/CONSTRAINTS.md` — hard constraints and negative scenarios
- `docs/superpowers/plans/2026-10-27-engine.md` — executable plan and verification matrix

## Method

Diagnosis -> design -> plan -> test RED -> implement GREEN -> static/security checks -> remote CI -> real-device validation -> independent review. Every engine job has a persisted request ID, idempotency key, approval policy, status, event and diagnostic record. Do not mistake a passed unit test for successful Android integration.
