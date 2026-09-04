"""Static audit for API37 reflection, static-final, Unsafe and JNI writes."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any, Iterable


SOURCE_SUFFIXES = {".java", ".kt", ".c", ".cc", ".cpp", ".h", ".hpp"}
EXCLUDED_PARTS = {".git", ".gradle", "build", "out", "ref"}

FIELD_WRITE_RE = re.compile(
    r"\b(?:[A-Za-z_$][\w$]*\.)?(?:[A-Za-z_$][\w$]*Field|field|f|values|added|views|global|"
    r"instrumentation|mPm|cacheField|serviceCacheField|currentField|targetField|sourceField)"
    r"\.set(?:Boolean|Byte|Char|Double|Float|Int|Long|Short)?\s*\("
)
ACCESS_RE = re.compile(r"\b(?:[A-Za-z_$][\w$]*\.)?(?:Field|field|f)\.setAccessible\s*\(")
JNI_STATIC_RE = re.compile(r"\bSetStatic(?:Object|Boolean|Byte|Char|Short|Int|Long|Float|Double)Field\s*\(")
UNSAFE_STATIC_RE = re.compile(r"\b(?:unsafe|UNSAFE|sUnsafe)\s*\.\s*(?:put|compareAndSwap|putOrdered)[A-Za-z]*\s*\(")
VARHANDLE_WRITE_RE = re.compile(
    r"\b(?:set|setRelease|setOpaque|setVolatile|compareAndSet|weakCompareAndSet)[A-Za-z]*\s*\("
)


def _is_source(path: Path, root: Path) -> bool:
    if path.suffix.lower() not in SOURCE_SUFFIXES:
        return False
    try:
        relative_parts = path.resolve().relative_to(root.resolve()).parts
    except ValueError:
        return False
    return not any(part in EXCLUDED_PARTS for part in relative_parts)


def _classification(path: Path, line: str, *, static: bool) -> str:
    normalized = line.lower()
    if static and path.name == "BuildIdentityHook.java":
        # Build.BRAND/MODEL/etc. are public static final fields in the API37
        # android.jar. This is a source-level risk record, not a PASS claim.
        return "FRAMEWORK_STATIC_FINAL"
    if static and "android." in normalized:
        return "FRAMEWORK_STATIC_WRITE_CANDIDATE"
    if static:
        return "STATIC_WRITE_CANDIDATE"
    if "testharness" in str(path).lower() or "src\\test" in str(path).lower():
        return "TEST_ONLY_INSTANCE_WRITE"
    return "INSTANCE_WRITE"


def audit_sources(root: Path) -> dict[str, Any]:
    root = root.resolve()
    records: list[dict[str, Any]] = []
    source_count = 0
    for path in sorted(root.rglob("*")):
        if not path.is_file() or not _is_source(path, root):
            continue
        source_count += 1
        try:
            lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            continue
        relative = path.relative_to(root).as_posix()
        for number, line in enumerate(lines, start=1):
            field_write = FIELD_WRITE_RE.search(line)
            access = ACCESS_RE.search(line)
            jni = JNI_STATIC_RE.search(line)
            unsafe = UNSAFE_STATIC_RE.search(line)
            varhandle = "VarHandle" in "\n".join(lines[max(0, number - 8):number + 2]) and VARHANDLE_WRITE_RE.search(line)
            if not any((field_write, access, jni, unsafe, varhandle)):
                continue
            static = bool(field_write and re.search(r"\.set(?:Boolean|Byte|Char|Double|Float|Int|Long|Short)?\s*\(\s*null\b", line))
            if jni:
                kind = "JNI_STATIC_FIELD_WRITE"
                classification = "JNI_STATIC_FINAL_RISK"
            elif unsafe:
                kind = "UNSAFE_FIELD_WRITE"
                classification = "UNSAFE_STATIC_WRITE_CANDIDATE"
            elif varhandle:
                kind = "VARHANDLE_WRITE"
                classification = "VARHANDLE_STATIC_WRITE_CANDIDATE"
            elif field_write:
                kind = "REFLECTION_FIELD_WRITE"
                classification = _classification(path, line, static=static)
            else:
                kind = "REFLECTION_ACCESS"
                classification = "SET_ACCESSIBLE"
            records.append(
                {
                    "path": relative,
                    "line": number,
                    "kind": kind,
                    "classification": classification,
                    "static_receiver": static,
                    "snippet": line.strip()[:300],
                }
            )

    field_writes = [item for item in records if item["kind"] == "REFLECTION_FIELD_WRITE"]
    framework_final = [
        item for item in records if item["classification"] == "FRAMEWORK_STATIC_FINAL"
    ]
    jni_final = [
        item for item in records if item["kind"] == "JNI_STATIC_FIELD_WRITE"
        and item["classification"] == "JNI_STATIC_FINAL_RISK"
    ]
    return {
        "source_root": str(root),
        "source_file_count": source_count,
        "records": records,
        "counts": {
            "STATIC_FINAL_WRITE_SCAN_TOTAL": len(field_writes),
            "FRAMEWORK_STATIC_FINAL_WRITE_COUNT": len(framework_final),
            "JNI_STATIC_FINAL_RISK_COUNT": len(jni_final),
            "reflection_access_sites": sum(item["kind"] == "REFLECTION_ACCESS" for item in records),
            "reflection_field_write_sites": len(field_writes),
            "jni_static_field_sites": sum(item["kind"] == "JNI_STATIC_FIELD_WRITE" for item in records),
            "unsafe_write_sites": sum(item["kind"] == "UNSAFE_FIELD_WRITE" for item in records),
            "varhandle_write_sites": sum(item["kind"] == "VARHANDLE_WRITE" for item in records),
        },
    }


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=str(Path(__file__).resolve().parents[2]))
    parser.add_argument("--json", default="", help="also write the result to this path")
    return parser


def main() -> int:
    args = _parser().parse_args()
    payload = audit_sources(Path(args.root))
    if args.json:
        destination = Path(args.json).resolve()
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({"counts": payload["counts"], "records": payload["records"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
