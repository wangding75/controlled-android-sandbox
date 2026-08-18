# T57-R03-P4-FIX01-I — API35 / API36 revalidation

RESULT: `API35_PREPARE_PASS_LAUNCH_GATE_FAILED`

Evidence: `artifacts/capability-audit/fix01i/20260818T045959Z`

## API35 (`emulator-5554`, `sdk_gphone64_x86_64`)

| Fixture | import-prepare | launch |
| --- | --- | --- |
| `com.warden.controlledsandbox.fixture` | PASS | FAIL `LAUNCH_GATE_FAILED` |
| `com.warden.controlledsandbox.fixture.scale` | PASS | FAIL `LAUNCH_GATE_FAILED` |

Host install of the stub-refactored APK already succeeded earlier in FIX01-A.
Prepare/import of the 128-Activity scale fixture succeeds. The launch gate
does not see create/resume/window on this SwiftShader AVD.

This is not treated as a Host PackageParser / stub-cardinality failure.
API35 Host install remains PASS.

## API36

The `system-images;android-36;google_apis;x86_64` image is present locally.
A `T57_R03_API36_x86_64` AVD is created if the manager accepts it; smoke is
recorded when that emulator reaches `adb` `device`.

## API32

RD测试 remains the API32 authority and is not re-scored here.
