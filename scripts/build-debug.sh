#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
python3 scripts/check-build-environment.py --android
./scripts/check-wrapper-bootstrap.sh
./gradlew --no-daemon assembleDebug
