# M5-T19.1-H Guest Binder reconnect correctness

- Finding: P2-02 cached dead Guest Binder caused the current request to fail; only a later request rebound.
- Source fix: PASS.
- Same-request pre-dispatch reconnect: PASS.
- Ten concurrent callers share one replacement bind: PASS.
- Delayed old death callback isolation: PASS.
- Distinct `DEAD_BINDER`, `BINDER_DIED`, `DISCONNECTED`, `BIND_REJECTED`, `BIND_TIMEOUT` diagnostics: PASS.
- Remote operation replay after dispatch: prohibited.
- Android Binder/device evidence: 0.
