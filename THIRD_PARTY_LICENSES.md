# Third-party licenses

## Runtime and product source

The Controlled Sandbox runtime and product modules bundle no third-party runtime source library.

The project uses Android platform APIs and separately obtained build tools: Android SDK/NDK, Android Gradle Plugin, Gradle, CMake and a JDK. These remain governed by their respective licenses and are not redistributed in the product source package.

Imported APKs are user-supplied artifacts and are not included in project releases. The generated `verification/sbom.json` records project modules and build-time external dependencies.

## Reference-only source snapshots

The project owner supplied two public upstream source snapshots under `ref/`. They are excluded from all product modules and retained only for architecture, behavior and compatibility comparison. Their original archives, source URLs, archive commit comments, licenses/notices and per-file hashes are preserved.

| Snapshot | Source | License status | Project policy |
|---|---|---|---|
| NewBlackbox | `https://github.com/ALEX5402/NewBlackbox` | Root `LICENSE` declares Apache-2.0 | Read-only reference; retain license and attribution |
| VirtualApp | `https://github.com/asLody/VirtualApp` | Uploaded root archive contains no root `LICENSE`; README contains commercial authorization and use restrictions. HookZz subdirectory has its own license only. | Internal provenance/reference only; no source copying, product linking, packaging or public redistribution without separate rights review |

The presence of a source tree in `ref/` does not grant additional rights and does not change the Controlled Sandbox project license. See `ref/README.md` and `ref/manifests/*.json`.
