# RD测试 runtime device resolution

Project scope: `Controlled Android Sandbox / 闪现2` only.

This is the one-time resolution record for the current T54-R02 execution session. The MuMu
instance name is the project-level identity; the ADB serial is connection data resolved for this
session and must be resolved again after a MuMu restart, ADB reconnect, or new test session.

| Field | Resolved value |
| --- | --- |
| MuMu instance name | `RD测试` |
| MuMu instance index | `1` |
| MuMu instance id | `ginstance1400581308116493301` |
| MuMu VM | `MuMuPlayer-12.0-1` |
| MuMu player state | `start_finished` |
| Manager ADB host/port | `127.0.0.1:16416` |
| Runtime status | `device` |
| Resolved ADB serial | `127.0.0.1:16416` |
| Manufacturer | `Redmi` |
| Model | `22041211A` |
| Android release | `12` |
| API level | `32` |
| Android ID | `398eea33120cd887` |
| Resolution timestamp | `2026-08-12T08:01:22.472431+00:00` |

The resolver exact-matched the MuMu instance name `RD测试`, read the current manager ADB port,
then verified:

```text
adb -s 127.0.0.1:16416 get-state
device
```

Current scripts use the rule:

```text
Project: Controlled Android Sandbox / 闪现2
MuMu target instance: RD测试
ADB serial: dynamically resolved for the current session
```

`SX测试` is historical T54-R01 environment evidence only and is not the current execution target.
The concurrent RD instance identity must remain name-based; `127.0.0.1:16416` must not be treated
as a long-term device identity.
