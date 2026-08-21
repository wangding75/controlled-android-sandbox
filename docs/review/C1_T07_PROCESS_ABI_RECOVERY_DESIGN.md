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

The resumed RD run also closes the API32 window-focus recovery defect observed
after the x86 Guest was killed: Android's InputMethodManager can report an
`unknown client` Binder for the retired Guest. The framework invocation boundary
now fails closed for that exact stale input client, returns the method's normal
default value, and continues to reject all unrelated service failures. The
device runner filters the marker logcat stream, preserves full failure logcat,
and retries only transient ADB-daemon startup errors. The P1-00 slot harness
omits `processName` for the ordinary main process instead of passing an empty
extra.

The final RD evidence is recorded under
`verification/catch-up/C1-T07-rerun-final2`, with 50/50 PASS iterations,
generation 1 to 2, PID replacement, x86/32-bit Companion markers, and
`GUEST_PROCESS_DISCONNECTED`. Supporting ordinary recovery, cross-ABI
clear/delete/reinstall, isolated-service, and P1-00 slot evidence is kept in
the corresponding C1-T07 evidence directories and the P1-00 campaign artifact.
