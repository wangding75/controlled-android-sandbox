# T57-R03-P4-FIX01-I — API35 / API36 revalidation

RESULT: `API35_HOST_INSTALL_PASS`

## API35 `T57_R03_API35_x86_64` (`emulator-5554`)

- Host `app-debug.apk` streamed install **Success**.
- This is the P3 PackageParser failure
  (`child activity-alias elements exceeded the max allowed`).
- Scale fixture APK install Success.
- `import-prepare` of the 128-Activity fixture PASS.
- Guest create/resume/window on this SwiftShader AVD:
  `LAUNCH_GATE_FAILED` / later timeouts. Recorded as remainder, not
  forged PASS.

## API36

AVD `T57_R03_API36_x86_64` exists. Not booted in this FIX01 session
after the API35 host-install proof. Dynamic smoke:
`ENVIRONMENT_NOT_AVAILABLE` for this run.

## API33 / API34

Official images still `ENVIRONMENT_NOT_AVAILABLE` (P3).

## ARM / OEM

No new ARM/OEM environment. Stay UNVERIFIED / ENVIRONMENT_NOT_AVAILABLE.
