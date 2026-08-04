#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / "build/verification/release-apk-signing.json"
DIGEST_PATTERN = re.compile(
    r"Signer\s+#\d+\s+certificate\s+SHA-256\s+digest:\s*([0-9a-fA-F:]+)", re.I
)


def apksigner_path() -> Path | None:
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk:
        return None
    tools = "35.0.0"
    name = "apksigner.bat" if os.name == "nt" else "apksigner"
    return Path(sdk) / "build-tools" / tools / name


def verify_apk(apksigner: Path, path: Path) -> dict[str, object]:
    if not path.is_file():
        raise RuntimeError(f"APK is missing: {path}")
    result = subprocess.run(
        [str(apksigner), "verify", "--verbose", "--print-certs", str(path)],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    if result.returncode != 0:
        raise RuntimeError(f"apksigner rejected {path}: {result.stdout.strip()}")
    match = DIGEST_PATTERN.search(result.stdout)
    if not match:
        raise RuntimeError(f"apksigner did not report a signer SHA-256 digest for {path}")
    digest = match.group(1).replace(":", "").lower()
    return {
        "path": str(path.resolve()),
        "size": path.stat().st_size,
        "signerSha256": digest,
        "verified": True,
    }


def write_report(payload: dict[str, object]) -> None:
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("verify",))
    parser.add_argument("--host", type=Path, required=True)
    parser.add_argument("--companion", type=Path, required=True)
    parser.add_argument("--fixture64", type=Path, required=True)
    parser.add_argument("--fixture32", type=Path, required=True)
    args = parser.parse_args()

    signer = apksigner_path()
    errors: list[str] = []
    artifacts: dict[str, dict[str, object]] = {}
    if signer is None:
        errors.append("ANDROID_SDK_ROOT or ANDROID_HOME is not set")
    elif not signer.is_file():
        errors.append(f"locked apksigner is unavailable: {signer}")
    else:
        for name, path in {
            "host": args.host,
            "companion32": args.companion,
            "fixture64": args.fixture64,
            "fixture32": args.fixture32,
        }.items():
            try:
                artifacts[name] = verify_apk(signer, path)
            except RuntimeError as error:
                errors.append(str(error))
        if not errors and artifacts["host"]["signerSha256"] != artifacts["companion32"]["signerSha256"]:
            errors.append("Host and Companion32 signer SHA-256 digests differ")

    payload: dict[str, object] = {
        "status": "PASS" if not errors else "FAIL",
        "apksigner": str(signer) if signer else "",
        "hostCompanionSignerMatch": bool(
            not errors
            and artifacts.get("host", {}).get("signerSha256")
            == artifacts.get("companion32", {}).get("signerSha256")
        ),
        "artifacts": artifacts,
        "errors": errors,
    }
    write_report(payload)
    if errors:
        print("FAIL release APK signing verification", file=sys.stderr)
        for error in errors:
            print(" - " + error, file=sys.stderr)
        return 1
    print("PASS release APK signatures and Host/Companion signer continuity")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
