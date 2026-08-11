#!/usr/bin/env python3
"""Import and verify immutable upstream reference source snapshots.

The reference trees are deliberately isolated under ``ref/`` and are never
part of the Controlled Sandbox build.  The original GitHub ZIP archives are
retained so the extracted trees can be verified byte-for-byte later.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import stat
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
REF_ROOT = ROOT / "ref"
IMPORT_DATE = "2026-07-30"


@dataclass(frozen=True)
class ReferenceSpec:
    key: str
    display_name: str
    repository: str
    archive_name: str
    archive_root: str
    archive_sha256: str
    archive_commit: str
    declared_license: str
    root_license_present: bool
    license_paths: tuple[str, ...]
    restriction_notice_paths: tuple[str, ...] = ()


SPECS = (
    ReferenceSpec(
        key="newblackbox",
        display_name="NewBlackbox",
        repository="https://github.com/ALEX5402/NewBlackbox",
        archive_name="NewBlackbox-main.zip",
        archive_root="NewBlackbox-main",
        archive_sha256="a0776a70d4e5dcf8ebabfb6abed3e4806ac18d2619cca6fd3e6a8303a4371242",
        archive_commit="89b59836c66f173756a4ae258cf379a957649820",
        declared_license="Apache-2.0",
        root_license_present=True,
        license_paths=("LICENSE",),
    ),
    ReferenceSpec(
        key="virtualapp",
        display_name="VirtualApp",
        repository="https://github.com/asLody/VirtualApp",
        archive_name="VirtualApp-master.zip",
        archive_root="VirtualApp-master",
        archive_sha256="e3c3e5797b2800b62cc2664930bed3d48a058bb64627e596f21d8468f284ddcb",
        archive_commit="c84bf5e9f487966b4b8967898075efa76392b808",
        declared_license="UNRESOLVED_FOR_ROOT_ARCHIVE",
        root_license_present=False,
        license_paths=("VirtualApp/lib/src/main/jni/HookZz/LICENSE",),
        restriction_notice_paths=("README.md", "README_eng.md"),
    ),
)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def safe_relative_path(name: str, expected_root: str) -> PurePosixPath | None:
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or "\\" in name:
        raise ValueError(f"unsafe ZIP entry: {name!r}")
    if not path.parts or path.parts[0] != expected_root:
        raise ValueError(f"unexpected archive root for {name!r}; expected {expected_root!r}")
    relative = PurePosixPath(*path.parts[1:])
    if not relative.parts:
        return None
    return relative


def archive_mode(info: zipfile.ZipInfo) -> int:
    unix_mode = (info.external_attr >> 16) & 0xFFFF
    return 0o755 if unix_mode & stat.S_IXUSR else 0o644


def inspect_archive(spec: ReferenceSpec, archive: Path) -> tuple[list[dict[str, object]], int]:
    actual_sha = sha256_file(archive)
    if actual_sha != spec.archive_sha256:
        raise ValueError(
            f"{spec.display_name}: archive SHA-256 mismatch: {actual_sha} != {spec.archive_sha256}"
        )

    records: list[dict[str, object]] = []
    total_uncompressed = 0
    with zipfile.ZipFile(archive) as package:
        comment = package.comment.decode("ascii", "strict")
        if comment != spec.archive_commit:
            raise ValueError(
                f"{spec.display_name}: ZIP comment commit mismatch: {comment!r} != {spec.archive_commit!r}"
            )
        seen: set[str] = set()
        for info in package.infolist():
            relative = safe_relative_path(info.filename, spec.archive_root)
            if relative is None or info.is_dir():
                continue
            relative_name = relative.as_posix()
            if relative_name in seen:
                raise ValueError(f"{spec.display_name}: duplicate path: {relative_name}")
            seen.add(relative_name)
            mode_type = stat.S_IFMT((info.external_attr >> 16) & 0xFFFF)
            if mode_type not in (0, stat.S_IFREG):
                raise ValueError(f"{spec.display_name}: unsupported non-regular entry: {relative_name}")
            data = package.read(info)
            records.append(
                {
                    "path": relative_name,
                    "sha256": hashlib.sha256(data).hexdigest(),
                    "size": len(data),
                    "mode": f"{archive_mode(info):04o}",
                }
            )
            total_uncompressed += len(data)
    records.sort(key=lambda item: str(item["path"]))
    return records, total_uncompressed


def extract_archive(spec: ReferenceSpec, archive: Path, destination: Path) -> None:
    shutil.rmtree(destination, ignore_errors=True)
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as package:
        for info in package.infolist():
            relative = safe_relative_path(info.filename, spec.archive_root)
            if relative is None:
                continue
            output = destination.joinpath(*relative.parts)
            if info.is_dir():
                output.mkdir(parents=True, exist_ok=True)
                continue
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(package.read(info))
            output.chmod(archive_mode(info))


def windows_backed_filesystem() -> bool:
    """Return true when the verifier runs on NTFS directly or through WSL/DrvFS."""
    if os.name == "nt":
        return True
    try:
        mounts = Path("/proc/mounts").read_text(encoding="utf-8", errors="ignore")
        return str(ROOT).startswith("/mnt/") and "aname=drvfs" in mounts
    except OSError:
        return False


def write_tree_hashes(path: Path, records: list[dict[str, object]]) -> None:
    lines = [
        f"{record['sha256']}  {record['mode']}  {record['size']}  {record['path']}"
        for record in records
    ]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_manifest(
    spec: ReferenceSpec,
    records: list[dict[str, object]],
    total_uncompressed: int,
    archive_size: int,
) -> None:
    manifest = {
        "schemaVersion": 1,
        "project": spec.display_name,
        "repository": spec.repository,
        "snapshotType": "GitHub source archive uploaded by project owner",
        "archive": f"ref/archives/{spec.archive_name}",
        "archiveSha256": spec.archive_sha256,
        "archiveCommentCommit": spec.archive_commit,
        "archiveRoot": spec.archive_root,
        "archiveSizeBytes": archive_size,
        "extractedTree": f"ref/upstream/{spec.display_name}",
        "fileCount": len(records),
        "uncompressedFileBytes": total_uncompressed,
        "importedOn": IMPORT_DATE,
        "buildStatus": "not executed; reference-only source",
        "productBuildInclusion": False,
        "declaredLicense": spec.declared_license,
        "rootLicensePresent": spec.root_license_present,
        "licensePaths": list(spec.license_paths),
        "restrictionNoticePaths": list(spec.restriction_notice_paths),
        "cleanRoomPolicy": "Read-only behavior and architecture reference. Do not copy source into product modules.",
        "treeHashManifest": f"ref/manifests/{spec.key}-tree-sha256.txt",
    }
    path = REF_ROOT / "manifests" / f"{spec.key}.json"
    path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def import_sources(args: argparse.Namespace) -> None:
    inputs = {
        "newblackbox": Path(args.newblackbox).resolve(),
        "virtualapp": Path(args.virtualapp).resolve(),
    }
    (REF_ROOT / "archives").mkdir(parents=True, exist_ok=True)
    (REF_ROOT / "upstream").mkdir(parents=True, exist_ok=True)
    (REF_ROOT / "manifests").mkdir(parents=True, exist_ok=True)

    for spec in SPECS:
        source = inputs[spec.key]
        if not source.is_file():
            raise FileNotFoundError(source)
        records, total_uncompressed = inspect_archive(spec, source)
        archive_copy = REF_ROOT / "archives" / spec.archive_name
        shutil.copyfile(source, archive_copy)
        archive_copy.chmod(0o644)
        extract_archive(spec, archive_copy, REF_ROOT / "upstream" / spec.display_name)
        write_tree_hashes(REF_ROOT / "manifests" / f"{spec.key}-tree-sha256.txt", records)
        write_manifest(spec, records, total_uncompressed, archive_copy.stat().st_size)
        print(
            f"PASS imported {spec.display_name}: files={len(records)} "
            f"commit={spec.archive_commit} archive_sha256={spec.archive_sha256}"
        )


def verify_source(spec: ReferenceSpec) -> None:
    archive = REF_ROOT / "archives" / spec.archive_name
    manifest_path = REF_ROOT / "manifests" / f"{spec.key}.json"
    hashes_path = REF_ROOT / "manifests" / f"{spec.key}-tree-sha256.txt"
    tree = REF_ROOT / "upstream" / spec.display_name
    for required in (archive, manifest_path, hashes_path, tree):
        if not required.exists():
            raise ValueError(f"{spec.display_name}: missing reference artifact: {required.relative_to(ROOT)}")

    records, total_uncompressed = inspect_archive(spec, archive)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    expected_fields = {
        "repository": spec.repository,
        "archiveSha256": spec.archive_sha256,
        "archiveCommentCommit": spec.archive_commit,
        "fileCount": len(records),
        "uncompressedFileBytes": total_uncompressed,
        "declaredLicense": spec.declared_license,
        "rootLicensePresent": spec.root_license_present,
        "productBuildInclusion": False,
    }
    for field, expected in expected_fields.items():
        if manifest.get(field) != expected:
            raise ValueError(
                f"{spec.display_name}: manifest field {field!r} mismatch: "
                f"{manifest.get(field)!r} != {expected!r}"
            )

    expected_paths = {str(record["path"]): record for record in records}
    actual_paths = {
        path.relative_to(tree).as_posix(): path
        for path in tree.rglob("*")
        if path.is_file()
    }
    if set(actual_paths) != set(expected_paths):
        missing = sorted(set(expected_paths) - set(actual_paths))
        extra = sorted(set(actual_paths) - set(expected_paths))
        raise ValueError(
            f"{spec.display_name}: extracted tree path mismatch; missing={missing[:5]} extra={extra[:5]}"
        )
    for relative, path in actual_paths.items():
        expected = expected_paths[relative]
        actual_sha = sha256_file(path)
        if actual_sha != expected["sha256"]:
            raise ValueError(f"{spec.display_name}: extracted file hash mismatch: {relative}")
        # ZIP records retain the authoritative POSIX mode.  Windows extraction APIs expose
        # regular files but do not preserve the executable bit in NTFS metadata; compare the
        # materialized bit only on platforms that can represent it.
        if not windows_backed_filesystem():
            actual_mode = "0755" if path.stat().st_mode & stat.S_IXUSR else "0644"
            if actual_mode != expected["mode"]:
                raise ValueError(
                    f"{spec.display_name}: mode mismatch for {relative}: {actual_mode} != {expected['mode']}"
                )

    expected_hashes = "\n".join(
        f"{record['sha256']}  {record['mode']}  {record['size']}  {record['path']}"
        for record in records
    ) + "\n"
    if hashes_path.read_text(encoding="utf-8") != expected_hashes:
        raise ValueError(f"{spec.display_name}: tree hash manifest mismatch")

    for license_path in spec.license_paths:
        if not (tree / license_path).is_file():
            raise ValueError(f"{spec.display_name}: declared license path missing: {license_path}")
    for notice_path in spec.restriction_notice_paths:
        if not (tree / notice_path).is_file():
            raise ValueError(f"{spec.display_name}: restriction notice path missing: {notice_path}")

    print(
        f"PASS verified {spec.display_name} reference snapshot: "
        f"files={len(records)} commit={spec.archive_commit}"
    )


def verify_build_isolation() -> None:
    settings = (ROOT / "settings.gradle").read_text(encoding="utf-8")
    if "ref/" in settings or "ref:" in settings or "ref.upstream" in settings:
        raise ValueError("settings.gradle must not include reference source modules")
    for path in ROOT.rglob("*.gradle"):
        if not path.is_file() or REF_ROOT in path.parents:
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        if "ref/upstream" in text or "ref\\upstream" in text:
            raise ValueError(f"product Gradle file references upstream source tree: {path.relative_to(ROOT)}")
    attributes = (ROOT / ".gitattributes").read_text(encoding="utf-8")
    if "ref/upstream/** binary linguist-vendored" not in attributes:
        raise ValueError("reference source bytes must be protected from Git text normalization")
    release_script = (ROOT / "scripts/package-release.sh").read_text(encoding="utf-8")
    if "--exclude-prefix ref/" not in release_script:
        raise ValueError("product source release must exclude ref/")
    print("PASS reference sources are isolated from product Gradle modules and release packages")


def verify_sources(_: argparse.Namespace) -> None:
    for spec in SPECS:
        verify_source(spec)
    verify_build_isolation()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    importer = subparsers.add_parser("import", help="Import the two locked upstream ZIP snapshots")
    importer.add_argument("--newblackbox", required=True)
    importer.add_argument("--virtualapp", required=True)
    importer.set_defaults(func=import_sources)
    verifier = subparsers.add_parser("verify", help="Verify archives, manifests and extracted trees")
    verifier.set_defaults(func=verify_sources)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        args.func(args)
    except (OSError, ValueError, zipfile.BadZipFile, json.JSONDecodeError) as exc:
        print(f"FAIL reference source operation: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
