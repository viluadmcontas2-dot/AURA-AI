# Engine constraints / falsification gates

| ID | Non-negotiable invariant | Failing case that must be exercised |
|----|--------------------------|-------------------------------------|
| SEC-01 | Every endpoint authenticates | GET /v1/health without bearer returns 401 |
| SEC-02 | Only loopback server | Start with 0.0.0.0 rejected |
| SEC-03 | Workspace confinement including symlinks | ../escape and symlink to outside fail |
| SEC-04 | Deny-by-default side effects | Arbitrary shell before approval never runs |
| SEC-05 | No secrets in logs | API key / bearer absent from events and exceptions |
| JOB-01 | Idempotent submit | Equal key equal request yields same job |
| JOB-02 | Duplicate key different request is conflict | Reuse UUID with new payload rejected |
| JOB-03 | At-most-once claim | Single queued job cannot run twice |
| JOB-04 | Persistent interruption on restart | running -> interrupted, never replayed |
| JOB-05 | Cancellation races | Completed/cancelled cannot be overwritten |
| EVT-01 | Monotonic durable events | restart still yields events after cursor |
| ADB-01 | No fabricated availability | missing adb is unavailable |
| PROVIDER-01 | Offline/no login is explicit | missing claude/gemini key -> actionable error |
| ROUTE-01 | Cheap deterministic intent | "listar dispositivos" -> adb devices no model |
| ROUTE-02 | Ambiguous complex intent | plan via available model, never shell automatically |
| SCHEDULE-01 | Scheduling never bypasses approvals | critical scheduled command remains awaiting_approval |
| UX-01 | Android client not blocked by stderr | result structured even on non-zero exit |
| FIN-01 | Requests requiring API spend not silent | API job risk=spend |
| FILE-01 | Nonprogrammer cannot lose unrelated files | delete requires approval; no full-device root access |
| TRACE-01 | Every claim links to test/CI/device result | no unsupported status "complete" |

Review scenarios: duplicate replay, concurrent approve + cancel, malicious `SKILL.md`, huge output, stalled CLI, broken USB/WiFi, device offline, token leak, unexpected json shape, API 429, GitHub permissions, Android OS killing app, zero storage, screen rotation/IME inset. Test mandatory for every applicable invariant; unverified device-only cases tracked explicitly.
