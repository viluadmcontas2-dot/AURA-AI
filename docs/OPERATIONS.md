# AURA Engine: operational runbook

## Authority, ownership, boundaries
The GitHub repository is canonical. The engine executes **on the phone** in Termux. The Claude visual team owns Android UI. Calls to GitHub/Drive/Anthropic/Google are optional external services, not remote execution hosts. Real-device validation is mandatory before making device compatibility claims.

## Install on Android without a PC

Open Termux. Use its official package repositories. First authenticate GitHub using its normal browser/device flow:

```sh
pkg install -y git python gh android-tools
gh auth login
gh repo clone viluadmcontas2-dot/AURA-AI
cd AURA-AI/engine
python -m unittest discover -s tests -v
python -m aura_engine
```

To keep the server running while using the Android application, configure Android foreground execution/notification and Termux permissions with the application's onboarding. Killing the Termux process interrupts jobs; interrupted jobs are never silently replayed. The engine does NOT require root.

## Connection and authentication

- The engine binds only to `127.0.0.1:8765`, not Wi-Fi or a public server.
- On startup, it creates `~/.aura/engine/token` with mode 0600, a 256-bit random credential.
- The Android application receives the token through an explicitly user-authorised Termux RUN_COMMAND flow; it must store the secret in app-private encrypted storage.
- Never place the token in GitHub, CI logs, screenshots, model prompts or publicly shared commands.
- Every request needs `Authorization: Bearer <token>`. Browser javascript cannot bypass this. Do not set permissive CORS.

Termux [RUN_COMMAND specification](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent): Android caller must request `com.termux.permission.RUN_COMMAND` and the user must enable `allow-external-apps=true` in Termux properties. Permissions and result delivery vary by Termux version; verify on device.

## Official CLI accounts

- Claude CLI: `claude auth login` and `claude auth status`; authenticated in Termux (no PC).
- Gemini CLI: interactive login via `gemini`; `gemini -p '...'` is only for execution after authenticated.
- Antigravity CLI: authenticate once interactively using `agy`; `agy -p '...' --output-format json` for headless execution. Do not enable `--dangerously-skip-permissions`.
- GitHub: `gh auth login` and `gh auth status`, via browser/device login. No personal access token stored in the repository.
- Drive: `rclone config` creates an authenticated remote; engine supports read/list and approved transfer. Never automatically crawl the user's whole Google Drive.

CLI availability does not prove authentication; a headless request can fail with a structured provider error.

## Critical approvals

`read`: automatically queue. `write`, `critical`, `spend`: require immutable-job approval. Before pressing Approve the app must show service, device/repo/path, operation, irreversible effects and (for APIs) potential spend. A scheduled high-risk job still stops for explicit approval.

## Real evidence

```sh
cd AURA-AI/engine
python -m compileall -q aura_engine
python -m unittest discover -s tests -v
python -m aura_engine
```

The tests use temporary paths, no paid model calls, and no modifications to a real device. The actual-device acceptance checklist is separate: authenticated Termux bridge, background lifecycle, IME keyboard, voice, ADB pairing/read-only action, provider login, cancellation, process kill/reopen, GitHub auth, Drive consent and failure cases.

When diagnosing: report job ID, status, error code and relevant redacted event, never the bearer token or entire Android device dump.

## Recovery

If a process died: restart `python -m aura_engine`; unfinished `running` jobs become `interrupted`, not completed. Inspect Activity, outputs and side effects before resubmitting. When ADB disconnects, reauthorize or reconnect through the owner-provided host/port. After a model quota is exhausted, route to available alternate CLI or ask for permission to use an API; do not switch billing mode silently.

## Delivery states
Implementation = files in canonical repo; CI = tests on exact SHA; device validated = on-device capture/log/exit codes. None implies the others.

## Native Android first-run token exchange
When user has granted Termux RUN_COMMAND to this specific app and enabled Termux external commands, invoke `python -m aura_engine.pair --emit-token` through the authorised result-returning service. Do not display the JSON or pass it into an AI model. Plain `python -m aura_engine.pair` reports only safe readiness. The Android client must keep the token in encrypted app-private storage; lost/rotated credentials require user-initiated re-pairing.
