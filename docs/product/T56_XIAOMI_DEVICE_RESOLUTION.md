# T56-R01 Xiaomi device resolution

`REAL_XIAOMI_DEVICE`

The T56-R01 session was locked to `192.168.137.186:39531` after a fresh `adb devices -l` check. The endpoint is an ADB-over-TCP connection supplied for the Xiaomi handset; it is not the excluded `127.0.0.1:*` endpoint.

| Field | Value |
| --- | --- |
| Manufacturer / brand | Xiaomi / Xiaomi |
| Model / device | `25019PNF3C` / `xuanyuan` |
| Android / API | Android 16 / API 36 |
| HyperOS | `OS3.0`, code `3`, MIUI UI `V816` |
| ABI | `arm64-v8a` |
| Fingerprint | `Xiaomi/xuanyuan/xuanyuan:16/BP2A.250605.031.A3/OS3.0.306.0.WOACNXM:user/release-keys` |
| Screen / density | `1080x2400` / `450` |
| Boot ID | `f1d7a7f5-fbc6-4ad2-a907-ee9ef91e1e8b` |
| Android ID | `86b54040bbf6ebda` |
| Hardware / emulator marker | `qcom` / `ro.boot.qemu` empty |
| Capture time | `2026-08-14T10:29:11.8893193+08:00` |

Raw device evidence: `build/t56-r01-xiaomi/device/device-resolution.txt`.

The initial `127.0.0.1:16416` device was not used for this session. It was excluded before the Xiaomi API36 session was locked.
