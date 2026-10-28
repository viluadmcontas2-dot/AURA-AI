# AURA Engine traceability matrix

A code symbol and a test method are NOT proof of execution. The machine-readable source for links is `docs/remote-traceability.json`; `scripts/check_traceability.py` and the Actions `AURA traceability gate` job validate structural references. A checked job proves only its own SHA and command, not the Android handset.

| Requirement | Code | Test |
|---|---|---|
| SEC-01 auth all endpoints | engine/aura_engine/server.py | test_SEC_01_authentication_required |
| SEC-02 loopback | server.py | test_SEC_02_only_loopback |
| SEC-03 no traversal/symlink | security.py | test_SEC_03_paths_and_symlinks |
| SEC-04 approvals before shell | security.py,core.py,store.py | test_SEC_04_shell_requires_approval |
| SEC-05 bearer file mode | core.py | test_SEC_05_token_permissions |
| JOB-01 idempotency | store.py | test_JOB_01_idempotency |
| JOB-02 conflicting UUID | store.py | test_JOB_02_idempotency_conflict |
| JOB-03 claim once | store.py | test_JOB_03_job_not_claimed_twice |
| JOB-04 restart interruption | store.py | test_JOB_04_restart_marks_running_interrupted |
| JOB-05 cancellation | core.py,store.py | test_JOB_05_cancel_prevents_execution |
| EVT-01 monotonic durable events | store.py | test_EVT_01_events_durable_and_monotonic |
| ADB-01 missing executable | adapters.py | test_ADB_01_missing_provider_truthful |
| ROUTE-01 cheap deterministic tool | core.py | test_ROUTE_01_local_adb |
| ROUTE-02 open-ended request | core.py | test_ROUTE_02_complex_request_model |
| SCHEDULE-01 approval not bypassed | core.py,store.py | test_SCHEDULE_01_approval_not_bypassed |
| FIN-01 API spend approval | security.py,store.py | test_FIN_01_api_needs_spend_approval |
| FILE-01 destructive delete | security.py | test_FILE_01_delete_is_critical |
| SKILLS portable built-ins | adapters.py and skills/ | test_SKILL_discovery_of_builtin_manifest |
| CLI session resume | adapters.py | test_PROVIDER_resume_uses_exact_session |
| AGY headless status/usage | adapters.py | test_PROVIDER_agy_headless_json_and_usage, test_PROVIDER_agy_non_success_must_fail |
| HTTP approve/cancel UX | server.py | test_HTTP_approval_and_cancel_without_body |
| Token/cache visibility | store.py and server.py | test_COST_usage_is_reported_without_inventing_prices |

Not currently covered by remote unit tests: actual phone's Android permissions, foreground lifecycle, secure token handoff, voice microphone, IME keyboard, UI rendering, vendor CLI availability and authentication, ADB wireless pairing, API billing, native Android APK.

Do not mark release-ready while any material device-specific requirement is unverified.

| PAIR-01 credential bootstrap without DB | security.py,pair.py,core.py | test_PAIR_token_is_stable_without_opening_engine_database |
| JOB-06 bounded parallel workers | core.py,adapters.py,store.py | test_PARALLEL_workers_do_not_share_process_state |
| ROUTE-03 native skills by intent | core.py,adapters.py | test_ROUTE_skill_is_selected_from_natural_language |

Pairing and parallel workers remain **remote CI and Android device unverified** until the actual test run is inspected.

## States and jobs (28/10/2026 UTC)

Use exactly one state per requirement in `docs/remote-traceability.json`: `implementado e comprovado por Actions`; `coberto por teste, Actions pendente`; `implementado, teste remoto ausente`; `somente aparelho`. The JSON checks IDs, source files, test method/class, workflow path, job ID and any claimed Actions URL/SHA/job. There is **no Markdown-table parser**. This is a link-integrity check, not a claim of runtime coverage.

- Engine link examples: `SEC-01` → `engine/aura_engine/server.py` → `engine/tests/test_engine.py::test_SEC_01_authentication_required` → `.github/workflows/engine-ci.yml / engine-tests (3.12, 3.14)`; `JOB-05` → `engine/aura_engine/core.py` → `test_JOB_05_cancel_prevents_execution` → same workflow.
- Android isolated logic examples: `AND-BACKOFF-01` → `android/.../integration/EventBackoff.kt` → `EventBackoffTest.kt` → `android-ci.yml / android`; `AND-SPEND-01` → `SpendParser.kt` → `SpendParserTest.kt` → same job.
- Device-only examples: `DEVICE-RUN-COMMAND`, `DEVICE-KEYSTORE`, `DEVICE-IME-VOICE`. No CI status can upgrade these to physical-device proof.

### Verified historical runs, not a verdict about main

At PR #2 head `8a8631dc05820ecc39709520abb259dfa0cc1f0c` the following passed:

| Workflow | Job | Run | Limit |
|---|---|---|---|
| Android build and unit verification | android | [40608936901](https://github.com/viluadmcontas2-dot/AURA-AI/actions/runs/40608936901) | debug APK artifact 12782173282, not delivered |
| Engine contract CI | engine-tests (3.12) and engine-tests (3.14) | [40608936975](https://github.com/viluadmcontas2-dot/AURA-AI/actions/runs/40608936975) | no device |
| design-coverage | coverage | [40608936886](https://github.com/viluadmcontas2-dot/AURA-AI/actions/runs/40608936886) | ordinary gate green, release gate not green |

The new trace gate caught invalid JSON root (RED [40621057381](https://github.com/viluadmcontas2-dot/AURA-AI/actions/runs/40621057381)) and rejected it correctly after the fix (GREEN [40621104383](https://github.com/viluadmcontas2-dot/AURA-AI/actions/runs/40621104383)). These are prior SHAs, **not the SHA of this document**.

### Remaining coverage is not disguised as complete

- `SEC-05` currently links the token file permission test; that alone does **not** establish secret redaction across every error/log path.
- Tests of absent Termux/token, error redaction, event polling and collector concurrency, activity recreation, immutable approvals and navigation must be matched individually to deterministic JVM tests; the listed backoff test covers only backoff.
- Termux permission, RUN_COMMAND, Keystore, ADB, real account login, vendor CLIs, mic/IME, lifecycle/background, rotation and physical UX remain `somente aparelho`.
- Design/release coverage in PR #3 has 66 items, with **0/66 marked completed** at the inspected snapshot; a normal green `design-coverage` is not permission to release an APK.
- The latest exact SHA must be checked in GitHub Actions after every new push. No "escopo remoto fechado" or production claim follows from a source-only scan.
