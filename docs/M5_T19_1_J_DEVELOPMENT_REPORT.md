# M5-T19.1-J recent Review remediation

- P1 test-ownership dead-code bypass: fixed with `main` reachability plus successful static-runner receipt.
- Guest Binder publication before `linkToDeath`: fixed with link/recheck/publish ordering.
- Storage Registry multi-instance lost updates: fixed with JVM and OS file-lock transactions and reload/merge.
- Legacy list omission: unique legacy artifacts are indexed and migrated; ambiguous entries fail explicitly.
- Legacy first-access-wins: removed; ambiguous ownership fails from the first access.
- Reversible-name Registry growth: removed; only hash and migration metadata persist.
- Hash claim cleanup after deletion: implemented.
- Generated verification drift: live reports moved to `build/verification`; verify-all compares tracked diff before/after.
- Android Binder, multi-process and device evidence: 0.
