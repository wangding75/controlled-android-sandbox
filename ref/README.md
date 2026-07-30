# Upstream reference sources

This directory preserves immutable source snapshots supplied by the project owner for architecture and behavior comparison only.

## Build isolation

- `ref/upstream/**` is not included by `settings.gradle` and is not a product module.
- Controlled Sandbox production source must not import, compile, link, package, or copy code from these trees.
- Reference code may be read to identify public behavior, component boundaries, state transitions, compatibility cases, and test scenarios.
- Product implementation must remain independently designed and written against Android/AOSP contracts.
- No upstream Gradle wrapper, script, binary, archive, APK, AAR, JAR, static library, or native library in this directory is executed by project verification.

## Preserved snapshots

| Project | Repository | ZIP archive commit | Root license state | Policy |
|---|---|---|---|---|
| NewBlackbox | `https://github.com/ALEX5402/NewBlackbox` | `89b59836c66f173756a4ae258cf379a957649820` | Root `LICENSE` declares Apache-2.0 | Read-only reference; attribution/license retained |
| VirtualApp | `https://github.com/asLody/VirtualApp` | `c84bf5e9f487966b4b8967898075efa76392b808` | Uploaded root archive contains no root `LICENSE`; README contains commercial-authorization and use restrictions | Provenance preservation and internal research only; no product-source reuse or public redistribution without separate rights review |

The commit values above come from the GitHub source ZIP archive comments. The uploaded archives contain no `.git` history.

## Layout

```text
ref/
├── archives/                 # Exact uploaded ZIP files
├── manifests/                # SHA-256, source, commit and per-file tree hashes
└── upstream/                 # Safely extracted, unmodified source trees
```

Run the integrity gate with:

```bash
python3 tools/reference_sources.py verify
```

Development snapshots and complete internal backups include this directory. `scripts/package-release.sh` excludes it from product source releases. Product runtime artifacts must not include it. Any public source release containing `ref/VirtualApp` requires a separate legal and licensing review.
