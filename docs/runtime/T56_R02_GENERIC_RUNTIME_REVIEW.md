# T56-R02 Generic Runtime Review

Living review. Updated as clusters close. Not a PASS receipt.

## Starting checkpoint

See `docs/runtime/T56_R02_STARTING_CHECKPOINT.md`.

- Branch `feature/t56-product-convergence`
- HEAD `bfa436f14044c47c51809ae8d95f3c049b9d0fa5`
- Tree `ea9fd4f107a48941329e6124b698526143160f46`
- Origin matches HEAD
- Dirty tree: 25 modified files + `GuestComponentFactory.java`
- Device: Xiaomi `25019PNF3C` / HyperOS OS3.0 / Android 16 API36 / `192.168.137.186:39531`

## Existing uncommitted changes

See `docs/runtime/T56_R02_CHANGE_INVENTORY.md`. Nothing in the starting dirty tree is accepted as complete.

Session work after the inventory:

- Removed Instagram ReDex probe `X.0IA` / `X.1ro` from `GuestRuntimeEnvironment`.
- Removed GMS/vending metadata special logs.
- Stopped dumping full application metadata to logcat.
- Introduced `PackageVisibilityClass` / `PackageVisibilityPolicy` and wired PackageManager identity queries to it.
- Play Services / Play Store are classified as `SYSTEM_DEPENDENCY_PROJECTED` (Android system role), not by guest app package. Without a sanitized projector they report NameNotFound. Host user apps still throw `HOST_PACKAGE_HIDDEN`. WebView keeps its existing projector.
- NFC self-test updated to `install(Context, identity)`. Harness `NfcAdapter.getDefaultAdapter` added. Static-field bind path is idempotent.
- Provider query self-test now requires class-distinct records + first-owner authority.

`python tools/static_android_compile.py` = PASS after these changes (domain, visibility, NFC, provider query, and the existing suite).

## Generic issues

| Id | Status after this session |
| --- | --- |
| G01/G02 | KEEP. Domain SelfTest PASS. G14 still incomplete. |
| G03 | Split no longer inherits base PackageInfo. Own manifest version + independent signer digest. Self-test versionCode PASS. |
| G04/G05/G06 | Implemented; dump reduced; no dedicated metadata fixture yet. |
| G07/G08 | Dual-index direction kept. Query self-test updated and PASS. Runtime acquire/user isolation tests still open. |
| G09 | Partial. Parent not deleted; dedicated security suite still open. |
| G16 | Empty legacy, empty v2, populated-legacy+empty-v2, both-populated covered. Self-test PASS. |
| G10 | Implemented. No new dedicated audio fixture yet. Existing media self-test PASS. |
| G11/G12 | Method-level classifier in Core. Local event/window success; interrupt/cross-app/secure denied. Policy self-test PASS. |
| G13 | Interceptor path kept. NFC self-test PASS. Device NFC absent so present-NFC conversion unproven on Xiaomi. |
| G14 | Still `launchTargetClass` shortcut. |
| G15 | Typed policy in Core. Self-test PASS. Sanitized projectors not implemented (absent, not Host dump). |
| G17 | Live interceptor kept. Dead `activityManager` branch still present. |
| G18 | Process-singleton factory. Application/Activity/Service/Receiver/Provider all go through it. Self-test PASS. |
| G19 | Formal `GuestLaunchGate`. Broker waits for create/resume/window. UI/debug require `LAUNCH_PASS`. Self-test PASS. |
| G20 | Identity overlay now runs before `instantiateApplication`. `Process.setArgV0` + `DdmHandleAppName` publish Guest process name (`osProcess=com.instagram.android`, `ps` shows Guest as that name). Enabled providers are installed by class (duplicate authority still first-owner). Latest Xiaomi launch still `LAUNCH_FAILED` / `IgSessionManager not initialized`. Host Instagram pid 14683 unchanged. Evidence: `build/t56-r02-g20-argv0/`. Still Generic. |

## Security/Isolation Review

In progress. Package visibility taxonomy exists; sanitized projectors for system packages are not implemented yet (absent, not Host dump). NFC/Accessibility/Provider/Clear Data still open.

## Instagram

Still `GUEST_ACTIVITY_FAILED` / `IgSessionManager not initialized` on the last Xiaomi probe. Not re-run after this session's Core cleanup.

## Remaining app-specific boundary

`X.0IA` / `X.1ro` removed from Core. No Instagram / Xiaomi / HyperOS behavior branches added.
