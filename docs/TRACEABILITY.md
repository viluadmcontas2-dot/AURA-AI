# AURA Engine traceability matrix

Status is **implemented/CI unverified** until GitHub Actions checks can be read for the exact SHA. Source commits are not test passes.

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
