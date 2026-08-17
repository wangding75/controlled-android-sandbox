# T57-R03-P3 API matrix

RESULT: PASS with classified remainder (`ANDROID_MATRIX_PARTIAL`)

VA Pro: `NOT_PROVEN`

## Environments

| API | Environment | Dynamic |
| --- | --- | --- |
| 32 | MuMu `RD测试` | EXECUTED (P0–P2 campaigns) |
| 33 | official `google_apis;x86_64` image | ENVIRONMENT_NOT_AVAILABLE — sdkmanager IO exception while downloading manifest |
| 34 | official image | ENVIRONMENT_NOT_AVAILABLE — same install attempt |
| 35 | dedicated AVD `T57_R03_API35_x86_64` booted `emulator-5554` | PARTIAL — fixture APK installed; host APK install rejected |
| 36 | dedicated AVD `T57_R03_API36_x86_64` created, not booted | ENVIRONMENT_NOT_AVAILABLE for dynamic matrix after API35 host-install block |

User AVDs were not modified. Dedicated T57 AVDs were created from already-installed official images.

## API35 host install

```
INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION:
The number of child activity-alias elements exceeded the max allowed in application
```

This is ANDROID_COMPAT on API35 PackageParser, not a fake PASS.
The production stub/alias pool is the frozen 64-slot Activity contract.
Reducing aliases to fit the parser cap would change that contract and
was not done in P3.

Companion32: `INSTALL_FAILED_NO_MATCHING_ABIS` on x86_64 emulator. Expected.

## API32 (executed)

P0/P1/P2 probes already ran on API32: Activity/Service/Provider/PI/isolated
16-slot, package lifecycle, WebView/Quark, soak. See P2 Final.

## Remainder

API33/34 images not installed. API36 not dynamically executed. No
API-specific compatibility adapter was added because the only new
dynamic failure is the Activity alias-count parser cap.
