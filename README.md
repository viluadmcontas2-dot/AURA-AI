# AURA AI

**Mobile-native interface, device-local execution.** AURA runs its tools on the owner's Android through Termux. It may call external model endpoints and authorised services, but never requires the owner's PC or a remote execution session.

## Ownership

- **Experience/UI:** authored by the parallel Claude Design Systems work. Do not overwrite UI, design tokens, or navigation without first integrating its source.
- **Engine:** this repository's `engine/`, owned by the local runtime team. Python standard library; no secondary server or third-party API gateway.
- **Contracts:** `docs/ENGINE_CONTRACT.md`, the source of truth for the Android-to-Termux boundary.
- **Canonical authority:** GitHub `main`; no additional branches unless strictly necessary.

## Full engine scope

One extensible runtime, with modules for authenticated device-local RPC, durable jobs, approvals, live events, CLI executors (Claude Code, Gemini CLI, Antigravity), native commands and ADB, API providers (Anthropic/Gemini), GitHub via `gh`, workspace/files, skills, memory, scheduling, cost usage and troubleshooting. Providers remain individually detectable; unavailable ones are not simulated.

**Security:** no unauthenticated socket, no API keys in repo, no arbitrary remote shell by default, no automatic destructive work; a user can deliberately authorise high-risk actions.

## Quick start in Termux

```sh
pkg install python
git clone https://github.com/viluadmcontas2-dot/AURA-AI.git
cd AURA-AI/engine
python -m aura_engine
```

Engine binds to loopback only. On first start a secret is generated under `~/.aura/engine/token` (mode 0600), and API requests require `Authorization: Bearer <token>`. The native Android bridge must be secured with Termux RUN_COMMAND permission and explicit user opt-in.

See `docs/ENGINE_CONTRACT.md` and `docs/OPERATIONS.md` before wiring the Android UI.

## Evidence standard

An adapter being present is not equivalent to authentication, functionality or compatibility on a given Android. CI and actual-device checks are separate gates. Never report tests green or production-ready unless current evidence supports the claim.
