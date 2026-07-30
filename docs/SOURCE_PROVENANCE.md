# Source provenance

## Controlled Sandbox production source

All production Java, AIDL, XML, C/C++, Gradle and script files in the Controlled Sandbox product modules were independently created for this clean-room project.

## Reference-only upstream snapshots

Immutable source snapshots from VirtualApp and NewBlackbox are preserved under `ref/` at the project owner's request. They are not product modules, are not compiled or linked, and are excluded from product source releases. Their exact uploaded archives, source URLs, GitHub ZIP comment commits, license state and per-file hashes are recorded under `ref/manifests/`.

- NewBlackbox snapshot: root `LICENSE` declares Apache-2.0.
- VirtualApp snapshot: uploaded root archive contains no root license and its README contains commercial authorization/use restrictions. It is retained for internal provenance and behavior research only.

No code from these upstream trees may be copied, translated, mechanically rewritten or submitted into Controlled Sandbox product modules. See `docs/CLEAN_ROOM_POLICY.md`, `docs/VA_NBB_REFERENCE_BASELINE.md` and `ref/README.md`.

## Build-time tools

- Android Gradle Plugin 8.11.1, downloaded from Google's Maven repository during a normal online build.
- Gradle 8.13, downloaded from the URL in `gradle-wrapper.properties`.
- Android SDK Platform 36 and JDK 17.

These tools are not redistributed in the product source package. The included Gradle bootstrap JAR is compiled from original project source under `tools/wrapper-src`.

## Runtime libraries

Controlled Sandbox product modules have no third-party runtime source-library dependency. They use Android platform APIs and project-owned modules only.
