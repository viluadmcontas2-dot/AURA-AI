# Android UI handoff, source of truth for Claude Design Systems

Engine ownership is separate from UX ownership. Android app SHALL NOT launch its own cloud execution service or parse scraped terminal screens. It connects to the local AURA Engine via secure Termux authorization.

## Onboarding for a first-time user
1. Welcome: "Seu telefone é o ambiente de execução." Offer a short explanation of optional model network access.
2. Check that Termux is present; guide installation only if absent. Termux RUN_COMMAND requires an explicit Android permission and the user-enabled Termux property. Do not silently grant either.
3. Establish protected loopback engine connection and one-time user-authorised credential transfer. Secure token in Android Keystore/app-private encrypted storage. Never display the token.
4. Connections: Claude login (open official browser, passkey), optional Gemini/Antigravity/GitHub (OAuth/device flow), optional Drive/rclone. Show Connected / Needs login / Not installed / Offline states based on actual detection.
5. Choose voice/text mode, accept least-privilege media/storage permissions on demand.
6. Begin a natural-language task. Display typed plan and approvals only when consequential.

## Four primary destinations
- Home: natural language and voice, compact live status, contextual next step.
- Projects: workspace, source repo, latest state, artifacts and resume button.
- Devices: attached/paired authorised devices, battery/log/capture controls and active authorization state.
- Activity: persistent execution history, approvals, cancelled/interrupted/failed, actual evidence and cost/usage details.

## Required UI behaviors
- Single conversation state drives keyboard, voice input, and task context. On Android IME open: composer stays above keyboard; scroll remains anchored to newest message, no hidden approval buttons; handle gesture nav safe areas and screen rotation.
- Do not claim task success when job is only queued or running.
- Approval UI displays immutable request payload in a human-readable summary and high-risk target; requires explicit gesture; do not auto-approve via a prompt or model.
- Explanations use simple Brazilian Portuguese, a short verdict and one next action; logs behind expansion.
- Context isn't reset when navigating away. On app resume, call GET /v1/events?since=... and GET /v1/jobs/{id} to reconcile states.
- Never infer connectivity from IP ping. ADB readiness is independently verified by authorised ADB output.
- Phone background limitations are expected: expose persistent notification for user-visible long-running jobs, use appropriate Android APIs, show interrupted state after OS process death.

## Transport (v1)
All calls to `http://127.0.0.1:8765` require bearer token. No cross-device HTTP server is exposed; ADB itself connects outward to the authorized target. JSON contract: docs/ENGINE_CONTRACT.md.

UI team must implement secure pairing; the backend provides `python -m aura_engine.pair --emit-token` for use ONLY from a permission-approved Termux RUN_COMMAND result callback; store the returned token in the Android Keystore. No APK or screenshot verification is claimed by the engine team.

## Real-device matrix
Android 11/13/15 when available; portrait, landscape, small screen, large phone, split-screen, keyboard insets; background force stop and restart; airplane mode; wrong credential; ADB paired/unauthorized/offline; GitHub login expired; Claude exhausted limits; Gemini/agy missing; low storage. Separate perceptual and functional reviews.
