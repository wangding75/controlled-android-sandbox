# B3-T5A frozen development baseline

## Baseline identity

- Baseline name: `B3-T5A uploaded source baseline`
- Upstream-declared source revision: `4bb36b4e279759bba06a2beb0d79946a6d757c12`
- Uploaded archive SHA-256: `b48dc213732ef980fa2241d6d5a6f6933e32a2b0f54e25ea9fae829a09812e32`
- Frozen Git branch: `baseline/b3-t5a-upload`
- Frozen Git tag: `baseline-b3-t5a-upload`
- Frozen baseline commit: `7edf76364cf464cc43f91aaec5b0dee4d38abea3`
- Imported file count: `387`
- Active development branch: `feature/m4-reproducible-build`

An independent path-and-SHA-256 comparison confirmed that all 387 files in the uploaded ZIP exactly match the frozen baseline tag. The baseline branch and tag contain the uploaded source without development changes. All subsequent work starts from a separate feature branch. Replacing or rewriting the baseline tag is prohibited.

## Baseline status

This is a source-development baseline, not a production or device-validated release baseline.

| Dimension | Baseline status |
|---|---|
| Source structure | Present |
| Host-side verification | Passed before import |
| Android Gradle build in this environment | Not verified |
| Emulator validation | Deferred by project decision |
| Physical-device validation | Deferred by project decision |
| Third-party application compatibility | Not established |
| Untrusted APK security boundary | Not established |

## Toolchain lock

`build-environment.lock.json` is the machine-readable authority for the build toolchain. The first reproducible-build iteration freezes:

- Java language level 17
- Gradle 8.13 and its official binary-distribution SHA-256
- Android Gradle Plugin 8.11.1
- compile SDK 36, target SDK 35 and minimum SDK 26
- Android Build Tools 35.0.0
- Android NDK 27.2.12479018
- CMake 3.22.1
- ARM64 and x86_64 as the explicitly supported baseline ABIs

Changing any locked value requires a dedicated commit with a compatibility reason and updated verification evidence.

## Evidence rules

A capability must be reported separately as one of:

1. Source exists.
2. Production path is connected.
3. Host-side automated tests pass.
4. Android build passes.
5. Emulator validation passes.
6. Physical-device validation passes.

No lower evidence level may be presented as proof of a higher level. VA and NBB comparisons must use the same evidence dimensions.

## Delivery formats

The Git bundle is the canonical delivery because it preserves the frozen branch and tag. The deterministic source ZIP is provided for inspection and IDE import; host verification in ZIP-only mode checks the recorded baseline evidence but cannot re-read the immutable Git tag. Maintainer packaging and the two-build Android reproducibility workflow should run from a clone restored from the Git bundle.
