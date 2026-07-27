#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
errors = []

port_root = ROOT / "sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/port"
for name in ("Clock.java", "TokenGenerator.java", "AuditSink.java", "SessionMetricsRepository.java"):
    if not (port_root / name).is_file():
        errors.append(f"missing domain port {name}")

session = (ROOT / "sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/session/SessionRegistry.java").read_text()
if "implements SessionMetricsRepository" not in session:
    errors.append("SessionRegistry must implement SessionMetricsRepository")
if "TokenGenerator tokenGenerator" not in session:
    errors.append("SessionRegistry must depend on TokenGenerator")
if "java.util.UUID" in session or "UUID.randomUUID" in session:
    errors.append("SessionRegistry must not create concrete UUID tokens")

dispatcher_path = ROOT / "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/status/RuntimeStatusDispatcher.java"
source_path = ROOT / "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/status/RuntimeStatusSource.java"
concrete_source_path = ROOT / "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/status/BrokerRuntimeStatusSource.java"
for path in (dispatcher_path, source_path, concrete_source_path):
    if not path.is_file(): errors.append(f"missing {path.relative_to(ROOT)}")
if dispatcher_path.is_file():
    dispatcher = dispatcher_path.read_text()
    for forbidden in ("android.", "BrokerActivityRuntime", "BrokerProviderRuntime", "SessionRegistry"):
        if forbidden in dispatcher:
            errors.append(f"RuntimeStatusDispatcher leaks concrete dependency {forbidden}")
    for required in ("Clock", "AuditSink", "RuntimeStatusSource", "LongConsumer"):
        if required not in dispatcher:
            errors.append(f"RuntimeStatusDispatcher missing injected boundary {required}")

service_path = ROOT / "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeBrokerService.java"
service = service_path.read_text()
if "runtimeStatusDispatcher.dispatch(request)" not in service:
    errors.append("RuntimeBrokerService must delegate runtimeStatusV2 to RuntimeStatusDispatcher")
if "RuntimeStatusSnapshot.builder()" in service:
    errors.append("RuntimeBrokerService must not build runtime status snapshots")
if re.search(r"private\s+RuntimeStatusResult\s+runtimeStatusV2", service):
    errors.append("RuntimeBrokerService must not retain runtimeStatusV2 business method")
if "android.os.SystemClock.elapsedRealtime" in service:
    errors.append("RuntimeBrokerService must use injected Clock")
if "new SessionRegistry(SLOT_COUNT, tokenGenerator)" not in service:
    errors.append("RuntimeBrokerService must inject TokenGenerator into SessionRegistry")

if errors:
    print("FAIL domain port and dispatcher checks", file=sys.stderr)
    for error in errors: print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS domain port and dispatcher checks")
