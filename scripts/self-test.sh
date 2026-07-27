#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
BUILD="$ROOT/build/self-test"
rm -rf "$BUILD"
mkdir -p "$BUILD/domain" "$BUILD/wrapper"

mapfile -t DOMAIN_MAIN < <(find "$ROOT/sandbox-domain/src/main/java" -name '*.java' | sort)
mapfile -t DOMAIN_TEST < <(find "$ROOT/sandbox-domain/src/testHarness/java" -name '*.java' | sort)
javac --release 17 -d "$BUILD/domain" "${DOMAIN_MAIN[@]}" "${DOMAIN_TEST[@]}"
java -cp "$BUILD/domain" com.warden.controlledsandbox.domain.SelfTest

javac --release 17 -d "$BUILD/wrapper" "$ROOT/tools/wrapper-src/org/gradle/wrapper/GradleWrapperMain.java"
jar --create --file "$BUILD/gradle-wrapper.jar" -C "$BUILD/wrapper" .

python3 - "$ROOT" <<'PYSELFTEST'
from pathlib import Path
import sys, xml.etree.ElementTree as ET
root=Path(sys.argv[1])
for p in root.rglob('*.xml'):
    if 'build/' not in str(p): ET.parse(p)
required=['settings.gradle','app/build.gradle','sandbox-runtime/src/main/AndroidManifest.xml','docs/CLEAN_ROOM_POLICY.md']
for rel in required:
    if not (root/rel).is_file(): raise SystemExit('missing '+rel)
debug_manifest=ET.parse(root/'app/src/debug/AndroidManifest.xml').getroot()
android='{http://schemas.android.com/apk/res/android}'
activity=debug_manifest.find('./application/activity')
if activity is None or activity.get(android+'name') != '.DebugCommandActivity':
    raise SystemExit('missing debug command activity')
if activity.get(android+'exported') != 'true' or activity.get(android+'permission') != 'android.permission.DUMP':
    raise SystemExit('debug command activity must be exported only behind android.permission.DUMP')
print('PASS XML and structure checks')
PYSELFTEST

echo 'PASS repository self-test'
