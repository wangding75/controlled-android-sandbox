#!/usr/bin/env python3
"""Static regression guard for the asynchronous launch-readiness publication boundary."""

from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / (
    "sandbox-runtime/src/main/java/"
    "com/warden/controlledsandbox/runtime/broker/RuntimeActivityLaunchCoordinator.java"
)


def main() -> int:
    text = SOURCE.read_text(encoding="utf-8")
    start = text.index("private void scheduleReadinessObservation(")
    end = text.index("\n    private static String taskObservationKey", start)
    method = text[start:end]
    publish = method.index("owner.publishLaunchReadiness(activityToken, details);")
    remove = method.index("removeObservationMappings(observation);")
    if publish > remove:
        print("FAIL terminal readiness is removed before it is published")
        return 1
    if method.count("owner.publishLaunchReadiness(activityToken, details);") != 1:
        print("FAIL asynchronous readiness must publish exactly once")
        return 1
    if method.count("removeObservationMappings(observation);") != 1:
        print("FAIL asynchronous readiness must remove observation mappings exactly once")
        return 1
    print("PASS C4-R03 readiness publication closes the observe/remove race")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
