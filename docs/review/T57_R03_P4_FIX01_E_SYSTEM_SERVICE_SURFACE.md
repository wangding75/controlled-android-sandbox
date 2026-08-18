# T57-R03-P4-FIX01-E — SystemService explicit API surface

RESULT: `EXPLICIT_SURFACE_MATRIX`

Dispositions:

- `VIRTUAL_STATE_IMPLEMENTED`
- `HOST_DELEGATED_REWRITTEN`
- `EXPLICIT_DENIED`
- `EXPLICIT_UNSUPPORTED`
- `NOT_APPLICABLE`

Unknown query-like methods throw `VIRTUAL_*_SIGNATURE_UNSUPPORTED`.
They do not return `defaultValue(returnType)`.

## Matrix (API 32 / API 35 same interceptor)

### Alarm (`VirtualSystemServiceInterceptor.alarm`)

| Method family | Disposition |
| --- | --- |
| set / setExact / setAndAllowWhileIdle / setRepeating / schedule (PendingIntent) | HOST_DELEGATED_REWRITTEN |
| set / setExact (OnAlarmListener) | VIRTUAL_STATE_IMPLEMENTED |
| cancel / remove (PendingIntent) | HOST_DELEGATED_REWRITTEN |
| cancel / remove (listener) | VIRTUAL_STATE_IMPLEMENTED |
| canScheduleExactAlarms | VIRTUAL_STATE_IMPLEMENTED (permission policy) |
| getNextAlarmClock | VIRTUAL_STATE_IMPLEMENTED |
| setTime / setTimeZone | EXPLICIT_DENIED |
| any other | EXPLICIT_UNSUPPORTED |

### Notification

| Method family | Disposition |
| --- | --- |
| notify / enqueue | HOST_DELEGATED_REWRITTEN |
| cancel / cancelAll | HOST_DELEGATED_REWRITTEN |
| create/delete/get channels and groups | HOST_DELEGATED_REWRITTEN |
| getActiveNotifications / getAppActiveNotifications | HOST_DELEGATED_REWRITTEN |
| areNotificationsEnabled | HOST_DELEGATED_REWRITTEN |
| any other | EXPLICIT_UNSUPPORTED |

### JobScheduler

| Method family | Disposition |
| --- | --- |
| schedule / enqueue | HOST_DELEGATED_REWRITTEN |
| cancel / cancelAll | HOST_DELEGATED_REWRITTEN |
| getPendingJob | HOST_DELEGATED_REWRITTEN |
| getAllPendingJobs | HOST_DELEGATED_REWRITTEN |
| any other | EXPLICIT_UNSUPPORTED |

### Account

| Method family | Disposition |
| --- | --- |
| getAccounts / getAccountsByType | VIRTUAL_STATE_IMPLEMENTED |
| add/remove/password/token | VIRTUAL_STATE_IMPLEMENTED |
| getAccountVisibility / setAccountVisibility | VIRTUAL_STATE_IMPLEMENTED |
| register/unregisterAccountListener | VIRTUAL_STATE_IMPLEMENTED |
| any other | EXPLICIT_UNSUPPORTED |

### Clipboard

| Method family | Disposition |
| --- | --- |
| set/get/has/clear primary clip | VIRTUAL_STATE_IMPLEMENTED |
| add/remove primary-clip listener | VIRTUAL_STATE_IMPLEMENTED |
| any other | EXPLICIT_UNSUPPORTED |

### Settings (content resolver / settings authority)

Intercepted by `ApplicationEnvironmentInvocationInterceptor` (`content`).
Identity rewrite + virtual settings profile. Not a silent host leak.

### UsageStats

`ApplicationEnvironmentInvocationInterceptor.usageStats`:
query methods VIRTUAL_STATE / policy; force/whitelist EXPLICIT_DENIED or
UNSUPPORTED per method.

### Shortcut

`ApplicationEnvironmentInvocationInterceptor.shortcut`:
dynamic/manifest/pinned query and mutate VIRTUAL_STATE_IMPLEMENTED.
Unknown EXPLICIT_UNSUPPORTED.

### AppWidget

`ApplicationEnvironmentInvocationInterceptor.appWidget`:
id allocate/bind/update VIRTUAL_STATE_IMPLEMENTED.
Unknown EXPLICIT_UNSUPPORTED.

## Special audit

- Account visibility is stored per account; listeners are registered in
  virtual state. They are not host AccountManager listeners.
- `areNotificationsEnabled` is host-delegated, not a fake `true`.
- Job pending/state queries rewrite guest job ids to host ids.

## Regression

Alarm PendingIntent delegation is required by FIX01-D. Listener alarms
remain virtual so the existing in-process self-test still holds.
