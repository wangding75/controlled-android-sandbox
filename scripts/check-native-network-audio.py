#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []
required = [
    'sandbox-native/src/main/cpp/include/controlled_sandbox/native_network.h',
    'sandbox-native/src/main/cpp/include/controlled_sandbox/native_network_interceptors.h',
    'sandbox-native/src/main/cpp/native_network.cpp',
    'sandbox-native/src/main/cpp/native_network_interceptors.cpp',
    'sandbox-native/src/main/cpp/include/controlled_sandbox/native_audio.h',
    'sandbox-native/src/main/cpp/native_audio.cpp',
    'sandbox-native/src/main/java/com/warden/controlledsandbox/nativebridge/NativeNetworkIdentity.java',
    'sandbox-native/src/test/cpp/native_network_self_test.cpp',
    'sandbox-native/src/test/cpp/native_network_interceptors_self_test.cpp',
    'sandbox-native/src/test/cpp/native_audio_self_test.cpp',
]
for relative in required:
    if not (ROOT / relative).is_file(): errors.append(f'missing M4-T17 B2 evidence: {relative}')

policy = (ROOT / 'sandbox-native/src/main/cpp/include/controlled_sandbox/native_policy.h').read_text()
for token in ['CidrV6', 'NativeNetworkIdentity', 'allow_ipv6', 'proxy_host', 'cleartext_permitted']:
    if token not in policy: errors.append(f'native network policy missing: {token}')
network_interceptors = (ROOT / 'sandbox-native/src/main/cpp/native_network_interceptors.cpp').read_text()
for token in ['controlled_socket(', 'controlled_send(', 'controlled_recvmsg(',
              'controlled_accept4(', 'controlled_dup3(', 'checked_recvfrom(']:
    if token not in network_interceptors: errors.append(f'native network interceptor missing: {token}')
interceptors = (ROOT / 'sandbox-native/src/main/cpp/native_interceptors.cpp').read_text()
for token in ['controlled_getnameinfo(', 'controlled_gethostname(', 'controlled_getifaddrs(',
              'controlled_AAudioStream_requestStart(',
              'controlled_AMediaRecorder_start(', 'revoke_native_audio_captures(']:
    if token not in interceptors: errors.append(f'native network/audio interceptor missing: {token}')
runtime = (ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestRuntimeEnvironment.java').read_text()
for token in ['NativeNetworkIdentity.isolated', 'configureAudioCapture',
              'setAudioCaptureAllowed', 'resetAudioCapture']:
    if token not in runtime: errors.append(f'Guest runtime native policy wiring missing: {token}')
framework = (ROOT / 'sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/CapabilityServiceInterceptor.java').read_text()
for token in ['unregisterAudioRecordingCallback', 'stopInput', 'releaseInput']:
    if token not in framework: errors.append(f'Binder audio revocation cleanup missing: {token}')
script = (ROOT / 'scripts/test-native.sh').read_text()
for token in ['native_network_self_test', 'native_network_interceptors_self_test',
              'native_network_interceptors.cpp', 'native_audio_self_test',
              'native_network.cpp', 'native_audio.cpp']:
    if token not in script: errors.append(f'native test wiring missing: {token}')
if errors:
    print('FAIL M4-T17 B2 native network/audio checks', file=sys.stderr)
    for error in errors: print(' - ' + error, file=sys.stderr)
    raise SystemExit(1)
print('PASS M4-T17 B2 native network/audio checks')
