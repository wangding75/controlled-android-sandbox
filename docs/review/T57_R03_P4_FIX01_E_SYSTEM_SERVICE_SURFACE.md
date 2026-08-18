# T57-R03-P4-FIX01-E — SystemService explicit API surface

RESULT: `GENERIC_DEFAULT_REMOVED`

## Change

`VirtualSystemServiceInterceptor` no longer answers unknown
query-like methods with `defaultValue(returnType)` for Notification or
JobScheduler.

Unknown methods now throw
`VIRTUAL_NOTIFICATION_SIGNATURE_UNSUPPORTED` /
`VIRTUAL_JOB_SIGNATURE_UNSUPPORTED`.

`areNotificationsEnabled` remains the explicit Boolean.TRUE
PARTIAL query (still not a host-backed check).

## Census (HEAD)

| Service | Disposition |
| --- | --- |
| Clipboard | VIRTUAL_STATE_IMPLEMENTED (closed) |
| Account | VIRTUAL_STATE_IMPLEMENTED; visibility/listeners PARTIAL |
| Alarm | VIRTUAL_STATE_IMPLEMENTED; clock mutation EXPLICIT_DENIED |
| Notification | HOST_DELEGATED_REWRITTEN for notify/cancel/channels/active; unknown query EXPLICIT_UNSUPPORTED |
| JobScheduler | HOST_DELEGATED_REWRITTEN for schedule/cancel/pending; unknown query EXPLICIT_UNSUPPORTED |
| Settings | NOT_APPLICABLE (pass-through, not intercepted) |
| UsageStats | NOT_APPLICABLE (pass-through) |
| Shortcut | NOT_APPLICABLE (pass-through) |
| AppWidget | NOT_APPLICABLE (pass-through) |

Settings/UsageStats/Shortcut/AppWidget remain host pass-through and
must be marked PARTIAL in any API matrix, not implemented.
