#!/usr/bin/env python3
from __future__ import annotations
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
manifest_path = ROOT / "verification/m4-t18-resource-lifecycle-audit.json"
data = json.loads(manifest_path.read_text(encoding="utf-8"))
errors: list[str] = []
if data.get("schemaVersion") != 1:
    errors.append("resource lifecycle audit must use schemaVersion 1")
required_categories = {"capacity", "cleanup"}
ids: set[str] = set()
for domain in data.get("domains", []):
    domain_id = str(domain.get("id", "")).strip()
    if not domain_id or domain_id in ids:
        errors.append(f"invalid or duplicate domain id: {domain_id!r}")
        continue
    ids.add(domain_id)
    categories: set[str] = set()
    for assertion in domain.get("assertions", []):
        category = str(assertion.get("category", "")).strip()
        path = ROOT / str(assertion.get("path", ""))
        tokens = assertion.get("tokens", [])
        categories.add(category)
        if not path.is_file():
            errors.append(f"{domain_id}: evidence file missing: {path.relative_to(ROOT)}")
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        for token in tokens:
            if token not in text:
                errors.append(f"{domain_id}/{category}: evidence token missing in {path.relative_to(ROOT)}: {token}")
    for missing in sorted(required_categories - categories):
        errors.append(f"{domain_id}: missing required lifecycle category {missing}")

for surface in data.get("querySurfaces", []):
    path = ROOT / surface.get("path", "")
    if not path.is_file():
        errors.append(f"query surface missing: {path.relative_to(ROOT)}")
        continue
    text = path.read_text(encoding="utf-8", errors="replace")
    for token in surface.get("requiredTokens", []):
        if token not in text:
            errors.append(f"{surface.get('id')}: fail-closed token missing: {token}")
    for token in surface.get("forbiddenTokens", []):
        if token in text:
            errors.append(f"{surface.get('id')}: Host fallback pattern returned: {token}")

for reviewed in data.get("reviewedPassThrough", []):
    path = ROOT / reviewed.get("path", "")
    if not reviewed.get("reason"):
        errors.append(f"reviewed pass-through lacks reason: {reviewed}")
    if not path.is_file():
        errors.append(f"reviewed pass-through file missing: {path.relative_to(ROOT)}")
        continue
    text = path.read_text(encoding="utf-8", errors="replace")
    for token in reviewed.get("requiredTokens", []):
        if token not in text:
            errors.append(f"reviewed pass-through evidence missing: {token}")

# Freeze the exact durable Job compensation rule: no best-effort raw persist in startJob.
store = (ROOT / "app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceStore.java").read_text(encoding="utf-8")
start = store.find("boolean startJob(")
stop = store.find("boolean stopJob(", start)
if start < 0 or stop < 0:
    errors.append("VirtualSystemServiceStore startJob boundary missing")
else:
    block = store[start:stop]
    if "try { persist(); } catch" in block or "persist();\n                return false" in block:
        errors.append("startJob contains best-effort persistence outside exact rollback transaction")
    for token in ["MutationSnapshot compensation", "persistOrRestore", "VIRTUAL_JOB_START_ROLLBACK_FAILED"]:
        if token not in block:
            errors.append(f"startJob compensation evidence missing: {token}")

if errors:
    print("FAIL M4-T18 ownership/cleanup checks", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print(f"PASS M4-T18 ownership/cleanup checks: domains={len(ids)} querySurfaces={len(data.get('querySurfaces', []))}")
