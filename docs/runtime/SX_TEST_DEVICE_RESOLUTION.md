# SX测试 runtime device resolution

Project scope: `Controlled Android Sandbox / 闪现2` only.

This record is a point-in-time runtime resolution. The MuMu instance name is the project-level
identity; the ADB serial is connection information resolved again before a subsequent MuMu
operation.

| Field | Resolved value |
| --- | --- |
| MuMu instance name | `SX测试` |
| MuMu instance index | `0` |
| MuMu instance id | `ginstance1400581187375821600` |
| MuMu VM | `MuMuPlayer-12.0-0` |
| Runtime status | `device` |
| Resolved ADB serial | `127.0.0.1:16384` |
| Manufacturer | `Samsung` |
| Model | `SM-A5260` |
| Android release | `12` |
| API level | `32` |
| Android ID | `6af8fde7af55c9b2` |
| Resolution timestamp | `2026-08-12T07:18:06.474124+00:00` |

The resolver selected the exact `playerName` from MuMu's current instance list and read the
current ADB forwarding information from that instance's VM configuration. It then ran:

```text
adb connect <resolved serial>
adb -s <resolved serial> get-state
```

The second command returned `device`. Device properties were read again through the same
resolved serial. The raw resolver output, including the selected config paths and the concurrent
`adb devices -l` snapshot, is retained with the T54-R01 runtime evidence.

For the post-restart execution session on 2026-08-12, the resolved serial was then held fixed
as the operator-requested session parameter `127.0.0.1:16384`; this does not make the serial a
long-term instance identity. The MuMu manager reported `adb_port=16384`, `player_state=start_finished`,
and `adb -s 127.0.0.1:16384 get-state` returned `device`. The concurrent `127.0.0.1:16416`
entry was `RD测试`/out of scope.

`RD测试` is a separate MuMu instance (index `1`) and is out of scope. AVD/API35/API36 evidence,
if used, must remain separately identified and cannot substitute for Xiaomi HyperOS evidence.

## Rule used by current scripts

```text
Project: Controlled Android Sandbox / 闪现2
MuMu target instance: SX测试
ADB serial: dynamically resolved at runtime
```

The current Xiaomi HyperOS / Android 16 / API36 real-device identity remains `TBD`, with status
`REAL_DEVICE_VERIFICATION_PENDING`.
