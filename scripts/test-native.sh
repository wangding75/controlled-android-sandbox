#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT="$ROOT/build/native-self-test"
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
