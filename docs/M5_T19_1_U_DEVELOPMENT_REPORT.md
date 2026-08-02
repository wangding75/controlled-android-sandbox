# M5-T19.1-U Supply-chain and CI governance

## Scope

This stage closes the remaining P3 global-review finding without changing the frozen 113-capability matrix.

## Implemented controls

### GitHub Actions

- The source-gate runner is fixed to `ubuntu-24.04`; floating `ubuntu-latest` is forbidden.
- Every external Action reference uses a reviewed full 40-character commit SHA:
  - `actions/checkout` v7.0.0 — `9c091bb21b7c1c1d1991bb908d89e4e9dddfe3e0`
  - `actions/setup-java` v5.6.0 — `03ad4de0992f5dab5e18fcb136590ce7c4a0ac95`
  - `actions/upload-artifact` v7.0.1 — `043fb46d1a93c77aae656e7c1c64a875d1fc6a0a`
- The reviewed mappings are frozen in `verification/github-actions-lock.json`.
- Checkout credentials are not persisted and the workflow token remains `contents: read`.
- The CI JDK is fixed to Eclipse Temurin `17.0.19+10` with `check-latest: false`.
- Dependabot is configured for monthly GitHub Actions and Gradle update proposals. Updates remain review-gated; mutable tags are never accepted directly.

### Gradle dependency integrity

- The Gradle wrapper remains pinned to Gradle 8.13 with `distributionSha256Sum` and URL validation.
- CI invokes `./gradlew --dependency-verification=strict` before the source gates.
- `gradle/verification-metadata.xml` enables metadata and PGP signature verification.
- Network key-server lookup is disabled and the reviewed armored keyring is checked in.
- All imported `trusted-artifacts` bypasses were removed. A resolved artifact must match an approved signing key or an exact SHA-256 entry.
- Exact SHA-256 values for the AGP 8.11.1 root JAR and POM are included.
- The reviewed dependency coordinate set is frozen in `gradle/reviewed-dependency-coordinates.json`; dynamic, changing and snapshot versions are rejected.
- `dependencyLocking.lockAllConfigurations()`, conflict rejection and exact-version enforcement are enabled. Lock state still needs to be materialized and validated by a real Gradle resolution in the locked Android environment.
- The imported metadata/keyring provenance, transformations and final local SHA-256 values are recorded in `gradle/dependency-verification-provenance.json`.

### Commit identity

- New M5-T19.1 commits must use `OpenAI <openai@users.noreply.github.com>` for author and committer identities.
- Historical invalid/local aliases are normalized through `.mailmap`; frozen Git objects are not rewritten.
- `verification/approved-commit-identities.txt` and the U gate reject unapproved canonical identities and unapproved raw identities after the S/T baseline.

### Gate behavior

`scripts/check-m5-t19-1-u-supply-chain-governance.py` validates the live workflow, lock files, verification metadata, coordinate manifest, Gradle configuration, Dependabot configuration and Git identities. It also runs negative fixtures for mutable Action tags, floating runners, persisted credentials and lenient Gradle verification.

The U gate is part of `scripts/verify-all.sh` and writes generated evidence only to `build/verification`.

## Evidence boundary

The current execution container does not have the Gradle 8.13 distribution, Android SDK/NDK or a resolved AGP cache, and it cannot reach external artifact repositories. Consequently:

- source-policy and metadata validation are executable here;
- a strict Gradle dependency-resolution run is configured in CI but was not executed here;
- Android Gradle, generated AIDL, APK, Emulator and physical-device evidence remain unavailable;
- the checked-in verification metadata may require reviewed additions when the first real strict build resolves project-specific artifacts.
