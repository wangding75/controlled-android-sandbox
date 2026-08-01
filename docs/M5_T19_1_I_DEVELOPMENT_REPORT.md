# M5-T19.1-I Guest storage-name collision correctness

- Finding: P2-03 lossy `safeName()` mapping silently merged different logical storage names.
- Source fix: PASS.
- Shared codec used by SharedPreferences, databases, files, getDir and external types: PASS.
- Reversible UTF-8 Base64URL mapping for normal names: PASS.
- Bounded SHA-256 form plus persistent owner claim for long names: PASS.
- CRC-protected registry and restart stability: PASS.
- Legacy file, preferences, database, directory and external-type migration: PASS.
- Legacy collision fail-closed error `LEGACY_NAME_COLLISION_AMBIGUOUS`: PASS.
- Spaces, question mark, slash, Unicode, emoji, dot names and long names: PASS.
- Android filesystem/device evidence: 0.
