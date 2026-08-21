# C1-T07 Process / ABI / Recovery Design

The campaign treats the process slot and generation as the ownership boundary. A
Guest death must advance generation, disconnect the old Binder lease, allocate a
new physical PID, and reject stale work. The x86 Guest is launched through the
32-bit Companion; the Host and Companion release identity and admission checks
remain the existing production gates.

The RD campaign runner resolves MuMu by instance name on every invocation and
delegates each iteration to the existing cross-ABI death/recovery probe. It
requires at least 50 iterations and writes one raw evidence directory and one
structured result per iteration plus an aggregate summary. A failed iteration
stops the campaign and is not relabeled as a harness pass.

Acceptance dimensions: generation advancement, PID replacement, native load,
Binder-death disconnect, cross-ABI routing, stale-generation rejection, and
absence of fatal/ANR markers. Existing static gates cover slot capacity,
Companion identity, timeout recovery, and runtime ownership contracts.
