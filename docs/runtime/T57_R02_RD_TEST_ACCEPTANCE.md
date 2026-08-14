# T57-R02 RD Test Acceptance

## Gate

The only dynamic gate is the simulator instance named `RD测试`, API 32. Every script dynamically enumerates `adb devices -l`, reads model/device/API/Android/boot ID, and rejects ambiguous or non-matching candidates. No historical ADB serial is embedded.

Scripts:

- `tools/device/t57_rd_activity_real_path.ps1`
- `tools/device/t57_rd_pending_intent_real_path.ps1`
- `tools/device/t57_rd_service_real_path.ps1`
- `tools/device/t57_rd_provider_real_path.ps1`
- `tools/device/t57_rd_clear_lifecycle.ps1`
- `tools/device/t57_rd_full_regression.ps1`

## Current run

`RESULT: BLOCKED` for RD acceptance. The connected API32 candidate reported model `22041211A`, device `rubens`, and a valid boot ID, but its properties did not identify the instance as `RD测试`; the resolver correctly rejected it. The offline candidate was rejected before probing.

Therefore RD-01 through RD-08 and the 20-minute stability run remain `DEVICE_REGRESSION_PENDING`. This document does not claim API33–36 coverage.

## Acceptance record required for a future run

Record `instanceName`, dynamically resolved `serial`, model, device, API, Android release, boot ID, APK SHA256, sandbox package/version, Git HEAD/tree, case result, diagnostics JSONL, Java/native crash artifacts and ANR traces. A case is accepted only when the framework-side evidence and the Guest-side evidence share the correlation fields described in `T57_R02_OBSERVABILITY_ARCHITECTURE.md`.
