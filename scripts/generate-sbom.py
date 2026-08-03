#!/usr/bin/env python3
from __future__ import annotations

import argparse
from hashlib import sha256
import json
import re
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "verification/sbom.json"
SETTINGS = ROOT / "settings.gradle"
RELEASE_IDENTITY = (
    ROOT
    / "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/ControlledReleaseIdentity.java"
).read_text(encoding="utf-8")
VERSION_MATCH = re.search(r'VERSION_NAME\s*=\s*"([^"]+)"', RELEASE_IDENTITY)
if VERSION_MATCH is None:
    raise SystemExit("ControlledReleaseIdentity.java has no VERSION_NAME")
PROJECT_VERSION = VERSION_MATCH.group(1)
INCLUDE_STATEMENT = re.compile(r"(?ms)^\s*include\s*(?:\((.*?)\)|([^\n]*))")
QUOTED_PROJECT = re.compile(r"['\"](:[^'\"]+)['\"]")


def sha256_file(path: Path) -> str:
    digest = sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def included_projects() -> list[tuple[str, str, Path]]:
    text = SETTINGS.read_text(encoding="utf-8")
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
    text = "\n".join(line.split("//", 1)[0] for line in text.splitlines())
    projects: list[tuple[str, str, Path]] = []
    seen: set[str] = set()
    for match in INCLUDE_STATEMENT.finditer(text):
        payload = match.group(1) if match.group(1) is not None else match.group(2)
        for project_path in QUOTED_PROJECT.findall(payload or ""):
            if project_path in seen:
                raise SystemExit(f"duplicate Gradle project include: {project_path}")
            seen.add(project_path)
            module_name = project_path[1:]
            directory = ROOT.joinpath(*module_name.split(":"))
            if not directory.is_dir():
                raise SystemExit(f"Gradle project directory is missing: {project_path} -> {directory}")
            if not (directory / "build.gradle").is_file() and not (directory / "build.gradle.kts").is_file():
                raise SystemExit(f"Gradle project has no build script: {project_path}")
            projects.append((project_path, module_name, directory))
    if not projects:
        raise SystemExit("settings.gradle contains no included projects")
    return projects


def component_identity(module_name: str, directory: Path) -> tuple[str, str]:
    build_path = directory / "build.gradle"
    if not build_path.is_file():
        build_path = directory / "build.gradle.kts"
    build_text = build_path.read_text(encoding="utf-8")
    component_type = "application" if "com.android.application" in build_text else "library"
    if module_name == "app":
        role = "host"
    elif module_name.startswith("fixture-"):
        role = "fixture"
    elif "companion" in module_name:
        role = "companion"
    else:
        role = "component"
    return component_type, role


def component(project_path: str, module_name: str, directory: Path) -> dict[str, Any]:
    files = sorted(
        path for path in directory.rglob("*")
        if path.is_file() and "build" not in path.relative_to(directory).parts
    )
    digest = sha256()
    languages: set[str] = set()
    for path in files:
        relative = path.relative_to(ROOT).as_posix().encode("utf-8")
        digest.update(relative + b"\0")
        digest.update(path.read_bytes())
        suffix = path.suffix.lower()
        languages.add({
            ".java": "Java",
            ".aidl": "AIDL",
            ".cpp": "C++",
            ".h": "C++",
            ".xml": "XML",
            ".gradle": "Gradle",
            ".kts": "Gradle",
        }.get(suffix, suffix.lstrip(".") or "binary"))
    component_type, role = component_identity(module_name, directory)
    return {
        "type": component_type,
        "role": role,
        "name": module_name,
        "gradleProjectPath": project_path,
        "version": PROJECT_VERSION,
        "license": "Apache-2.0",
        "fileCount": len(files),
        "languages": sorted(languages),
        "sourceDigestSha256": digest.hexdigest(),
    }


def build_document() -> dict[str, Any]:
    projects = included_projects()
    return {
        "bomFormat": "ControlledSandbox-SBOM",
        "specVersion": "1.1",
        "project": "controlled-sandbox-cleanroom",
        "version": PROJECT_VERSION,
        "settingsGradleSha256": sha256_file(SETTINGS),
        "includedProjects": [project_path for project_path, _, _ in projects],
        "components": [component(*project) for project in projects],
        "externalRuntimeDependencies": [
            {"name": "Android platform APIs", "scope": "provided"},
            {"name": "Android Gradle Plugin", "version": "8.11.1", "scope": "build"},
            {"name": "Gradle", "version": "8.13", "scope": "build"},
        ],
    }


def explain_difference(actual: dict[str, Any], expected: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    actual_projects = actual.get("includedProjects", [])
    expected_projects = expected["includedProjects"]
    if actual_projects != expected_projects:
        errors.append(
            f"includedProjects mismatch expected={expected_projects} actual={actual_projects}"
        )
    actual_components = {
        item.get("gradleProjectPath"): item for item in actual.get("components", [])
        if isinstance(item, dict)
    }
    expected_components = {
        item["gradleProjectPath"]: item for item in expected["components"]
    }
    if set(actual_components) != set(expected_components):
        errors.append(
            "component project set mismatch "
            f"expected={sorted(expected_components)} actual={sorted(actual_components)}"
        )
    for project_path in sorted(set(actual_components) & set(expected_components)):
        actual_component = actual_components[project_path]
        expected_component = expected_components[project_path]
        for field in ("type", "role", "name", "fileCount", "languages", "sourceDigestSha256"):
            if actual_component.get(field) != expected_component.get(field):
                errors.append(
                    f"{project_path} {field} mismatch "
                    f"expected={expected_component.get(field)!r} actual={actual_component.get(field)!r}"
                )
    if actual.get("settingsGradleSha256") != expected.get("settingsGradleSha256"):
        errors.append("settings.gradle digest mismatch")
    if not errors and actual != expected:
        errors.append("SBOM document metadata differs from generated document")
    return errors


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate or verify the repository SBOM")
    parser.add_argument(
        "--check", action="store_true",
        help="verify the checked-in SBOM without modifying the worktree",
    )
    args = parser.parse_args()
    expected = build_document()
    if args.check:
        try:
            actual = json.loads(OUT.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise SystemExit(f"SBOM is missing or invalid: {error}") from error
        errors = explain_difference(actual, expected)
        if errors:
            raise SystemExit("SBOM verification failed:\n- " + "\n- ".join(errors))
        print(
            "PASS verified SBOM against settings.gradle "
            f"({len(expected['components'])} components)"
        )
        return
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(expected, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        "PASS generated SBOM from settings.gradle "
        f"({len(expected['components'])} components)"
    )


if __name__ == "__main__":
    main()
