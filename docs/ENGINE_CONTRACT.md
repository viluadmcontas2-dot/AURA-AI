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
