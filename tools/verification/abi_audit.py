"""Static ABI, ELF, and native packaging audit for the Android build outputs.

The audit intentionally consumes Gradle outputs rather than source-directory
assumptions.  It writes verbose evidence under ``out/verification`` and keeps
the tracked result compact: JSON inventories, tool receipts, and a summary.
The reusable pass/fail contract lives in :mod:`matrix_validator`.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import zipfile
from collections import Counter, defaultdict
from pathlib import Path
from typing import Iterable, Mapping, Sequence

if __package__ in {None, ""}:
    _ROOT = Path(__file__).resolve().parents[2]
    if str(_ROOT) not in sys.path:
        sys.path.insert(0, str(_ROOT))

from tools.verification.matrix_validator import (
    ABI_ELF_CONTRACT,
    ABI_NAMES,
    MatrixAccountingError,
    validate_abi_matrix,
    validate_native_inventory,
)


KNOWN_ABIS = tuple(sorted(ABI_NAMES))
ABI_PATH_RE = re.compile(r"(?:^|[\\/])(armeabi-v7a|arm64-v8a|x86_64|x86)(?:[\\/]|$)")
ARCH_MACROS = ("__x86_64__", "__i386__", "__aarch64__", "__arm__")
ARCH_ASM_MARKERS = ("syscall", "__NR_", "SYS_", "svc #0", "int $0x80", "AUDIT_ARCH_")

SYSTEM_NEEDED = {
    "libandroid.so",
    "libaaudio.so",
    "libcamera2ndk.so",
    "libc.so",
    "libdl.so",
    "libEGL.so",
    "libGLESv1_CM.so",
    "libGLESv2.so",
    "libjnigraphics.so",
    "liblog.so",
    "libm.so",
    "libmediandk.so",
    "libnativewindow.so",
    "libneuralnetworks.so",
    "libOpenMAXAL.so",
    "libOpenSLES.so",
    "libvulkan.so",
    "libz.so",
    "ld-android.so",
}


def _relative(path: Path, root: Path) -> str:
    return path.resolve().relative_to(root.resolve()).as_posix()


def _slug(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "_", value).strip("._") or "item"


def _raw_file(evidence: Path, prefix: str, value: str) -> Path:
    digest = hashlib.sha256(value.encode("utf-8")).hexdigest()[:12]
    label = _slug(value)[:100]
    return evidence / "raw" / f"{_slug(prefix)}-{label}-{digest}.txt"


def _write_json(path: Path, payload: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return path.read_text(encoding="utf-8", errors="replace")


def _module_from_path(path: Path, root: Path) -> str:
    parts = Path(_relative(path, root)).parts
    if "build" in parts:
        index = parts.index("build")
        return parts[index - 1]
    return parts[0] if parts else "root"


def _find_sdk(root: Path) -> Path | None:
    local_properties = root / "local.properties"
    if local_properties.exists():
        for line in _text(local_properties).splitlines():
            if line.strip().startswith("sdk.dir="):
                value = line.split("=", 1)[1].strip().replace("\\\\", "\\")
                candidate = Path(value)
                if candidate.exists():
                    return candidate
    for key in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        value = os.environ.get(key)
        if value and Path(value).exists():
            return Path(value)
    return None


def _find_tools(root: Path, config: Mapping[str, object] | None = None) -> dict[str, str | None]:
    sdk = _find_sdk(root)
    zipalign: Path | None = None
    readelf: Path | None = None
    nm: Path | None = None
    root_toolchain = config.get("root_toolchain", {}) if config else {}
    preferred_build_tools = str(root_toolchain.get("build_tools", "")).strip()
    preferred_ndk = str(root_toolchain.get("ndk_version", "")).strip()
    if sdk:
        build_tools = [path for path in (sdk / "build-tools").glob("*") if path.is_dir()]
        build_tools.sort(key=lambda path: path.name, reverse=True)
        if preferred_build_tools:
            build_tools.sort(key=lambda path: path.name != preferred_build_tools)
        for directory in build_tools:
            candidate = directory / ("zipalign.exe" if os.name == "nt" else "zipalign")
            if candidate.exists():
                zipalign = candidate
                break
        ndk_roots = [path for path in (sdk / "ndk").glob("*") if path.is_dir()]
        ndk_roots.sort(key=lambda path: path.name != preferred_ndk)
        for ndk_root in ndk_roots:
            prebuilt = "windows-x86_64" if os.name == "nt" else "linux-x86_64"
            tool_dir = ndk_root / "toolchains" / "llvm" / "prebuilt" / prebuilt / "bin"
            candidate_readelf = tool_dir / ("llvm-readelf.exe" if os.name == "nt" else "llvm-readelf")
            candidate_nm = tool_dir / ("llvm-nm.exe" if os.name == "nt" else "llvm-nm")
            if candidate_readelf.exists() and candidate_nm.exists():
                readelf = candidate_readelf
                nm = candidate_nm
                break
    return {
        "sdk": str(sdk) if sdk else None,
        "zipalign": str(zipalign or shutil.which("zipalign") or "") or None,
        "llvm_readelf": str(readelf or shutil.which("llvm-readelf") or "") or None,
        "llvm_nm": str(nm or shutil.which("llvm-nm") or "") or None,
    }


def _run_command(command: Sequence[str], raw_output: Path) -> subprocess.CompletedProcess[str]:
    raw_output.parent.mkdir(parents=True, exist_ok=True)
    completed = subprocess.run(
        list(command),
        cwd=raw_output.parents[2] if len(raw_output.parents) > 2 else None,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    raw_output.write_text(
        "$ " + subprocess.list2cmdline(list(command)) + "\n"
        + completed.stdout
        + ("\n[stderr]\n" + completed.stderr if completed.stderr else ""),
        encoding="utf-8",
    )
    return completed


def _run_command_in_root(
    command: Sequence[str], root: Path, raw_output: Path
) -> subprocess.CompletedProcess[str]:
    raw_output.parent.mkdir(parents=True, exist_ok=True)
    completed = subprocess.run(
        list(command),
        cwd=root,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    raw_output.write_text(
        "$ " + subprocess.list2cmdline(list(command)) + "\n"
        + completed.stdout
        + ("\n[stderr]\n" + completed.stderr if completed.stderr else ""),
        encoding="utf-8",
    )
    return completed


def _parse_quoted_values(fragment: str) -> list[str]:
    return re.findall(r"['\"]([^'\"]+)['\"]", fragment)


def _collect_build_configuration(root: Path) -> dict[str, object]:
    gradle_files = [root / "build.gradle"] + sorted(root.glob("*/build.gradle"))
    entries: list[dict[str, object]] = []
    declared: dict[str, list[str]] = {}
    for path in gradle_files:
        if not path.exists():
            continue
        source = _text(path)
        module = "root" if path == root / "build.gradle" else path.parent.name
        filters: list[str] = []
        for match in re.finditer(r"\babiFilters\b([^\n]*)", source):
            filters.extend(_parse_quoted_values(match.group(1)))
        filters = list(dict.fromkeys(filters))
        if filters:
            declared[module] = filters
        entries.append(
            {
                "module": module,
                "path": _relative(path, root),
                "abi_filters": filters,
                "ndk_abi_filters_present": bool(re.search(r"\bndk\s*\{[^}]*\babiFilters\b", source, re.S)),
                "splits_abi_present": bool(re.search(r"\bsplits\s*\{[^}]*\babi\b", source, re.S)),
                "external_native_build_present": "externalNativeBuild" in source,
                "cmake_present": bool(re.search(r"\bcmake\b", source)),
                "ndk_versions": re.findall(r"\bndkVersion\s*=\s*['\"]([^'\"]+)['\"]", source),
                "jni_libs_references": re.findall(r"[^\n]*jniLibs[^\n]*", source),
                "packaging_references": re.findall(r"[^\n]*(?:packagingOptions|useLegacyPackaging|pickFirst|doNotStrip|exclude)[^\n]*", source),
            }
        )

    settings = root / "settings.gradle"
    settings_text = _text(settings) if settings.exists() else ""
    included = []
    for line in settings_text.splitlines():
        if re.search(r"\binclude\b", line):
            included.extend(re.findall(r"['\"]:([^'\"]+)['\"]", line))
    source_native_paths = [
        _relative(path, root)
        for path in sorted(root.glob("*/src/*/jniLibs/**/*.so"))
        if "ref" not in path.parts
    ]
    cmake_files = [
        path
        for path in sorted(root.glob("*/**/CMakeLists.txt"))
        if not any(part in {"ref", "build", "out", ".git"} for part in path.parts)
    ]
    cmake_entries = []
    for path in cmake_files:
        source = _text(path)
        cmake_entries.append(
            {
                "path": _relative(path, root),
                "targets": re.findall(
                    r"\badd_library\s*\(\s*([A-Za-z0-9_+.-]+)\s+(?:SHARED|STATIC|MODULE|OBJECT)",
                    source,
                    re.I,
                ),
                "max_page_size_16384": bool(re.search(r"max-page-size=16384", source)),
                "link_options": re.findall(r"target_link_options[^\n]*", source),
            }
        )
    versions = [
        _relative(path, root)
        for path in sorted(root.glob("gradle/**/*.toml"))
        if path.exists()
    ]
    root_build = _text(root / "build.gradle") if (root / "build.gradle").exists() else ""
    return {
        "settings_path": _relative(settings, root) if settings.exists() else None,
        "included_modules": included,
        "gradle": entries,
        "declared_abis_by_module": declared,
        "jni_libs_source_paths": source_native_paths,
        "cmake": cmake_entries,
        "version_catalogs": versions,
        "root_toolchain": {
            "build_tools": next(iter(re.findall(r"\bcontrolledBuildTools\s*=\s*['\"]([^'\"]+)['\"]", root_build)), ""),
            "ndk_version": next(iter(re.findall(r"\bcontrolledNdkVersion\s*=\s*['\"]([^'\"]+)['\"]", root_build)), ""),
            "cmake_version": next(iter(re.findall(r"\bcontrolledCmakeVersion\s*=\s*['\"]([^'\"]+)['\"]", root_build)), ""),
        },
        "android_mk_or_application_mk": [
            _relative(path, root)
            for pattern in ("**/Android.mk", "**/Application.mk")
            for path in root.glob(pattern)
            if not any(part in {"ref", "build", "out", ".git"} for part in path.parts)
        ],
    }


def _discover_artifacts(root: Path) -> list[Path]:
    artifacts: list[Path] = []
    for module_dir in sorted(path for path in root.iterdir() if path.is_dir()):
        if module_dir.name in {"ref", "out", ".git", ".gradle"}:
            continue
        artifacts.extend(module_dir.glob("build/outputs/**/*.apk"))
        artifacts.extend(module_dir.glob("build/outputs/**/*.aar"))
    return sorted(path for path in artifacts if path.is_file())


def _zip_data_offset(path: Path, info: zipfile.ZipInfo) -> int:
    with path.open("rb") as stream:
        stream.seek(info.header_offset)
        header = stream.read(30)
    if len(header) != 30:
        raise ValueError(f"short ZIP local header for {path}:{info.filename}")
    fields = struct.unpack("<IHHHHHIIIHH", header)
    filename_length, extra_length = fields[-2:]
    return info.header_offset + 30 + filename_length + extra_length


def _zip_kind(method: int) -> str:
    return "STORED" if method == zipfile.ZIP_STORED else f"METHOD_{method}"


def _parse_readelf(text: str) -> dict[str, object]:
    def value(label: str) -> str:
        match = re.search(rf"^\s*{re.escape(label)}:\s*(.+?)\s*$", text, re.M)
        return match.group(1).strip() if match else ""

    loads: list[int] = []
    for line in text.splitlines():
        if re.match(r"^\s*LOAD\s+", line):
            fields = line.split()
            if fields:
                try:
                    loads.append(int(fields[-1], 0))
                except ValueError:
                    pass
    needed = re.findall(r"\(NEEDED\).*?\[([^\]]+)\]", text)
    rpaths = re.findall(r"\((?:RPATH|RUNPATH)\).*?\[([^\]]+)\]", text)
    return {
        "elf_class": value("Class"),
        "data": value("Data"),
        "os_abi": value("OS/ABI"),
        "type": value("Type"),
        "entry": value("Entry point address"),
        "machine": value("Machine"),
        "load_alignments": loads,
        "min_load_alignment": min(loads) if loads else 0,
        "needed": needed,
        "rpath_runpath": rpaths,
        "alignment_status": "PASS" if loads and all(value >= 0x4000 for value in loads) else "FAIL",
    }


def _parse_nm_symbols(text: str) -> list[str]:
    symbols: list[str] = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("nm:") or stripped.startswith("/ "):
            continue
        fields = stripped.split()
        if fields:
            candidate = fields[-1]
            if candidate not in {"U", "w", "W", "T", "t", "D", "d", "B", "b", "R", "r"}:
                symbols.append(candidate)
    return sorted(set(symbols))


def _infer_abi(path_text: str) -> str | None:
    match = ABI_PATH_RE.search(path_text.replace("\\", "/"))
    return match.group(1) if match else None


def _collect_intermediates(root: Path) -> list[Path]:
    paths: list[Path] = []
    for module_dir in sorted(path for path in root.iterdir() if path.is_dir()):
        if module_dir.name in {"ref", "out", ".git", ".gradle"}:
            continue
        paths.extend(module_dir.glob("build/**/*.so"))
        paths.extend(module_dir.glob(".cxx/**/*.so"))
    return sorted(
        path
        for path in set(paths)
        if path.is_file() and "outputs" not in path.parts and "out" not in path.parts
    )


def _source_files(root: Path) -> Iterable[Path]:
    suffixes = {".c", ".cc", ".cpp", ".cxx", ".h", ".hh", ".hpp", ".s", ".S"}
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.suffix not in suffixes:
            continue
        if any(part in {"ref", "build", "out", ".git", ".gradle", ".cxx"} for part in path.parts):
            continue
        yield path


def _source_audit(root: Path) -> dict[str, object]:
    arch_files: dict[str, list[str]] = {macro: [] for macro in ARCH_MACROS}
    syscall_files: list[dict[str, object]] = []
    coverage_failures: list[dict[str, object]] = []
    unresolved_arch_candidates: list[dict[str, object]] = []
    pointer_candidates: list[dict[str, object]] = []
    source_count = 0

    for path in _source_files(root):
        source_count += 1
        relative = _relative(path, root)
        content = _text(path)
        present = [macro for macro in ARCH_MACROS if macro in content]
        for macro in present:
            arch_files[macro].append(relative)

        is_companion_entry = relative.endswith("sandbox-companion32/src/main/cpp/native_companion_jni.cpp")
        if present and any(marker in content for marker in ARCH_ASM_MARKERS):
            required = {"__arm__", "__i386__"} if is_companion_entry else set(ARCH_MACROS)
            missing = sorted(required - set(present))
            if missing:
                coverage_failures.append({"file": relative, "missing_macros": missing})
            syscall_files.append(
                {
                    "file": relative,
                    "macros": present,
                    "required_macros": sorted(required),
                    "coverage": "PASS" if not missing else "FAIL",
                    "raw_numeric_syscall_literals": bool(
                        re.search(r"\b(?:syscall|raw_syscall\w*)\s*\(\s*\d+", content)
                    ),
                }
            )

        for line_number, line in enumerate(content.splitlines(), start=1):
            if re.search(r"reinterpret_cast\s*<\s*(?:jint|int)\s*>|static_cast\s*<\s*(?:jint|int)\s*>\s*\([^\n]*(?:pointer|uintptr|size_t)", line):
                pointer_candidates.append({"file": relative, "line": line_number, "text": line.strip()})

        if present and re.search(r"\b(?:TODO|FIXME)\b|\bunsupported\b", content, re.I):
            if not is_companion_entry:
                unresolved_arch_candidates.append({"file": relative, "reason": "architecture branch contains TODO/unsupported marker"})

    product_java_files = [
        path
        for base in (root / "app", root / "sandbox-runtime", root / "sandbox-framework", root / "sandbox-contract")
        if base.exists()
        for path in base.glob("src/**/*.java")
        if path.is_file() and not any(part in {"build", "ref", "out"} for part in path.parts)
    ]
    os_arch_hits = []
    supported_abis_hits = []
    split_contract_hits = []
    for path in sorted(product_java_files):
        relative = _relative(path, root)
        content = _text(path)
        if "System.getProperty(\"os.arch\")" in content or "System.getProperty('os.arch')" in content:
            os_arch_hits.append(relative)
        if "Build.SUPPORTED_ABIS" in content:
            supported_abis_hits.append(relative)
        if any(token in content for token in ("nativeLibraryDir", "splitSourceDirs", "splitNames")):
            split_contract_hits.append(relative)

    native_policy = root / "sandbox-native/src/main/java/com/warden/controlledsandbox/nativebridge/NativePolicy.java"
    companion_request = root / "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/NativeCompanionRequest.aidl"
    companion_result = root / "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/NativeCompanionResult.aidl"
    stable_contract_files = [
        _relative(path, root)
        for path in (native_policy, companion_request, companion_result)
        if path.exists()
    ]
    pointer_width_evidence = {
        "native_policy_uses_jlong_or_wide_integer": bool(
            native_policy.exists() and re.search(r"\bjlong\b|\buint64_t\b|\buintptr_t\b", _text(native_policy))
        ),
        "companion_contract_uses_pointer_types": bool(
            any(
                path.exists() and re.search(r"\b(?:void\s*\*|uintptr_t|jint\s+\w*pointer|nativeHandle)\b", _text(path), re.I)
                for path in (companion_request, companion_result)
            )
        ),
    }
    pointer_width_status = "PASS" if not pointer_candidates and not pointer_width_evidence["companion_contract_uses_pointer_types"] else "REVIEW"
    jni_status = "PASS" if pointer_width_status == "PASS" and not os_arch_hits else "REVIEW"
    source_status = "PASS" if not coverage_failures and not unresolved_arch_candidates else "FAIL"
    return {
        "source_file_count": source_count,
        "architecture_macro_files": arch_files,
        "syscall_and_arch_dispatch": syscall_files,
        "architecture_coverage_failures": coverage_failures,
        "unresolved_arch_stub_candidates": unresolved_arch_candidates,
        "pointer_truncation_candidates": pointer_candidates,
        "pointer_width_evidence_files": stable_contract_files,
        "pointer_width_status": pointer_width_status,
        "jni_type_status": jni_status,
        "loader_contract": {
            "os_arch_product_hits": os_arch_hits,
            "build_supported_abis_hits": supported_abis_hits,
            "split_and_native_path_contract_hits": split_contract_hits,
            "status": "PASS" if not os_arch_hits and supported_abis_hits else "REVIEW",
        },
        "status": source_status,
    }


def _first_party_names(config: Mapping[str, object], root: Path) -> set[str]:
    names: set[str] = set()
    for entry in config.get("cmake", []):
        if not isinstance(entry, Mapping):
            continue
        for target in entry.get("targets", []):
            names.add(f"lib{target}.so")
    for relative in config.get("jni_libs_source_paths", []):
        names.add(Path(str(relative)).name)
    return names


def _audit_final_artifacts(
    root: Path,
    evidence: Path,
    tools: Mapping[str, str | None],
    first_party_names: set[str],
    config: Mapping[str, object],
) -> tuple[list[dict[str, object]], list[dict[str, object]], list[str]]:
    artifacts = _discover_artifacts(root)
    artifact_inventory: list[dict[str, object]] = []
    native_records: list[dict[str, object]] = []
    findings: list[str] = []
    names_by_artifact_abi: defaultdict[tuple[str, str], set[str]] = defaultdict(set)
    zipalign_status: dict[str, str] = {}

    for artifact in artifacts:
        relative_artifact = _relative(artifact, root)
        module = _module_from_path(artifact, root)
        kind = "APK" if artifact.suffix.lower() == ".apk" else "AAR"
        native_entries: list[dict[str, object]] = []
        with zipfile.ZipFile(artifact) as archive:
            all_names = [info.filename for info in archive.infolist()]
            duplicate_names = [name for name, count in Counter(all_names).items() if count > 1]
            if duplicate_names:
                findings.append(f"duplicate ZIP entries in {relative_artifact}: {duplicate_names}")
            for info in archive.infolist():
                match = re.fullmatch(r"(?:lib|jni)/(armeabi-v7a|arm64-v8a|x86|x86_64)/([^/]+\.so)", info.filename)
                if not match:
                    continue
                abi, library = match.groups()
                extracted = evidence / "extracted" / _slug(relative_artifact) / abi / library
                extracted.parent.mkdir(parents=True, exist_ok=True)
                extracted.write_bytes(archive.read(info))
                data_offset = _zip_data_offset(artifact, info)
                package_alignment = "NOT_APPLICABLE_REPACKAGED"
                if kind == "APK":
                    package_alignment = "PASS" if info.compress_type == zipfile.ZIP_STORED and data_offset % 0x4000 == 0 else "FAIL"
                    if package_alignment != "PASS":
                        findings.append(f"native APK packaging failure: {relative_artifact}:{info.filename}")
                elif info.compress_type == zipfile.ZIP_STORED:
                    package_alignment = "STORED_AAR_REPACKAGED"
                names_by_artifact_abi[(relative_artifact, abi)].add(library)
                native_entries.append(
                    {
                        "path": info.filename,
                        "abi": abi,
                        "library": library,
                        "size": info.file_size,
                        "sha256": _sha256(extracted),
                        "zip_method": _zip_kind(info.compress_type),
                        "zip_data_offset": data_offset,
                        "package_alignment_status": package_alignment,
                        "extracted_path": extracted.relative_to(evidence).as_posix(),
                    }
                )
                native_records.append(
                    {
                        "artifact": relative_artifact,
                        "module": module,
                        "package_kind": kind,
                        "package_path": info.filename,
                        "abi": abi,
                        "library": library,
                        "size": info.file_size,
                        "sha256": _sha256(extracted),
                        "classification": "first_party" if library in first_party_names else "third_party",
                        "zip_method": _zip_kind(info.compress_type),
                        "zip_data_offset": data_offset,
                        "package_alignment_status": package_alignment,
                        "extracted_path": extracted.relative_to(evidence).as_posix(),
                    }
                )

        artifact_inventory.append(
            {
                "artifact": relative_artifact,
                "module": module,
                "kind": kind,
                "size": artifact.stat().st_size,
                "sha256": _sha256(artifact),
                "native_entries": native_entries,
            }
        )

        if kind == "APK" and tools.get("zipalign"):
            raw = _raw_file(evidence, "zipalign", relative_artifact)
            result = _run_command_in_root(
                [str(tools["zipalign"]), "-c", "-v", "-P", "16", "4", str(artifact)],
                root,
                raw,
            )
            zipalign_status[relative_artifact] = "PASS" if result.returncode == 0 else "FAIL"
            if result.returncode != 0:
                findings.append(f"zipalign failed for {relative_artifact}")
        elif kind == "APK":
            zipalign_status[relative_artifact] = "TOOL_MISSING"
            findings.append("zipalign tool unavailable")

    for artifact in artifacts:
        relative_artifact = _relative(artifact, root)
        module = _module_from_path(artifact, root)
        declared = set()
        for row in config.get("gradle", []):
            if isinstance(row, Mapping) and row.get("module") == module:
                declared.update(str(abi) for abi in row.get("abi_filters", []))
        artifact_abis = {
            abi for (candidate, abi) in names_by_artifact_abi if candidate == relative_artifact
        }
        if declared and artifact_abis:
            for abi in sorted(declared & artifact_abis):
                names = names_by_artifact_abi[(relative_artifact, abi)]
                all_for_abi = set().union(
                    *(names_by_artifact_abi[(relative_artifact, other)] for other in artifact_abis)
                )
                missing = sorted(all_for_abi - names)
                if missing:
                    findings.append(
                        f"same-named native library missing in {relative_artifact}/{abi}: {missing}"
                    )

    for record in native_records:
        path = evidence / str(record["extracted_path"])
        raw = _raw_file(
            evidence,
            "readelf",
            f"{record['artifact']}/{record['abi']}/{record['library']}",
        )
        if not tools.get("llvm_readelf"):
            findings.append("llvm-readelf tool unavailable")
            parsed = {"alignment_status": "FAIL", "needed": [], "rpath_runpath": []}
        else:
            result = _run_command_in_root(
                [str(tools["llvm_readelf"]), "-h", "-l", "-d", "--wide", str(path)],
                root,
                raw,
            )
            if result.returncode != 0:
                findings.append(f"llvm-readelf failed for {record['artifact']}:{record['library']}")
                parsed = {"alignment_status": "FAIL", "needed": [], "rpath_runpath": []}
            else:
                parsed = _parse_readelf(result.stdout)
        record.update(parsed)
        expected_class, expected_machine = ABI_ELF_CONTRACT[str(record["abi"])]
        if record.get("elf_class") != expected_class or str(record.get("machine", "")) != expected_machine:
            findings.append(
                f"ELF machine mismatch {record['artifact']}:{record['abi']}:{record['library']} "
                f"= {record.get('elf_class')}/{record.get('machine')} expected {expected_class}/{expected_machine}"
            )
        if record.get("alignment_status") != "PASS":
            findings.append(f"ELF LOAD alignment failure {record['artifact']}:{record['abi']}:{record['library']}")
        needed = [str(value) for value in record.get("needed", [])]
        record["missing_dependencies"] = []
        record["dependency_status"] = "PASS"
        packaged_names = {
            other["library"]
            for other in native_records
            if other["artifact"] == record["artifact"] and other["abi"] == record["abi"]
        }
        for dependency in needed:
            if dependency in SYSTEM_NEEDED or dependency in packaged_names:
                continue
            record["missing_dependencies"].append(dependency)
        if record["missing_dependencies"]:
            record["dependency_status"] = "FAIL"
            findings.append(
                f"missing native dependency {record['artifact']}:{record['abi']}:{record['library']} "
                f"= {record['missing_dependencies']}"
            )
        record["rpath"] = [value for value in record.get("rpath_runpath", []) if "RPATH" in str(value)]
        record["runpath"] = [value for value in record.get("rpath_runpath", []) if "RUNPATH" in str(value)]
        absolute_path_issue = bool(
            re.search(r"(?:[A-Za-z]:[\\/]|/home/|/tmp/|/Users/)", " ".join(record.get("rpath_runpath", [])))
        )
        record["rpath_runpath_status"] = "FAIL" if absolute_path_issue else "PASS"
        if absolute_path_issue:
            findings.append(f"development absolute RPATH/RUNPATH in {record['artifact']}:{record['library']}")

        if tools.get("llvm_nm"):
            undefined_raw = _raw_file(
                evidence,
                "nm-undefined",
                f"{record['artifact']}/{record['abi']}/{record['library']}",
            )
            defined_raw = _raw_file(
                evidence,
                "nm-defined",
                f"{record['artifact']}/{record['abi']}/{record['library']}",
            )
            undefined_result = _run_command_in_root(
                [str(tools["llvm_nm"]), "-D", "--undefined-only", str(path)], root, undefined_raw
            )
            defined_result = _run_command_in_root(
                [str(tools["llvm_nm"]), "-D", "--defined-only", str(path)], root, defined_raw
            )
            undefined_symbols = _parse_nm_symbols(undefined_result.stdout) if undefined_result.returncode == 0 else []
            defined_symbols = _parse_nm_symbols(defined_result.stdout) if defined_result.returncode == 0 else []
            record["undefined_dynamic_symbol_count"] = len(undefined_symbols)
            record["undefined_dynamic_symbols_sample"] = undefined_symbols[:12]
            record["exported_symbol_count"] = len(defined_symbols)
            record["exported_jni_symbols"] = sorted(
                symbol for symbol in defined_symbols if symbol.startswith("Java_") or symbol == "JNI_OnLoad"
            )
            if undefined_result.returncode != 0 or defined_result.returncode != 0:
                findings.append(f"llvm-nm failed for {record['artifact']}:{record['library']}")
        else:
            record["undefined_dynamic_symbol_count"] = None
            record["exported_symbol_count"] = None
            record["exported_jni_symbols"] = []
            findings.append("llvm-nm tool unavailable")

    return artifact_inventory, native_records, findings + [
        f"ZIPALIGN_STATUS {artifact}={status}" for artifact, status in sorted(zipalign_status.items()) if status != "PASS"
    ]


def _audit_intermediates(root: Path, evidence: Path, tools: Mapping[str, str | None]) -> tuple[list[dict[str, object]], list[str]]:
    records: list[dict[str, object]] = []
    findings: list[str] = []
    for path in _collect_intermediates(root):
        relative = _relative(path, root)
        abi = _infer_abi(relative)
        raw = _raw_file(evidence, "intermediate-readelf", relative)
        parsed: dict[str, object]
        if not abi:
            findings.append(f"intermediate native output has no ABI path: {relative}")
        if tools.get("llvm_readelf"):
            result = _run_command_in_root(
                [str(tools["llvm_readelf"]), "-h", "-l", "-d", "--wide", str(path)], root, raw
            )
            parsed = _parse_readelf(result.stdout) if result.returncode == 0 else {"alignment_status": "FAIL"}
            if result.returncode != 0:
                findings.append(f"llvm-readelf failed for intermediate {relative}")
        else:
            parsed = {"alignment_status": "FAIL"}
        records.append(
            {
                "path": relative,
                "abi_from_path": abi,
                "library": path.name,
                "size": path.stat().st_size,
                "sha256": _sha256(path),
                **parsed,
            }
        )
    return records, findings


def _run_companion_guard(root: Path, evidence: Path) -> tuple[str, str]:
    script = root / "scripts/check-native-abi-companion.py"
    if not script.exists():
        return "MISSING", "companion guard script missing"
    raw = evidence / "raw" / "check-native-abi-companion.txt"
    result = _run_command_in_root([sys.executable, str(script)], root, raw)
    return ("PASS" if result.returncode == 0 else "FAIL", result.stdout + result.stderr)


def _build_abi_cells(
    config: Mapping[str, object], native_records: Sequence[Mapping[str, object]]
) -> tuple[list[dict[str, object]], list[str]]:
    declared = config.get("declared_abis_by_module", {})
    actual: defaultdict[str, defaultdict[str, list[str]]] = defaultdict(lambda: defaultdict(list))
    for record in native_records:
        actual[str(record["module"])][str(record["abi"])].append(str(record["artifact"]))
    cells: list[dict[str, object]] = []
    findings: list[str] = []
    for module in sorted(declared):
        for abi in sorted(set(str(value) for value in declared[module])):
            artifacts = sorted(set(actual[module].get(abi, [])))
            if not artifacts:
                status = "DECLARED_NOT_BUILT"
                findings.append(f"declared ABI missing final artifact: {module}/{abi}")
            elif module == "sandbox-companion32":
                status = "COMPANION_ONLY"
            elif module.startswith("fixture-"):
                status = "TEST_ONLY"
            else:
                status = "BUILT"
            cells.append(
                {
                    "module": module,
                    "abi": abi,
                    "status": status,
                    "artifact": ";".join(artifacts),
                }
            )
        actual_abis = set(actual[module])
        unknown = sorted(actual_abis - set(str(value) for value in declared[module]))
        if unknown:
            findings.append(f"artifact ABI not declared by {module}: {unknown}")
    return cells, findings


def run_audit(root: Path, output: Path) -> int:
    output.mkdir(parents=True, exist_ok=True)
    (output / "raw").mkdir(exist_ok=True)
    (output / "extracted").mkdir(exist_ok=True)
    config = _collect_build_configuration(root)
    tools = _find_tools(root, config)
    _write_json(output / "config.json", config)
    first_party_names = _first_party_names(config, root)
    artifact_inventory, native_records, artifact_findings = _audit_final_artifacts(
        root, output, tools, first_party_names, config
    )
    intermediate_records, intermediate_findings = _audit_intermediates(root, output, tools)
    source_audit = _source_audit(root)
    companion_status, companion_output = _run_companion_guard(root, output)
    (output / "raw" / "companion_guard_summary.txt").write_text(companion_output, encoding="utf-8")

    abi_cells, abi_findings = _build_abi_cells(config, native_records)
    findings = list(artifact_findings) + list(intermediate_findings) + list(abi_findings)
    if source_audit["status"] != "PASS":
        findings.append("architecture-specific source audit has unresolved coverage/stub candidates")
    if source_audit["pointer_width_status"] != "PASS":
        findings.append("pointer-width audit requires review")
    if source_audit["jni_type_status"] != "PASS":
        findings.append("JNI type audit requires review")
    if source_audit["loader_contract"]["status"] != "PASS":
        findings.append("loader ABI selection contract requires review")
    if companion_status != "PASS":
        findings.append("sandbox-companion32 static guard failed")

    validator_errors: list[str] = []
    try:
        abi_summary = validate_abi_matrix(abi_cells)
    except MatrixAccountingError as error:
        abi_summary = None
        validator_errors.append(str(error))
    try:
        native_summary = validate_native_inventory(native_records)
    except MatrixAccountingError as error:
        native_summary = None
        validator_errors.append(str(error))
    findings.extend(validator_errors)

    _write_json(output / "abi_matrix.json", {"cells": abi_cells})
    _write_json(output / "native_inventory.json", {"records": native_records})
    _write_json(output / "artifact_inventory.json", {"artifacts": artifact_inventory})
    _write_json(output / "elf_matrix.json", {"records": native_records, "intermediates": intermediate_records})
    _write_json(output / "source_audit.json", source_audit)

    package_native_records = [record for record in native_records if record["package_kind"] == "APK"]
    aar_native_records = [record for record in native_records if record["package_kind"] == "AAR"]
    package_failures = [
        record
        for record in package_native_records
        if record.get("package_alignment_status") != "PASS"
    ]
    classification_counts = Counter(str(record["classification"]) for record in native_records)
    export_contract_issues: list[str] = []
    exports_by_library: defaultdict[str, dict[str, set[str]]] = defaultdict(dict)
    for record in native_records:
        exports_by_library[str(record["library"])][str(record["abi"])] = set(record.get("exported_jni_symbols", []))
    for library, by_abi in sorted(exports_by_library.items()):
        if len(by_abi) < 2:
            continue
        expected = set().union(*by_abi.values())
        if expected and any(symbols != expected for symbols in by_abi.values()):
            export_contract_issues.append(library)
    if export_contract_issues:
        findings.append(f"JNI export contract mismatch: {export_contract_issues}")

    summary = {
        "result": "PASS" if not findings else "BLOCKED",
        "findings": sorted(set(findings)),
        "tools": tools,
        "artifact_counts": {
            "apk_aar_total": len(artifact_inventory),
            "apk_total": sum(1 for item in artifact_inventory if item["kind"] == "APK"),
            "aar_total": sum(1 for item in artifact_inventory if item["kind"] == "AAR"),
            "native_library_total": len(native_records),
            "apk_native_records": len(package_native_records),
            "aar_native_records": len(aar_native_records),
            "unique_native_library_names": sorted({str(record["library"]) for record in native_records}),
            "first_party_native_count": classification_counts["first_party"],
            "third_party_native_count": classification_counts["third_party"],
        },
        "abi_counts": {
            abi: {
                "artifact_files": len({record["artifact"] for record in native_records if record["abi"] == abi}),
                "library_records": sum(1 for record in native_records if record["abi"] == abi),
            }
            for abi in KNOWN_ABIS
        },
        "elf": {
            "total": len(native_records),
            "pass": sum(1 for record in native_records if record.get("alignment_status") == "PASS" and record.get("dependency_status") == "PASS"),
            "fail": sum(1 for record in native_records if record.get("alignment_status") != "PASS" or record.get("dependency_status") != "PASS"),
            "class_machine_mismatch": sum(
                1
                for record in native_records
                if (record.get("elf_class"), record.get("machine")) != ABI_ELF_CONTRACT.get(str(record["abi"]))
            ),
        },
        "packaging": {
            "apk_native_total": len(package_native_records),
            "apk_native_pass": len(package_native_records) - len(package_failures),
            "apk_native_fail": len(package_failures),
            "aar_native_total": len(aar_native_records),
            "aar_compressed_repackaged": sum(1 for record in aar_native_records if record.get("zip_method") != "STORED"),
            "intermediate_elf_total": len(intermediate_records),
        },
        "abi_matrix": {
            "total": abi_summary.total if abi_summary else 0,
            "status_counts": dict(abi_summary.counts) if abi_summary else {},
            "validator": "PASS" if abi_summary else "FAIL",
        },
        "native_inventory_validator": "PASS" if native_summary else "FAIL",
        "exports": {
            "contract_issues": export_contract_issues,
            "undefined_dynamic_symbols_total": sum(
                int(record.get("undefined_dynamic_symbol_count") or 0) for record in native_records
            ),
        },
        "source_audit": source_audit,
        "companion32_static_status": companion_status,
    }
    _write_json(output / "audit_summary.json", summary)

    print(f"ABI_AUDIT_RESULT={summary['result']}")
    print(f"ABI_MATRIX_VALIDATOR={summary['abi_matrix']['validator']}")
    print(f"NATIVE_INVENTORY_VALIDATOR={summary['native_inventory_validator']}")
    print(f"APK_AAR_TOTAL={summary['artifact_counts']['apk_aar_total']}")
    print(f"NATIVE_LIBRARY_TOTAL={summary['artifact_counts']['native_library_total']}")
    print(f"FIRST_PARTY_NATIVE_COUNT={summary['artifact_counts']['first_party_native_count']}")
    print(f"THIRD_PARTY_NATIVE_COUNT={summary['artifact_counts']['third_party_native_count']}")
    print(f"ELF_TOTAL={summary['elf']['total']}")
    print(f"ELF_PASS={summary['elf']['pass']}")
    print(f"ELF_FAIL={summary['elf']['fail']}")
    print(f"PAGE_SIZE_16K_STATIC_TOTAL={len(native_records)}")
    print(f"PAGE_SIZE_16K_STATIC_PASS={sum(1 for record in native_records if record.get('alignment_status') == 'PASS')}")
    print(f"PAGE_SIZE_16K_STATIC_FAIL={sum(1 for record in native_records if record.get('alignment_status') != 'PASS')}")
    if summary["findings"]:
        print("FINDINGS=" + " | ".join(summary["findings"]))
    return 0 if summary["result"] == "PASS" else 1


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument(
        "--output",
        type=Path,
        default=None,
        help="ignored evidence directory; defaults to out/verification/t57-r03/c6-t02a-abi-elf-static-convergence",
    )
    args = parser.parse_args(argv)
    root = args.root.resolve()
    output = (args.output or root / "out/verification/t57-r03/c6-t02a-abi-elf-static-convergence").resolve()
    return run_audit(root, output)


if __name__ == "__main__":
    raise SystemExit(main())
