# T57-R03-P4-FIX01-D — System-holder PendingIntent after guest death

RESULT: `RUNNER_BOUND_TO_STUB_PID`

`tools/capability/run_fix01d_rd.py` arms the existing system-holder
fixture, records whether Notification/Alarm dumps still hold the sender,
then kills the CAS guest stub via FIX01-B `probe_kill` (`kill -9`
`:guestN`). It does not `am force-stop` the physical guest package.

Broker survival is taken from remaining `:sandbox_server` PIDs. Recover
prepare must create a new session. Delivery after death is read from
`files/system-holder-delivered.json` when the fixture writes it.

Remainder: live `:guestN` may already be gone after the debug Activity
teardown, so the kill path can report `KILL_TARGET_PID_MISSING` or
already-dead PID. That is recorded honestly. The physical guest package
is never the kill target.
