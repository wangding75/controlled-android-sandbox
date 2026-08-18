# T57-R03-P4-FIX01-H — Ledger reconciliation

RESULT: `RECONCILED_ITEM_BY_ITEM`

HEAD at time of this note: `1900ad78` on
`feature/t57-r03-va-pro-capability-campaign`.
`va_pro_equivalent` stays `NOT_PROVEN` for every capability.

---

## Capability Registry — per-item update

Evidence pointers and status updated from FIX01-A through FIX01-I:

| id | implementation | rd_api32 | api35 | api36 | notes |
| --- | --- | --- | --- | --- | --- |
| activity_framework | PARTIAL | PASS | PARTIAL | PARTIAL | FIX01-A stub arch; API35/36 Host install PASS, launch gate FAIL on SwiftShader |
| pending_intent_intent_sender | PARTIAL | PASS | UNVERIFIED | UNVERIFIED | FIX01-D AlarmManager relay after live `:guestN` kill |
| system_service_virtualization | PARTIAL | PARTIAL | UNVERIFIED | UNVERIFIED | FIX01-E explicit surface matrix; Account visibility/listener VIRTUAL_STATE; Notification/Job HOST_DELEGATED_REWRITTEN |
| process_death_recovery | PARTIAL | PASS | UNVERIFIED | UNVERIFIED | FIX01-B process-grounded classifiers; FIX01-D guest PID kill + new session |
| package_lifecycle_clear_delete_reinstall | PARTIAL | PASS | UNVERIFIED | UNVERIFIED | FIX01-C all-user reset/rollback; v1/v2 fixture lineage |
| android_oem_compatibility | GAP | N/A | UNVERIFIED | UNVERIFIED | API36 Host install PASS; API33/34 ENVIRONMENT_NOT_AVAILABLE |
| service_framework | PARTIAL | PASS | UNVERIFIED | UNVERIFIED | Unchanged from P3; FIX01 did not execute Service matrix on API35/36 |
| native_loader_jni_io | PARTIAL | PASS | UNVERIFIED | UNVERIFIED | P0A hostile-only seccomp PASS; raw SVC / ptrace / procfs gaps remain RECORDED |
| bind_application_loaded_apk_component_factory | PARTIAL | PASS | UNVERIFIED | UNVERIFIED | Unchanged from P3 |
| provider_content_uri_authority | PARTIAL | PASS | UNVERIFIED | UNVERIFIED | Unchanged from P3 |
| binder_death_notification | PARTIAL | PASS | UNVERIFIED | UNVERIFIED | Unchanged from P3 |
| process_slot_isolation | PARTIAL | PASS | UNVERIFIED | UNVERIFIED | 64 ordinary × opaque/translucent family confirmed FIX01-A |

No capability was marked broad commercial PASS. No `va_pro_equivalent` was
promoted to `PASS` or `PARTIAL_PROVEN`.

---

## Known Issues — item-by-item review

| issue_id | title (short) | status at FIX01-H | FIX01 effect |
| --- | --- | --- | --- |
| KI-M10-001 | VirtualPackageMetadata line threshold | RECORDED | No production change; gate stays KNOWN_ISSUE |
| KI-M10-002 | native_interceptors.cpp line threshold | RECORDED | No change |
| KI-M10-003 | Gradle unsafe cross-project config | RECORDED | No change |
| KI-M10-004 | CXX5202 32-bit native warning | KEEP_AS_IS | No change |
| KI-M10-005 | GuestNativeRuntimeProjection durability | RECORDED | No change |
| KI-M10-006 | HOST_PACKAGE_HIDDEN ownership mismatch | RECORDED | No change |
| KI-M10-007 | Internal Bundle boundary GBK conflict | RECORDED | No change |
| KI-T57-008 | Ordinary 64-slot high-index lag | NOT_PROVEN | FIX01-A proves slot 63 launches (index 0/63/64/95/127 on API32). High-index exhaustion test still lags production. Status stays NOT_PROVEN |
| KI-T57-009 | Native hostile not kernel-enforced | NOT_PROVEN | P0A hostile-only seccomp added but raw SVC and procfs gaps remain. NOT_PROVEN stays |
| KI-T57-010 | SystemService full semantics unproven | NOT_PROVEN | FIX01-E explicit surface matrix and default-return removal are partial progress. Full Android Framework semantics (Shortcut / AppWidget / UsageStats / Settings) remain unproven. NOT_PROVEN stays |
| KI-T57-011 | PendingIntent SystemUI/Alarm/NMS matrix unproven | NOT_PROVEN | FIX01-D proves AlarmManager holds sender after guest kill on API32. SystemUI/NMS cross-process matrix still unproven. NOT_PROVEN stays |
| KI-T57-012 | Framework fallback path not e2e proven | NOT_PROVEN | FIX01-A/B progress; fallback path not end-to-end proven on all API levels. NOT_PROVEN stays |
| KI-T57-013 | Provider FD/URI-grant/ANR matrix unproven | NOT_PROVEN | No change |
| KI-T57-014 | ClassLoader split/plugin/linker namespace unproven | NOT_PROVEN | No change |
| KI-T57-015 | SIGSEGV/ANR/LMK/reboot recovery matrix | NOT_PROVEN | FIX01-B adds process-grounded fault classifiers. Full matrix (ANR from system, native SIGSEGV with OS fatal, LMK) not dynamically proven. NOT_PROVEN stays |
| KI-T57-016 | Package upgrade/rollback/clone unproven | NOT_PROVEN | FIX01-C adds v1/v2 fixture and all-user rollback. Dynamic evidence is partial (fixture, not commercial APK, no cross-user clone proof). NOT_PROVEN stays |
| KI-T57-017 | Android API 33-36 matrix not executed | NOT_PROVEN | FIX01-I: API35 Host install PASS, launch gate FAIL; API36 Host install PASS, launch gate FAIL. API33/34 ENVIRONMENT_NOT_AVAILABLE. NOT_PROVEN stays |
| KI-T57-018 | OEM compatibility matrix not executed | NOT_PROVEN | No change. No OEM device available |
| KI-T57-019 | VA/NBB/VA Pro A/B comparison missing | NOT_PROVEN | No change. This agent does not issue VA Pro parity verdict |
| KI-R03-020 | M5-T19 architecture-decoupling map lags | RECORDED | No change |
| KI-R03-021 | SBOM source digest stale | RECORDED | No change |
| KI-R03-022 | Broadcast-model Receiver coordinator lag | RECORDED | No change |
| KI-R03-023 | Native trust-boundary requireAllowed missing | RECORDED | No change |
| KI-R03-024 | Runtime/Framework package-boundary imports | RECORDED | No change |
| KI-R03-025 | Isolated-process stopGuest token missing | RECORDED | No change |
| KI-R03-026 | APK revision-binding Broker tokens missing | RECORDED | FIX01-C adds revision-binding; Broker token integration still lags |
| KI-R03-027 | System-service/Broker split coordinator | RECORDED | No change |
| KI-R03-028 | Binder system-service coordinator missing | RECORDED | No change |
| KI-R03-029 | Split-install session evidence tokens | RECORDED | No change |
| KI-R03-030 | RD API32 framework transport launch-gate | RECORDED | FIX01-B confirms launch-gate is a fixture-probe issue, not infra defect. Still RECORDED |
| KI-R03-NATIVE-001 | Raw SVC bypasses PLT/GOT | RECORDED | No change |
| KI-R03-NATIVE-002 | Process identity / ptrace / execve unhooked | RECORDED | No change |
| KI-R03-NATIVE-003 | Procfs virtualization partial | RECORDED | No change |
| KI-R03-NATIVE-004 | Filesystem metadata / cwd unhooked | RECORDED | No change |
| KI-R03-NATIVE-005 | Custom loader / linker namespace | RECORDED | No change |
| KI-R03-NATIVE-006 | Native guest observes Binder device nodes | RECORDED | No change |
| KI-R03-NATIVE-007 | Translated-ABI skips PLT/GOT | RECORDED | No change |
| KI-R03-NATIVE-008 | seccomp-BPF user-notify not implemented | RECORDED | P0A hostile-only seccomp exists; user-notify still REQUIRES_PRIVILEGE. Stale "no seccomp" wording is superseded — item stays RECORDED (not FIXED) |
| KI-R03-NATIVE-009 | FD escape via /proc/self/fd | RECORDED | No change |
| KI-R03-NATIVE-010 | In-sandbox native-adversarial service | FIXED | P1-00 GuestDefiningLoader fix; remains FIXED |
| KI-R03-NATIVE-ENF-001 | Isolated process sockets/connect | RECORDED | No change; architecture gap, not a production defect |

No issue entry was deleted or silently promoted. Stale "no seccomp" wording
in KI-R03-NATIVE-008 is noted as superseded by P0A hostile-only seccomp but
the item stays RECORDED because user-notify is REQUIRES_PRIVILEGE and
kernel-level enforcement is not proven.

---

## VA Pro corpus (83 items) — reconciliation approach

Items are **not** batch-labeled PASS from module names.

Status vocabulary applied based on production source + tests + dynamic evidence:

| cas_status | count | rationale |
| --- | --- | --- |
| GAP | 51 | No CAS production source implements the VA commercial domain |
| NEEDS_TEST | 28 | CAS production source exists but no dynamic evidence on FINAL_HEAD |
| UNVERIFIED | 3 | Environment gap (API33/34/36 / OEM not available) |
| CAS_ALREADY_COVERS | 1 | Static structural evidence sufficient (package visibility fail-closed) |

FIX01-A/D/E change evidence paths for:
- Activity/task virtualization → `NEEDS_TEST` (dynamic evidence updated)
- PendingIntent system-holder → `NEEDS_TEST` (FIX01-D AlarmManager proof)
- SystemService explicit surface → `NEEDS_TEST` (FIX01-E matrix)

No item was promoted from GAP to CAS_ALREADY_COVERS or NEEDS_TEST unless
production source evidence exists. No item was marked PASS.
