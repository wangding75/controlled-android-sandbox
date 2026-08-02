# M5-T19.1-U — Supply-chain governance fix

## Finding

The M5-T19 baseline used mutable GitHub Actions major tags, `ubuntu-latest`, no Gradle dependency-verification metadata and historical commit identities with local or invalid email domains.

## Resolution

1. Pin every external Action to a reviewed full commit SHA and record the release mapping in `verification/github-actions-lock.json`.
2. Fix the hosted runner to `ubuntu-24.04`, keep workflow permissions read-only and disable checkout credential persistence.
3. Pin the CI JDK request and disable latest-version lookup.
4. Enable strict Gradle dependency verification with a checked-in keyring, exact checksum entries and disabled key servers.
5. Remove all trusted-artifact bypasses from the imported verification metadata.
6. Reject dynamic/changing dependency versions and dependency conflicts.
7. Add Dependabot proposals for Actions and Gradle while retaining immutable-SHA review enforcement.
8. Normalize frozen historical identities with `.mailmap` and reject future unapproved author/committer identities.
9. Add an executable U source gate and include it in `verify-all.sh`.

## Acceptance

- No workflow uses `@vN`, branches, tags or abbreviated SHAs.
- No workflow uses `ubuntu-latest` or another `*-latest` runner.
- Gradle dependency verification is strict, signatures and metadata are enabled, network key servers are disabled, and trusted-artifact bypasses are absent.
- Wrapper checksum and exact distribution URL remain pinned.
- Reviewed dependency coordinates are exact and provenance-bound.
- New commits use the approved GitHub noreply identity.
- The U gate and all existing source gates pass without changing tracked files.

## Unverified external boundary

A real strict Gradle resolution, Android build and GitHub-hosted workflow execution require the locked JDK/SDK/NDK and networked dependency environment. No such evidence is claimed by this source-only stage.
