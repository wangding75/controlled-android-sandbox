# T57-R03-P1D ClassLoader / LoadedApk / ART

RESULT: PASS with classified ART/WebView gaps

P1-00 closed the Service ClassNotFound host DexPathList defect. P1D records the
systemic loader contract.

## Contract

`GuestDefiningLoader` is the single defining-loader world for Activity, Service,
Receiver, Provider and JobService. `GuestLoadedApkBridge` publishes that
PathClassLoader on ActivityThread LoadedApk (`mClassLoader` /
`mDefaultClassLoader`) together with guest ApplicationInfo and resources.

Application.attach happens after the process loader is installed; the session
does not rebound to the host loader.

## Matrix

`docs/runtime/T57_R03_CLASSLOADER_MATRIX.yaml`

## Split

GuestPackageSpec already carries split paths/FDs. Isolated guests use
InMemoryDexClassLoader. Ordinary guests use PathClassLoader(base + splits).

## WebView

WebView provider lookup/visibility exists as a virtual service contract
(`GuestWebViewProviderServiceBridgeSelfTest`). Real provider package smoke is
RD-conditional.

## ART

RD API32 is the baseline. Hidden API bridge remains installed on API 29+.
No new API32-only field names were added. API33–36 dynamic proof is P3.

VA Pro: NOT_PROVEN
