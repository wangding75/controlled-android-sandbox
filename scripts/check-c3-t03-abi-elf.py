#!/usr/bin/env python3
"""C3-T03 static gate for four-ABI APKs and 16 KiB ELF load alignment."""

from __future__ import annotations

import argparse
import json
import re
import struct
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
PAGE_16K = 0x4000
ELF_MAGIC = b"\x7fELF"
MACHINES = {
    "arm64-v8a": (2, 183, "AArch64"),
    "armeabi-v7a": (1, 40, "ARM"),
    "x86_64": (2, 62, "x86-64"),
    "x86": (1, 3, "x86"),
}
PT_LOAD = 1
PT_DYNAMIC = 2
DT_NEEDED = 1
DT_STRTAB = 5
DT_STRSZ = 10


class GateError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise GateError(message)


def u16(data: bytes, offset: int) -> int:
    return struct.unpack_from("<H", data, offset)[0]


def u32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def u64(data: bytes, offset: int) -> int:
    return struct.unpack_from("<Q", data, offset)[0]


def read_c_string(data: bytes, offset: int, limit: int) -> str:
    if offset < 0 or offset >= len(data) or limit < 0:
        return ""
    end = min(len(data), offset + limit)
    stop = data.find(b"\0", offset, end)
    if stop < 0:
        stop = end
    return data[offset:stop].decode("utf-8", errors="replace")


def vaddr_to_offset(loads: list[dict[str, int]], address: int) -> int | None:
    for segment in loads:
        start = segment["vaddr"]
        end = start + segment["filesz"]
        if start <= address < end:
            return segment["offset"] + (address - start)
    return None


def parse_elf(data: bytes, abi: str, path: str) -> dict[str, Any]:
    if len(data) < 64 or data[:4] != ELF_MAGIC:
        fail(f"{path}: missing ELF magic")
    elf_class = data[4]
    endian = data[5]
    if endian != 1:
        fail(f"{path}: only little-endian ELF is supported")
    expected_class, expected_machine, machine_name = MACHINES[abi]
    if elf_class not in (1, 2):
        fail(f"{path}: unsupported ELF class {elf_class}")
    if elf_class == 2:
        if len(data) < 64:
            fail(f"{path}: truncated ELF64 header")
        machine = u16(data, 18)
        phoff = u64(data, 32)
        phentsize = u16(data, 54)
        phnum = u16(data, 56)
        expected_phentsize = 56
    else:
        if len(data) < 52:
            fail(f"{path}: truncated ELF32 header")
        machine = u16(data, 18)
        phoff = u32(data, 28)
        phentsize = u16(data, 42)
        phnum = u16(data, 44)
        expected_phentsize = 32
    if elf_class != expected_class:
        fail(f"{path}: ELF class {elf_class} does not match {abi}")
    if machine != expected_machine:
        fail(f"{path}: ELF machine {machine} does not match {abi}")
    if phentsize < expected_phentsize or phoff + phentsize * phnum > len(data):
        fail(f"{path}: invalid program-header table")

    loads: list[dict[str, int]] = []
    dynamic: list[tuple[int, int]] = []
    for index in range(phnum):
        base = phoff + index * phentsize
        if elf_class == 2:
            p_type = u32(data, base)
            p_offset = u64(data, base + 8)
            p_vaddr = u64(data, base + 16)
            p_filesz = u64(data, base + 32)
            p_align = u64(data, base + 48)
        else:
            p_type = u32(data, base)
            p_offset = u32(data, base + 4)
            p_vaddr = u32(data, base + 8)
            p_filesz = u32(data, base + 16)
            p_align = u32(data, base + 28)
        if p_type == PT_LOAD:
            loads.append({
                "offset": p_offset,
                "vaddr": p_vaddr,
                "filesz": p_filesz,
                "align": p_align,
            })
        elif p_type == PT_DYNAMIC:
            entry_size = 16 if elf_class == 2 else 8
            if p_offset + p_filesz > len(data) or p_filesz % entry_size:
                fail(f"{path}: invalid PT_DYNAMIC")
            for cursor in range(p_offset, p_offset + p_filesz, entry_size):
                if elf_class == 2:
                    tag = u64(data, cursor)
                    value = u64(data, cursor + 8)
                else:
                    tag = u32(data, cursor)
                    value = u32(data, cursor + 4)
                dynamic.append((tag, value))
                if tag == 0:
                    break

    if not loads:
        fail(f"{path}: no PT_LOAD segments")
    alignment_rows: list[dict[str, int | bool]] = []
    for index, segment in enumerate(loads):
        align = segment["align"]
        congruent = (
            segment["offset"] % PAGE_16K == segment["vaddr"] % PAGE_16K
        )
        aligned = align >= PAGE_16K and align % PAGE_16K == 0 and congruent
        alignment_rows.append({
            "index": index,
            "p_offset": segment["offset"],
            "p_vaddr": segment["vaddr"],
            "p_align": align,
            "offset_vaddr_congruent_16k": congruent,
            "pass": aligned,
        })
        if not aligned:
            fail(
                f"{path}: PT_LOAD[{index}] is not 16 KiB aligned "
                f"(offset=0x{segment['offset']:x}, vaddr=0x{segment['vaddr']:x}, "
                f"align=0x{align:x})"
            )

    needed: list[str] = []
    string_table = next((value for tag, value in dynamic if tag == DT_STRTAB), None)
    string_size = next((value for tag, value in dynamic if tag == DT_STRSZ), None)
    if string_table is not None:
        string_offset = vaddr_to_offset(loads, string_table)
        if string_offset is None:
            fail(f"{path}: DT_STRTAB is outside PT_LOAD")
        if string_size is None:
            string_size = len(data) - string_offset
        for tag, value in dynamic:
            if tag != DT_NEEDED:
                continue
            if value >= string_size:
                fail(f"{path}: DT_NEEDED offset {value} exceeds DT_STRSZ {string_size}")
            name = read_c_string(data, string_offset + value, string_size - value)
            if not name:
                fail(f"{path}: empty DT_NEEDED entry")
            if name.startswith("/") or ".." in Path(name).parts:
                fail(f"{path}: unsafe DT_NEEDED {name!r}")
            needed.append(name)

    return {
        "path": path,
        "abi": abi,
        "elf_class": 64 if elf_class == 2 else 32,
        "machine": machine,
        "machine_name": machine_name,
        "pt_load_count": len(loads),
        "pt_loads": alignment_rows,
        "dt_needed": needed,
        "size": len(data),
        "status": "PASS",
    }


def read_text(relative: str) -> str:
    path = ROOT / relative
    return path.read_text(encoding="utf-8", errors="replace") if path.is_file() else ""


def source_checks() -> list[dict[str, Any]]:
    checks = {
        "design": (
            "docs/review/C3_T03_ABI_16KB_NATIVE_MEDIA_DESIGN.md",
            ("C3-T03", "16 KB", "ENVIRONMENT_NOT_AVAILABLE"),
        ),
        "fixture_cmake": (
            "fixture-basic/src/main/cpp/CMakeLists.txt",
            ("c3_t03_native.cpp", "mediandk", "max-page-size=16384"),
        ),
        "native_cmake": (
            "sandbox-native/src/main/cpp/CMakeLists.txt",
            ("max-page-size=16384",),
        ),
        "companion_cmake": (
            "sandbox-companion32/src/main/cpp/CMakeLists.txt",
            ("max-page-size=16384",),
        ),
        "activity": (
            "fixture-basic/src/main/java/com/warden/controlledsandbox/fixture/C3T03NativeMediaActivity.java",
            ("nativeSurfaceBufferRoundTrip", "nativeCodecProbe", "C3_T03_NATIVE_MEDIA_RESULT"),
        ),
        "runner": (
            "tools/capability/run_c3_t03_rd.py",
            ("resolve_rd_environment", "RD测试", "C3-T03"),
        ),
        "fixture32_route": (
            "fixture-compat32/build.gradle",
            ("fixture-basic/src/main/cpp/CMakeLists.txt", "armeabi-v7a", "x86"),
        ),
    }
    rows: list[dict[str, Any]] = []
    for name, (relative, needles) in checks.items():
        content = read_text(relative)
        missing = [needle for needle in needles if needle not in content]
        rows.append({"check": name, "file": relative, "missing": missing, "status": "PASS" if not missing else "FAIL"})
    runner = read_text("tools/capability/run_c3_t03_rd.py")
    forbidden = sorted(set(re.findall(r"(?:127\.0\.0\.1|localhost|emulator-\d+):\d+", runner)))
    rows.append({
        "check": "runner_no_hardcoded_adb_endpoint",
        "forbidden_literals": forbidden,
        "status": "PASS" if not forbidden else "FAIL",
    })
    return rows


def inspect_apk(path: Path, allowed: set[str], required: set[str]) -> dict[str, Any]:
    if not path.is_file():
        fail(f"missing APK: {path}")
    rows: list[dict[str, Any]] = []
    observed_abis: set[str] = set()
    with zipfile.ZipFile(path) as archive:
        for name in sorted(archive.namelist()):
            match = re.fullmatch(r"lib/([^/]+)/([^/]+\.so)", name)
            if not match:
                continue
            abi, library = match.groups()
            observed_abis.add(abi)
            if abi not in MACHINES:
                fail(f"{path.name}: unsupported packaged ABI {abi}")
            parsed = parse_elf(archive.read(name), abi, f"{path.name}!/{name}")
            rows.append(parsed)
    if observed_abis != allowed:
        fail(f"{path.name}: ABI set {sorted(observed_abis)} != expected {sorted(allowed)}")
    observed_names = {Path(row["path"]).name for row in rows}
    missing = sorted(required - observed_names)
    if missing:
        fail(f"{path.name}: required native libraries missing {missing}")
    return {
        "apk": str(path.relative_to(ROOT).as_posix()),
        "sha256": __import__("hashlib").sha256(path.read_bytes()).hexdigest(),
        "allowed_abis": sorted(allowed),
        "observed_abis": sorted(observed_abis),
        "native_libraries": rows,
        "status": "PASS",
    }


def main() -> int:
    global ROOT
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument(
        "--report",
        type=Path,
        default=ROOT / "verification/catch-up/C3-T03/c3-t03-abi-report.json",
    )
    args = parser.parse_args()
    ROOT = args.root.resolve()
    report: dict[str, Any] = {
        "schema_version": 1,
        "task_id": "C3-T03",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "page_alignment_bytes": PAGE_16K,
        "status": "FAIL",
        "source_checks": [],
        "artifacts": [],
        "errors": [],
    }
    try:
        report["source_checks"] = source_checks()
        source_failures = [row for row in report["source_checks"] if row["status"] != "PASS"]
        if source_failures:
            fail("source contract checks failed: " + ", ".join(row["check"] for row in source_failures))
        lock = json.loads((ROOT / "build-environment.lock.json").read_text(encoding="utf-8"))
        config = lock.get("deviceLabBuild") or {}
        if config.get("schemaVersion") != 1:
            fail("deviceLabBuild schemaVersion must be 1")
        for item in config.get("artifacts", []):
            artifact = inspect_apk(
                ROOT / str(item["apk"]),
                set(map(str, item["allowedAbis"])),
                set(map(str, item["requiredNativeLibraries"])),
            )
            artifact["id"] = item["id"]
            artifact["application_id"] = item["applicationId"]
            report["artifacts"].append(artifact)
        report["status"] = "PASS"
    except (GateError, OSError, KeyError, ValueError, zipfile.BadZipFile, struct.error) as exc:
        report["errors"].append(str(exc))
    report["report_path"] = str(args.report.resolve().relative_to(ROOT).as_posix())
    args.report.resolve().parent.mkdir(parents=True, exist_ok=True)
    args.report.resolve().write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    if report["status"] != "PASS":
        print("FAIL C3-T03 ELF/16KB static gate", file=sys.stderr)
        for error in report["errors"]:
            print(" - " + error, file=sys.stderr)
        return 1
    print(
        "PASS C3-T03 ELF/16KB static gate: "
        + ", ".join(f"{item['id']}={len(item['native_libraries'])} ELF" for item in report["artifacts"])
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
