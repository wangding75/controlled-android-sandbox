#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
python3 scripts/check-build-environment.py --android
./scripts/check-wrapper-bootstrap.sh
export TZ=UTC LC_ALL=C LANG=C
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-"$ROOT/.gradle-reproducible"}
./gradlew --no-daemon --no-build-cache --no-parallel --refresh-dependencies help
./gradlew --no-daemon --no-build-cache --no-parallel \
  :fixture-basic:assembleRelease :app:assembleRelease
printf 'PASS populated locked Gradle cache at %s\n' "$GRADLE_USER_HOME"
