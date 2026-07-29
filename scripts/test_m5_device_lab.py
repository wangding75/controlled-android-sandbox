#!/usr/bin/env python3
from __future__ import annotations

import copy
import json
import tempfile
import unittest
from pathlib import Path

import m5_device_lab as lab


ROOT = Path(__file__).resolve().parents[1]
LOCK = json.loads((ROOT / "build-environment.lock.json").read_text())
COMMIT = "a" * 40


def formal_evidence() -> dict:
    commands = []
    for fixture in lab.FIXTURE_IDS:
        for user in lab.VIRTUAL_USERS:
            for command in lab.COMMANDS:
                result = {
                    "fixtureId": fixture,
                    "virtualUserId": user,
                    "command": command,
                    "status": "PASS",
                }
                if fixture == "fixture32":
                    result["companion"] = {
                        "successful": True,
                        "processBitness": 32,
                        "requestedAbi": "x86",
                        "nativeStatus": "bitness=32;abi=x86",
                    }
                commands.append(result)
    return {
        "schemaVersion": 1,
        "task": "M5-T5",
        "status": "PASS",
        "formal": True,
        "commit": COMMIT,
        "requestedStabilitySeconds": 1200,
        "observedStabilitySeconds": 1200,
        "deviceRunCount": 1,
        "device": {"serial": "emulator-5554", "abilist": ["x86_64", "x86"]},
        "artifacts": [
            {"id": item, "sha256": str(index + 1) * 64}
            for index, item in enumerate(lab.ARTIFACT_IDS)
        ],
        "commandResults": commands,
        "companionEvidence": {
            "pid": "1234",
            "processBitness": 32,
            "successfulProbeCount": 6,
        },
        "runtimeDiagnostics": [{"file": "runtime.jsonl", "sha256": "f" * 64}],
        "fatalFindings": [],
    }


class DeviceLabUnitTest(unittest.TestCase):
    def test_parse_adb_devices(self) -> None:
        text = "List of devices attached\nemulator-5554\tdevice product:sdk\nserial2\toffline\n"
        self.assertEqual(["emulator-5554"], lab.parse_adb_devices(text))

    def test_windows_batch_command_is_explicit(self) -> None:
        command = lab.executable_command(Path("avdmanager.bat"), "create", "avd")
        if lab.os.name == "nt":
            self.assertEqual(["cmd.exe", "/d", "/s", "/c", "avdmanager.bat", "create", "avd"], command)
        else:
            self.assertEqual(["avdmanager.bat", "create", "avd"], command)

    def test_fatal_findings(self) -> None:
        self.assertEqual([], lab.fatal_findings("normal log"))
        self.assertTrue(lab.fatal_findings("FATAL EXCEPTION: main"))
        self.assertTrue(lab.fatal_findings("ANR in com.warden.controlledsandbox.debug"))

    def test_formal_evidence_passes(self) -> None:
        self.assertEqual([], lab.validate_formal_evidence(formal_evidence(), LOCK, COMMIT))

    def test_short_run_cannot_pass(self) -> None:
        evidence = formal_evidence()
        evidence["observedStabilitySeconds"] = 1199
        self.assertTrue(any("stability duration" in item for item in lab.validate_formal_evidence(evidence, LOCK, COMMIT)))

    def test_missing_companion_probe_cannot_pass(self) -> None:
        evidence = formal_evidence()
        target = next(item for item in evidence["commandResults"] if item["fixtureId"] == "fixture32")
        target.pop("companion")
        self.assertTrue(any("Companion32 probe" in item for item in lab.validate_formal_evidence(evidence, LOCK, COMMIT)))

    def test_fatal_log_cannot_pass(self) -> None:
        evidence = formal_evidence()
        evidence["fatalFindings"] = ["FATAL EXCEPTION"]
        self.assertTrue(any("fatal findings" in item for item in lab.validate_formal_evidence(evidence, LOCK, COMMIT)))

    def test_build_manifest_validates_hashes(self) -> None:
        with tempfile.TemporaryDirectory() as value:
            root = Path(value)
            artifacts = []
            for configured in LOCK["deviceLabBuild"]["artifacts"]:
                name = f"{configured['id']}-debug.apk"
                path = root / name
                path.write_bytes((configured["id"] + "-apk").encode())
                artifacts.append({
                    "id": configured["id"],
                    "applicationId": configured["applicationId"],
                    "abis": sorted(configured["allowedAbis"]),
                    "collectedName": name,
                    "sha256": lab.sha256(path),
                    "size": path.stat().st_size,
                })
            manifest = {"schemaVersion": 1, "profile": "device-lab", "commit": COMMIT, "artifacts": artifacts}
            self.assertEqual([], lab.validate_build_manifest(manifest, root, LOCK, COMMIT))
            (root / "fixture32-debug.apk").write_bytes(b"tampered")
            self.assertTrue(any("fixture32 SHA-256" in item for item in lab.validate_build_manifest(manifest, root, LOCK, COMMIT)))


if __name__ == "__main__":
    unittest.main()
