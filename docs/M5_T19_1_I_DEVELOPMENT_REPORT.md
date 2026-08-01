# M5-T19.1-I Guest storage-name collision correctness

- Finding: P2-03 lossy `safeName()` mapping silently merged different logical storage names.
- Reversible UTF-8 Base64URL mapping for bounded names: PASS.
- Bounded SHA-256 form plus persistent owner claim for long names: PASS.
- Cross-instance transaction uses JVM lock, OS file lock, reload/merge and unique temporary file: PASS.
- Reversible names do not create permanent registry claims: PASS.
- Unique legacy file and database enumeration migration: PASS.
- Legacy collision fail-closed: ambiguous legacy access fails immediately with `LEGACY_NAME_COLLISION_AMBIGUOUS`: PASS.
- Ambiguous legacy enumeration fails with `LEGACY_NAME_INDEX_AMBIGUOUS`: PASS.
- Deleted hashed artifacts release stale claims: PASS.
- SharedPreferences, databases, files, getDir and external types use one codec: PASS.
- Android multi-process/filesystem/device evidence: 0.
