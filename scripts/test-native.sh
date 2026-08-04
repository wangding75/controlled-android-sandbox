#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
RECEIPT="$ROOT/build/verification/native-host-test-execution.json"
STAGE_TIMEOUT_SECONDS=${NATIVE_STAGE_TIMEOUT_SECONDS:-600}
COMPILE_TIMEOUT_SECONDS=${NATIVE_COMPILE_TIMEOUT_SECONDS:-120}

if [[ ${NATIVE_SELF_TEST_WATCHDOG_ACTIVE:-0} != 1 ]]; then
  mkdir -p "$(dirname "$RECEIPT")"
  STARTED=$(date +%s)
  set +e
  NATIVE_SELF_TEST_WATCHDOG_ACTIVE=1 timeout --signal=TERM --kill-after=10s \
    "${STAGE_TIMEOUT_SECONDS}s" bash "$0" "$@"
  CODE=$?
  set -e
  FINISHED=$(date +%s)
  STATUS=FAIL
  [[ $CODE -eq 0 ]] && STATUS=PASS
  [[ $CODE -eq 124 || $CODE -eq 137 ]] && STATUS=TIMEOUT
  cat > "$RECEIPT" <<JSON
{
  "status": "$STATUS",
  "exitCode": $CODE,
  "stageTimeoutSeconds": $STAGE_TIMEOUT_SECONDS,
  "compileTimeoutSeconds": $COMPILE_TIMEOUT_SECONDS,
  "elapsedSeconds": $((FINISHED - STARTED))
}
JSON
  if [[ $CODE -eq 124 || $CODE -eq 137 ]]; then
    echo "FAIL native Host test stage exceeded ${STAGE_TIMEOUT_SECONDS}s" >&2
  fi
  exit "$CODE"
fi

OUT=$(mktemp -d "${TMPDIR:-/tmp}/controlled-sandbox-native-self-test.XXXXXX")
trap 'rm -rf "$OUT"' EXIT

GXX_PATH=$(command -v g++)
g++() {
  local source='unknown'
  local argument
  for argument in "$@"; do
    [[ $argument == *.cpp ]] && source=$(basename "$argument")
  done
  echo "START native compile: $source"
  timeout --signal=TERM --kill-after=10s "${COMPILE_TIMEOUT_SECONDS}s" "$GXX_PATH" "$@"
  echo "PASS native compile: $source"
}

rm -rf "$OUT"
mkdir -p "$OUT"
g++ -std=c++20 -Wall -Wextra -Werror -pthread \
  -I"$ROOT/sandbox-native/src/main/cpp/include" \
  "$ROOT/sandbox-native/src/main/cpp/native_policy.cpp" \
  "$ROOT/sandbox-native/src/test/cpp/native_policy_self_test.cpp" \
  -o "$OUT/native_policy_self_test"
g++ -std=c++20 -Wall -Wextra -Werror -pthread \
  -I"$ROOT/sandbox-native/src/main/cpp/include" \
  "$ROOT/sandbox-native/src/main/cpp/native_policy.cpp" \
  "$ROOT/sandbox-native/src/main/cpp/native_file_system.cpp" \
  "$ROOT/sandbox-native/src/main/cpp/native_procfs.cpp" \
  "$ROOT/sandbox-native/src/test/cpp/native_file_system_self_test.cpp" \
  -o "$OUT/native_file_system_self_test"
"$OUT/native_file_system_self_test"
"$OUT/native_policy_self_test"
g++ -std=c++20 -Wall -Wextra -Werror -pthread \
  -I"$ROOT/sandbox-native/src/main/cpp/include" \
  "$ROOT/sandbox-native/src/main/cpp/native_policy.cpp" \
  "$ROOT/sandbox-native/src/main/cpp/native_file_system.cpp" \
  "$ROOT/sandbox-native/src/main/cpp/native_procfs.cpp" \
  "$ROOT/sandbox-native/src/test/cpp/native_syscall_boundary_self_test.cpp" \
  -o "$OUT/native_syscall_boundary_self_test"
"$OUT/native_syscall_boundary_self_test"
g++ -std=c++20 -Wall -Wextra -Werror -pthread \
  -I"$ROOT/sandbox-native/src/main/cpp/include" \
  "$ROOT/sandbox-native/src/main/cpp/native_policy.cpp" \
  "$ROOT/sandbox-native/src/main/cpp/native_procfs.cpp" \
  "$ROOT/sandbox-native/src/test/cpp/native_procfs_self_test.cpp" \
  -o "$OUT/native_procfs_self_test"
g++ -std=c++20 -Wall -Wextra -Werror -pthread \
  -I"$ROOT/sandbox-native/src/main/cpp/include" \
  "$ROOT/sandbox-native/src/main/cpp/native_policy.cpp" \
  "$ROOT/sandbox-native/src/main/cpp/native_network.cpp" \
  "$ROOT/sandbox-native/src/test/cpp/native_network_self_test.cpp" \
  -o "$OUT/native_network_self_test"
"$OUT/native_network_self_test"
g++ -std=c++20 -Wall -Wextra -Werror -pthread \
  -I"$ROOT/sandbox-native/src/main/cpp/include" \
  "$ROOT/sandbox-native/src/main/cpp/native_policy.cpp" \
  "$ROOT/sandbox-native/src/main/cpp/native_file_system.cpp" \
  "$ROOT/sandbox-native/src/main/cpp/native_procfs.cpp" \
  "$ROOT/sandbox-native/src/main/cpp/native_loader.cpp" \
  "$ROOT/sandbox-native/src/main/cpp/native_network.cpp" \
  "$ROOT/sandbox-native/src/main/cpp/native_network_interceptors.cpp" \
  "$ROOT/sandbox-native/src/main/cpp/native_audio.cpp" \
  "$ROOT/sandbox-native/src/main/cpp/native_interceptors.cpp" \
  "$ROOT/sandbox-native/src/main/cpp/native_hook.cpp" \
  "$ROOT/sandbox-native/src/test/cpp/native_network_interceptors_self_test.cpp" \
  -ldl -o "$OUT/native_network_interceptors_self_test"
"$OUT/native_network_interceptors_self_test"
g++ -std=c++20 -Wall -Wextra -Werror -pthread \
  -I"$ROOT/sandbox-native/src/main/cpp/include" \
  "$ROOT/sandbox-native/src/main/cpp/native_audio.cpp" \
  "$ROOT/sandbox-native/src/test/cpp/native_audio_self_test.cpp" \
  -o "$OUT/native_audio_self_test"
"$OUT/native_audio_self_test"
"$OUT/native_procfs_self_test"
g++ -std=c++20 -Wall -Wextra -Werror -pthread \
  -I"$ROOT/sandbox-native/src/main/cpp/include" \
  "$ROOT/sandbox-native/src/main/cpp/native_policy.cpp" \
  "$ROOT/sandbox-native/src/main/cpp/native_file_system.cpp" \
  "$ROOT/sandbox-native/src/main/cpp/native_procfs.cpp" \
  "$ROOT/sandbox-native/src/main/cpp/native_loader.cpp" \
  "$ROOT/sandbox-native/src/test/cpp/native_loader_self_test.cpp" \
  -o "$OUT/native_loader_self_test"
"$OUT/native_loader_self_test"
mkdir -p "$OUT/guest"
g++ -std=c++20 -Wall -Wextra -Werror -fPIC -shared \
  "$ROOT/sandbox-native/src/test/cpp/native_loader_child_fixture.cpp" \
  -o "$OUT/guest/libnative_loader_child.so"
g++ -std=c++20 -Wall -Wextra -Werror -fPIC -shared \
  "$ROOT/sandbox-native/src/test/cpp/native_file_hook_fixture.cpp" \
  -o "$OUT/guest/libnative_file_hook_fixture.so"
g++ -std=c++20 -Wall -Wextra -Werror -pthread \
  -I"$ROOT/sandbox-native/src/main/cpp/include" \
  "$ROOT/sandbox-native/src/main/cpp/native_policy.cpp" \
  "$ROOT/sandbox-native/src/main/cpp/native_file_system.cpp" \
  "$ROOT/sandbox-native/src/main/cpp/native_procfs.cpp" \
  "$ROOT/sandbox-native/src/main/cpp/native_loader.cpp" \
  "$ROOT/sandbox-native/src/main/cpp/native_network.cpp" \
  "$ROOT/sandbox-native/src/main/cpp/native_network_interceptors.cpp" \
  "$ROOT/sandbox-native/src/main/cpp/native_audio.cpp" \
  "$ROOT/sandbox-native/src/main/cpp/native_interceptors.cpp" \
  "$ROOT/sandbox-native/src/main/cpp/native_hook.cpp" \
  "$ROOT/sandbox-native/src/test/cpp/native_hook_self_test.cpp" \
  -ldl -o "$OUT/native_hook_self_test"
"$OUT/native_hook_self_test" "$OUT/guest/libnative_file_hook_fixture.so"
g++ -std=c++20 -Wall -Wextra -Werror -pthread \
  -I"$ROOT/sandbox-native/src/main/cpp/include" \
  "$ROOT/sandbox-native/src/main/cpp/native_crash.cpp" \
  "$ROOT/sandbox-native/src/test/cpp/native_crash_self_test.cpp" \
  -o "$OUT/native_crash_self_test"
"$OUT/native_crash_self_test"
JAVA_HOME_REAL=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
g++ -std=c++20 -Wall -Wextra -Werror \
  -I"$ROOT/sandbox-native/src/main/cpp/include" \
  -I"$JAVA_HOME_REAL/include" -I"$JAVA_HOME_REAL/include/linux" \
  -c "$ROOT/sandbox-native/src/main/cpp/native_policy_jni.cpp" \
  -o "$OUT/native_policy_jni.o"
g++ -std=c++20 -Wall -Wextra -Werror -pthread \
  -I"$ROOT/sandbox-native/src/main/cpp/include" \
  -c "$ROOT/sandbox-native/src/main/cpp/native_file_system.cpp" \
  -o "$OUT/native_file_system.o"
g++ -std=c++20 -Wall -Wextra -Werror -pthread \
  -I"$ROOT/sandbox-native/src/main/cpp/include" \
  -c "$ROOT/sandbox-native/src/main/cpp/native_procfs.cpp" \
  -o "$OUT/native_procfs.o"
g++ -std=c++20 -Wall -Wextra -Werror -pthread \
  -I"$ROOT/sandbox-native/src/main/cpp/include" \
  -c "$ROOT/sandbox-native/src/main/cpp/native_loader.cpp" \
  -o "$OUT/native_loader.o"
g++ -std=c++20 -Wall -Wextra -Werror -pthread \
  -I"$ROOT/sandbox-native/src/main/cpp/include" \
  -c "$ROOT/sandbox-native/src/main/cpp/native_network.cpp" \
  -o "$OUT/native_network.o"
g++ -std=c++20 -Wall -Wextra -Werror -pthread \
  -I"$ROOT/sandbox-native/src/main/cpp/include" \
  -c "$ROOT/sandbox-native/src/main/cpp/native_network_interceptors.cpp" \
  -o "$OUT/native_network_interceptors.o"
g++ -std=c++20 -Wall -Wextra -Werror -pthread \
  -I"$ROOT/sandbox-native/src/main/cpp/include" \
  -c "$ROOT/sandbox-native/src/main/cpp/native_audio.cpp" \
  -o "$OUT/native_audio.o"
g++ -std=c++20 -Wall -Wextra -Werror -pthread \
  -I"$ROOT/sandbox-native/src/main/cpp/include" \
  -c "$ROOT/sandbox-native/src/main/cpp/native_interceptors.cpp" \
  -o "$OUT/native_interceptors.o"
g++ -std=c++20 -Wall -Wextra -Werror -pthread \
  -I"$ROOT/sandbox-native/src/main/cpp/include" \
  -c "$ROOT/sandbox-native/src/main/cpp/native_hook.cpp" \
  -o "$OUT/native_hook.o"
g++ -std=c++20 -Wall -Wextra -Werror -pthread \
  -I"$ROOT/sandbox-native/src/main/cpp/include" \
  -c "$ROOT/sandbox-native/src/main/cpp/native_crash.cpp" \
  -o "$OUT/native_crash.o"
g++ -std=c++20 -Wall -Wextra -Werror \
  -I"$JAVA_HOME_REAL/include" -I"$JAVA_HOME_REAL/include/linux" \
  -c "$ROOT/sandbox-companion32/src/main/cpp/native_companion_jni.cpp" \
  -o "$OUT/native_companion_jni.o"
echo 'PASS 32-bit companion JNI source boundary compile'
echo 'PASS sandbox-native JNI boundary compile'
