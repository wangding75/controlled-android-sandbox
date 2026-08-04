#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Iterable

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW_DIR = ROOT / ".github" / "workflows"
ACTION_LOCK = ROOT / "verification" / "github-actions-lock.json"
APPROVED_IDENTITIES = ROOT / "verification" / "approved-commit-identities.txt"
PROVENANCE = ROOT / "gradle" / "dependency-verification-provenance.json"
METADATA = ROOT / "gradle" / "verification-metadata.xml"
KEYRING = ROOT / "gradle" / "verification-keyring.keys"
REVIEWED_COORDINATES = ROOT / "gradle" / "reviewed-dependency-coordinates.json"
WRAPPER = ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties"
IDENTITY_POLICY_BASELINE = "7370154b2f9dadcb41bd5a0d49afc8c6a4886bd2"
HEX40 = re.compile(r"^[0-9a-f]{40}$")
HEX64 = re.compile(r"^[0-9a-f]{64}$")
USES_RE = re.compile(r"^\s*-?\s*uses:\s*([^\s#]+)", re.MULTILINE)
RUNNER_RE = re.compile(r"^\s*runs-on:\s*([^\s#]+)", re.MULTILINE)
DYNAMIC_VERSION_RE = re.compile(
    r"(?:\+|latest(?:\.|[-_])|snapshot|release\s*\]|\[[^\]]*[,)]|\([^)]*,[^)]*\))",
    re.IGNORECASE,
)
PLUGIN_VERSION_RE = re.compile(r"\bversion\s+['\"]([^'\"]+)['\"]")
COORDINATE_RE = re.compile(r"['\"]([^:'\"\s]+):([^:'\"\s]+):([^'\"\s]+)['\"]")
CATALOG_VERSION_RE = re.compile(r"^\s*[A-Za-z0-9_.-]+\s*=\s*['\"]([^'\"]+)['\"]\s*$")
NS = {"v": "https://schema.gradle.org/dependency-verification"}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_noncomment_lines(path: Path) -> set[str]:
    return {
        line.strip()
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    }


def command(*args: str) -> str:
    completed = subprocess.run(
        args,
        cwd=ROOT,
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    return completed.stdout


def validate_workflow_text(text: str, lock: dict) -> list[str]:
    errors: list[str] = []
    allowed_runners = set(lock["runnerLabels"])
    locked_actions = lock["actions"]
    seen_actions: set[str] = set()

    runners = RUNNER_RE.findall(text)
    if not runners:
        errors.append("workflow has no runs-on label")
    for runner in runners:
        runner = runner.strip("'\"")
        if runner.endswith("-latest") or runner == "ubuntu-latest":
            errors.append(f"floating runner label forbidden: {runner}")
        elif runner not in allowed_runners:
            errors.append(f"runner label not in lock: {runner}")

    uses = USES_RE.findall(text)
    if not uses:
        errors.append("workflow has no actions")
    for value in uses:
        if value.startswith("./"):
            continue
        if "@" not in value:
            errors.append(f"action has no immutable revision: {value}")
            continue
        name, revision = value.rsplit("@", 1)
        seen_actions.add(name)
        if not HEX40.fullmatch(revision):
            errors.append(f"action is not pinned to a full commit SHA: {value}")
            continue
        expected = locked_actions.get(name)
        if expected is None:
            errors.append(f"action missing from lock: {name}")
        elif revision != expected["sha"]:
            errors.append(
                f"action SHA differs from lock: {name} expected={expected['sha']} actual={revision}"
            )

    stale = sorted(set(locked_actions) - seen_actions)
    if stale:
        errors.append(f"stale action lock entries: {stale}")
    if not re.search(r"^permissions:\s*\n\s+contents:\s*read\s*$", text, re.MULTILINE):
        errors.append("workflow must use top-level contents: read permissions")
    if "persist-credentials: false" not in text:
        errors.append("checkout must disable persisted credentials")
    java_lock = lock.get("java", {})
    expected_java = str(java_lock.get("version", ""))
    if not expected_java or f"java-version: '{expected_java}'" not in text:
        errors.append("setup-java must use the exact JDK version from the action lock")
    if "check-latest: false" not in text or java_lock.get("checkLatest") is not False:
        errors.append("setup-java must disable check-latest")
    gate_command = "python3 scripts/check-m5-t19-1-u-supply-chain-governance.py"
    gradle_command = "--dependency-verification=strict"
    if gate_command not in text:
        errors.append("workflow does not run the source supply-chain gate")
    if "--dependency-verification=strict" not in text:
        errors.append("workflow does not execute Gradle in strict dependency-verification mode")
    if "resolveAndLockAll --write-locks" not in text:
        errors.append("workflow does not generate Gradle dependency lock state")
    if "python3 tools/gradle_lock_state.py verify --require-clean" not in text:
        errors.append("workflow does not compare generated lock state with checked-in locks")
    if gradle_command in text and gate_command in text and text.index(gate_command) > text.index(gradle_command):
        errors.append("source supply-chain gate must run before Gradle dependency resolution")
    if "apt-get" in text or "curl " in text or "wget " in text:
        errors.append("workflow performs unverified mutable package or script downloads")
    return errors


def validate_dependency_metadata(metadata: Path, keyring: Path, provenance: dict) -> list[str]:
    errors: list[str] = []
    for relative, expected in provenance.get("files", {}).items():
        path = ROOT / relative
        if not path.is_file():
            errors.append(f"dependency verification file missing: {relative}")
            continue
        if not HEX64.fullmatch(expected):
            errors.append(f"invalid recorded SHA-256: {relative}")
            continue
        actual = sha256(path)
        if actual != expected:
            errors.append(f"dependency verification provenance mismatch: {relative}")

    try:
        tree = ET.parse(metadata)
    except (ET.ParseError, OSError) as exc:
        return errors + [f"invalid dependency verification XML: {exc}"]
    root = tree.getroot()
    configuration = root.find("v:configuration", NS)
    components = root.find("v:components", NS)
    if configuration is None or components is None:
        errors.append("verification metadata lacks configuration or components")
        return errors

    def text_of(name: str) -> str:
        element = configuration.find(f"v:{name}", NS)
        return "" if element is None or element.text is None else element.text.strip().lower()

    if text_of("verify-metadata") != "true":
        errors.append("verify-metadata must be true")
    if text_of("verify-signatures") != "true":
        errors.append("verify-signatures must be true")
    if text_of("keyring-format") != "armored":
        errors.append("keyring-format must be armored")
    key_servers = configuration.find("v:key-servers", NS)
    if key_servers is None or key_servers.attrib.get("enabled", "").lower() != "false":
        errors.append("network key servers must be disabled")
    trusted_artifacts = configuration.find("v:trusted-artifacts", NS)
    if trusted_artifacts is not None and len(list(trusted_artifacts)) != 0:
        errors.append("trusted-artifact bypasses are forbidden; use signatures or exact checksums")
    trusted_artifacts = configuration.find("v:trusted-artifacts", NS)
    if trusted_artifacts is not None and len(list(trusted_artifacts)) != 0:
        errors.append("trusted-artifacts exceptions are forbidden; use signatures or exact checksums")
    trusted_keys = configuration.find("v:trusted-keys", NS)
    if trusted_keys is None or len(list(trusted_keys)) == 0:
        errors.append("trusted key list must not be empty")
    if len(list(components)) == 0:
        errors.append("checksum component list must not be empty")
    if not keyring.is_file() or "BEGIN PGP PUBLIC KEY BLOCK" not in keyring.read_text(
        encoding="utf-8", errors="replace"
    ):
        errors.append("armored verification keyring is missing or invalid")
    return errors



def validate_reviewed_coordinate_manifest(lock_path: Path, metadata: Path) -> tuple[list[str], dict]:
    errors: list[str] = []
    try:
        lock = json.loads(lock_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return [f"invalid reviewed dependency coordinate manifest: {exc}"], {}

    policy = lock.get("policy", {})
    required_policy = {
        "verificationMode": "strict",
        "metadataVerification": True,
        "signatures": True,
        "dynamicVersionsAllowed": False,
        "changingModulesAllowed": False,
        "keyServersEnabled": False,
        "dependencyLockingActivated": True,
    }
    for key, expected in required_policy.items():
        if policy.get(key) != expected:
            errors.append(f"reviewed dependency coordinate manifest policy mismatch: {key}")

    root_plugin = lock.get("rootPlugin", {})
    if root_plugin.get("coordinate") != "com.android.tools.build:gradle:8.11.1":
        errors.append("root Android Gradle Plugin coordinate is not locked to 8.11.1")
    artifacts = root_plugin.get("artifacts", {})
    for name in ("gradle-8.11.1.jar", "gradle-8.11.1.pom"):
        value = artifacts.get(name, "")
        if not HEX64.fullmatch(value):
            errors.append(f"root plugin artifact checksum missing or invalid: {name}")

    coordinates = lock.get("exactCoordinateTrust", [])
    if coordinates != sorted(set(coordinates)):
        errors.append("reviewed dependency coordinates must be sorted and unique")
    coordinate_re = re.compile(r"^[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[^+*\[\](),]+$")
    for coordinate in coordinates:
        if not coordinate_re.fullmatch(coordinate):
            errors.append(f"invalid or non-exact dependency coordinate: {coordinate}")
    required_markers = {
        "com.android.application:com.android.application.gradle.plugin:8.11.1",
        "com.android.library:com.android.library.gradle.plugin:8.11.1",
    }
    missing_markers = sorted(required_markers - set(coordinates))
    if missing_markers:
        errors.append(f"plugin marker coordinates missing from reviewed coordinate manifest: {missing_markers}")

    build_text = (ROOT / "build.gradle").read_text(encoding="utf-8")
    if "dependencyLocking" not in build_text or "lockAllConfigurations()" not in build_text:
        errors.append("Gradle dependency locking is not activated for all project configurations")
    if "failOnVersionConflict()" not in build_text:
        errors.append("Gradle version conflicts are not fail-closed")
    plugin_rows = re.findall(r"id\s+'([^']+)'\s+version\s+'([^']+)'", build_text)
    actual_markers = {f"{plugin}:{plugin}.gradle.plugin:{version}" for plugin, version in plugin_rows}
    if actual_markers != required_markers:
        errors.append(f"Gradle plugin declarations differ from reviewed coordinate manifest: {sorted(actual_markers)}")

    try:
        tree = ET.parse(metadata)
        root = tree.getroot()
        components = root.find("v:components", NS)
        component = None if components is None else components.find(
            "v:component[@group='com.android.tools.build'][@name='gradle'][@version='8.11.1']", NS
        )
        if component is None:
            errors.append("verification metadata lacks checksum-pinned AGP 8.11.1 component")
        else:
            observed = {}
            for artifact in component.findall("v:artifact", NS):
                digest = artifact.find("v:sha256", NS)
                if digest is not None:
                    observed[artifact.attrib.get("name", "")] = digest.attrib.get("value", "")
            for name, expected in artifacts.items():
                if observed.get(name) != expected:
                    errors.append(f"verification metadata checksum differs from reviewed manifest: {name}")
    except (ET.ParseError, OSError) as exc:
        errors.append(f"cannot compare dependency lock to verification metadata: {exc}")

    return errors, {
        "manifestPath": str(lock_path.relative_to(ROOT)),
        "manifestSha256": sha256(lock_path),
        "rootPlugin": root_plugin,
        "reviewedCoordinateCount": len(coordinates),
        "reviewedCoordinates": coordinates,
    }

def validate_reviewed_coordinates(provenance: dict) -> list[str]:
    errors: list[str] = []
    reviewed = provenance.get("reviewedCoordinateManifest", {})
    relative = reviewed.get("path")
    expected_sha = reviewed.get("sha256")
    expected_count = reviewed.get("coordinateCount")
    if relative != "gradle/reviewed-dependency-coordinates.json":
        errors.append("reviewed coordinate manifest path is missing or unexpected")
        return errors
    path = ROOT / relative
    if not path.is_file():
        errors.append("reviewed coordinate manifest is missing")
        return errors
    if not isinstance(expected_sha, str) or not HEX64.fullmatch(expected_sha):
        errors.append("reviewed coordinate manifest SHA-256 is invalid")
    elif sha256(path) != expected_sha:
        errors.append("reviewed coordinate manifest provenance mismatch")
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return errors + [f"invalid reviewed coordinate manifest: {exc}"]
    coordinates = manifest.get("exactCoordinateTrust", [])
    if expected_count != len(coordinates):
        errors.append("reviewed coordinate count differs from provenance")
    if len(coordinates) != len(set(coordinates)):
        errors.append("reviewed coordinate manifest contains duplicates")
    coordinate_pattern = re.compile(r"^[^:\s]+:[^:\s]+:[^:\s]+$")
    for coordinate in coordinates:
        if not isinstance(coordinate, str) or not coordinate_pattern.fullmatch(coordinate):
            errors.append(f"reviewed dependency coordinate is not exact: {coordinate!r}")
        elif DYNAMIC_VERSION_RE.search(coordinate.rsplit(":", 1)[-1]):
            errors.append(f"reviewed dependency coordinate uses a dynamic version: {coordinate}")
    if manifest.get("policy", {}).get("signatures") is not True:
        errors.append("reviewed coordinate policy must require signatures")
    if manifest.get("policy", {}).get("keyServersEnabled") is not False:
        errors.append("reviewed coordinate policy must disable network key servers")
    return errors


def gradle_source_files() -> Iterable[Path]:
    for pattern in ("*.gradle", "*.gradle.kts", "gradle/libs.versions.toml"):
        yield from ROOT.glob(pattern)
        yield from ROOT.glob(f"*/{pattern}")


def validate_gradle_configuration() -> list[str]:
    errors: list[str] = []
    wrapper = WRAPPER.read_text(encoding="utf-8")
    match = re.search(r"^distributionSha256Sum=([0-9a-f]{64})$", wrapper, re.MULTILINE)
    if match is None:
        errors.append("Gradle wrapper distributionSha256Sum is missing or invalid")
    if "validateDistributionUrl=true" not in wrapper:
        errors.append("Gradle wrapper URL validation is disabled")
    if not re.search(r"distributionUrl=.*gradle-[0-9]+(?:\.[0-9]+)+-bin\.zip$", wrapper, re.MULTILINE):
        errors.append("Gradle wrapper distribution URL is not an exact binary version")

    settings = (ROOT / "settings.gradle").read_text(encoding="utf-8")
    if "RepositoriesMode.FAIL_ON_PROJECT_REPOS" not in settings:
        errors.append("project repositories are not centrally constrained")
    if "mavenLocal()" in settings or "jcenter()" in settings:
        errors.append("mutable local/deprecated repository is configured")

    for path in sorted(set(gradle_source_files())):
        text = path.read_text(encoding="utf-8", errors="replace")
        for line_number, line in enumerate(text.splitlines(), 1):
            candidates = [match.group(1) for match in PLUGIN_VERSION_RE.finditer(line)]
            candidates.extend(match.group(3) for match in COORDINATE_RE.finditer(line))
            if path.name == "libs.versions.toml":
                catalog = CATALOG_VERSION_RE.match(line)
                if catalog is not None:
                    candidates.append(catalog.group(1))
            for version in candidates:
                if DYNAMIC_VERSION_RE.search(version):
                    errors.append(
                        f"dynamic Gradle version in {path.relative_to(ROOT)}:{line_number}: {version}"
                    )
    return errors


def validate_commit_identities() -> tuple[list[str], dict]:
    errors: list[str] = []
    approved = read_noncomment_lines(APPROVED_IDENTITIES)
    if not approved:
        errors.append("approved commit identity list is empty")
        return errors, {}
    if not (ROOT / ".mailmap").is_file():
        errors.append(".mailmap is missing")
        return errors, {}

    canonical_rows = command(
        "git", "log", "--all", "--format=%aN <%aE>%x09%cN <%cE>"
    ).splitlines()
    canonical_identities = {
        identity for row in canonical_rows for identity in row.split("\t") if identity
    }
    unknown = sorted(canonical_identities - approved)
    if unknown:
        errors.append(f"unapproved canonical historical identities: {unknown}")

    if command("git", "cat-file", "-e", f"{IDENTITY_POLICY_BASELINE}^{{commit}}") is not None:
        recent_rows = command(
            "git",
            "log",
            f"{IDENTITY_POLICY_BASELINE}..HEAD",
            "--format=%an <%ae>%x09%cn <%ce>",
        ).splitlines()
    else:
        recent_rows = []
    recent_raw = {identity for row in recent_rows for identity in row.split("\t") if identity}
    bad_recent = sorted(recent_raw - approved)
    if bad_recent:
        errors.append(f"post-baseline commits use unapproved raw identities: {bad_recent}")

    head = command("git", "show", "-s", "--format=%an <%ae>%x09%cn <%ce>", "HEAD").strip()
    return errors, {
        "approved": sorted(approved),
        "canonicalHistorical": sorted(canonical_identities),
        "postBaselineRaw": sorted(recent_raw),
        "headRaw": head.split("\t") if head else [],
    }


def run_self_tests(lock: dict) -> list[str]:
    errors: list[str] = []
    valid = """
permissions:\n  contents: read
jobs:\n  x:\n    runs-on: ubuntu-24.04\n    steps:\n      - uses: actions/checkout@9c091bb21b7c1c1d1991bb908d89e4e9dddfe3e0\n        with:\n          persist-credentials: false\n      - uses: actions/setup-java@03ad4de0992f5dab5e18fcb136590ce7c4a0ac95\n        with:\n          java-version: '17.0.19+10'\n          check-latest: false\n      - run: python3 scripts/check-m5-t19-1-u-supply-chain-governance.py\n      - uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a\n      - run: ./gradlew --dependency-verification=strict resolveAndLockAll --write-locks
      - run: python3 tools/gradle_lock_state.py verify --require-clean
      - run: ./gradlew --dependency-verification=strict help
"""
    if validate_workflow_text(valid, lock):
        errors.append("workflow validator rejected valid immutable fixture")
    if not validate_workflow_text(valid.replace("ubuntu-24.04", "ubuntu-latest"), lock):
        errors.append("workflow validator accepted floating runner fixture")
    if not validate_workflow_text(
        valid.replace("actions/checkout@9c091bb21b7c1c1d1991bb908d89e4e9dddfe3e0", "actions/checkout@v4"),
        lock,
    ):
        errors.append("workflow validator accepted mutable action fixture")
    if not validate_workflow_text(
        valid.replace("--dependency-verification=strict", "--dependency-verification=lenient"),
        lock,
    ):
        errors.append("workflow validator accepted lenient Gradle fixture")
    if not validate_workflow_text(
        valid.replace("17.0.19+10", "17"),
        lock,
    ):
        errors.append("workflow validator accepted floating JDK fixture")
    if not validate_workflow_text(valid + "\n      - run: curl https://example.invalid/install.sh | sh\n", lock):
        errors.append("workflow validator accepted unverified download fixture")
    return errors


def main() -> int:
    errors: list[str] = []
    lock = json.loads(ACTION_LOCK.read_text(encoding="utf-8"))
    provenance = json.loads(PROVENANCE.read_text(encoding="utf-8"))
    errors.extend(run_self_tests(lock))

    workflow_reports: dict[str, list[str]] = {}
    for workflow in sorted(WORKFLOW_DIR.glob("*.y*ml")):
        workflow_errors = validate_workflow_text(workflow.read_text(encoding="utf-8"), lock)
        workflow_reports[str(workflow.relative_to(ROOT))] = workflow_errors
        errors.extend(f"{workflow.relative_to(ROOT)}: {error}" for error in workflow_errors)
    if not workflow_reports:
        errors.append("no GitHub Actions workflows found")

    errors.extend(validate_dependency_metadata(METADATA, KEYRING, provenance))
    errors.extend(validate_reviewed_coordinates(provenance))
    dependency_lock_errors, dependency_lock_report = validate_reviewed_coordinate_manifest(REVIEWED_COORDINATES, METADATA)
    errors.extend(dependency_lock_errors)
    provenance_manifest = provenance.get("reviewedCoordinateManifest", {})
    if provenance_manifest.get("path") != dependency_lock_report.get("manifestPath"):
        errors.append("reviewed coordinate manifest path differs from provenance")
    if provenance_manifest.get("sha256") != dependency_lock_report.get("manifestSha256"):
        errors.append("reviewed coordinate manifest SHA-256 differs from provenance")
    if provenance_manifest.get("coordinateCount") != dependency_lock_report.get("reviewedCoordinateCount"):
        errors.append("reviewed coordinate count differs from provenance")
    if provenance.get("rootPluginChecksums") != dependency_lock_report.get("rootPlugin"):
        errors.append("root plugin checksum lock differs from provenance")
    errors.extend(validate_gradle_configuration())
    build_source = (ROOT / "build.gradle").read_text(encoding="utf-8")
    lock_tool = (ROOT / "tools/gradle_lock_state.py").read_text(encoding="utf-8")
    if "tasks.register('resolveAndLockAll')" not in build_source or "configuration.incoming.resolutionResult.allComponents" not in build_source:
        errors.append("Gradle resolveAndLockAll task is missing or does not resolve every resolvable configuration")
    if "no Gradle-generated gradle.lockfile exists" not in lock_tool or "--require-clean" not in lock_tool:
        errors.append("Gradle lock-state verifier is missing generated-file and clean-tree enforcement")
    identity_errors, identity_report = validate_commit_identities()
    errors.extend(identity_errors)

    dependabot = (ROOT / ".github" / "dependabot.yml").read_text(encoding="utf-8")
    if "package-ecosystem: github-actions" not in dependabot:
        errors.append("Dependabot GitHub Actions updates are not configured")
    if "package-ecosystem: gradle" not in dependabot:
        errors.append("Dependabot Gradle updates are not configured")

    report = {
        "task": "M5-T19.1-U",
        "finding": "P3 immutable CI, Gradle dependency verification, and commit identity governance",
        "sourceStatus": "PASS" if not errors else "FAIL",
        "checks": {
            "fixedRunnerLabels": True,
            "actionsPinnedToFullCommitSha": True,
            "actionsMatchReviewedLock": True,
            "checkoutCredentialsNotPersisted": True,
            "gradleDependencyVerificationStrictInCi": True,
            "verificationMetadataAndSignaturesEnabled": True,
            "networkKeyServersDisabled": True,
            "verificationFilesMatchReviewedProvenance": True,
            "trustedArtifactBypassesForbidden": True,
            "reviewedCoordinateManifestPinned": True,
            "gradleWrapperChecksumPinned": True,
            "dynamicDependencyVersionsForbidden": True,
            "historicalIdentitiesNormalizedByMailmap": True,
            "postBaselineRawIdentityEnforced": True,
        },
        "workflowResults": workflow_reports,
        "actionLock": lock,
        "dependencyVerificationProvenance": provenance,
        "reviewedCoordinateManifest": dependency_lock_report,
        "gradleLockStateClaimedBySourceGate": False,
        "commitIdentity": identity_report,
        "errors": errors,
        "androidGradleBuildEvidenceCount": 0,
        "deviceEvidenceCount": 0,
    }
    output = ROOT / "build" / "verification" / "m5-t19-1-u-supply-chain-governance.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    if errors:
        print("FAIL M5-T19.1-U supply-chain governance", file=sys.stderr)
        for error in errors:
            print(" - " + error, file=sys.stderr)
        return 1
    print(
        "PASS M5-T19.1-U supply-chain governance "
        f"(workflows={len(workflow_reports)} actions={len(lock['actions'])} "
        f"runner={','.join(lock['runnerLabels'])} canonicalIdentities={len(identity_report['canonicalHistorical'])})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
