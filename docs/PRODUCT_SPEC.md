# AURA AI product spec v1

## Mission
A voice-first, minimal, premium Android assistant able to carry out arbitrary **authorised** work on the owner's device using Termux and locally installed tools. Accessible to a nonprogrammer. Visual design owned by the parallel Claude Design Systems work.

## Jobs to be done
- Create, inspect, repair, test and publish software by speech, with tools and skills chosen according to demonstrated capabilities.
- Control authorised Android devices by ADB, using real output, reconnect state and explicit consent.
- Browse, organise, analyse, generate and export local project files and artifacts; integrate cloud services through official authentication.
- Use authenticated Claude Code, Gemini CLI, optional Antigravity-compatible CLI, Anthropic/Gemini APIs, deterministic scripts and skills; minimise repeated model calls.
- Continue relevant session/workspace state and present results, failures, evidence and the one next useful action.

## Experience ownership
UX: four primary destinations, Conversation, Projects, Devices, Activity; contextual secondary pages for Skills, Connections, Costs, Approvals, Settings. Voice and keyboard use the same conversation state. Keyboard IME must resize content safely, maintain focus/scroll, and never hide controls. All screens support empty/loading/error/offline/permission-denied/reconnecting/completed states.

## Engine ownership
No UI is implemented by this team. A separate engine listens on loopback, secured by a bearer secret shared only with the authorised Android client, and publishes structured job state, events, capabilities and persisted results.

## Success criteria
P1. Speech or text -> intent -> typed task -> permission -> locally running executor -> evidence-rich result, no terminal exposure.
P2. Multi-provider routing with availability, tool costs and per-project preference, avoiding model for deterministic commands.
P3. Durable jobs and cancellation, workspaces, outputs, event replay and meaningful failures.
P4. No unsafe write, external spend, device modification or arbitrary command without an explicit approval tied to the exact payload.
P5. Android UI and runtime validated separately by real-device tests. No fake capability shown as connected.

## Outside engine ownership
The Claude design team builds the UI. OAuth/browser login UX must be implemented in Android UI; never embed third-party passwords or access tokens into shell command strings. Android background lifecycle and user-facing notification implementation require an Android app and device-level verification.
