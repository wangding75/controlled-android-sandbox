# Third-milestone gate

## Release command

```bash
./scripts/package-release.sh <path-to-artifacts/m3-emulator-*>
```

The command first runs all local gates and then validates `m3-gate.json`. It exits non-zero and prints `THIRD_MILESTONE_NOT_COMPLETE` when evidence is absent or incomplete.

## Required device evidence

The current strict gate requires:

- status `PASS`;
- at least two Guest Activity bridge creations, covering virtual users 0 and 1;
- Fixture Activity lifecycle log;
- Fixture JNI probe `JNI_OK`;
- at least two distinct `:guestN` host processes;
- independent `u0` and `u1` instance roots;
- successful Service, Receiver and Provider component-suite results;
- at least one collected runtime-diagnostics JSONL file with content;
- no matching fatal Java exception, native fatal signal or host ANR;
- at least 1,200 seconds elapsed for a formal M3 run.

## Evidence bundle

`run-emulator-m3.ps1` writes:

- `device.json`;
- install outputs;
- per-command start/result JSON;
- `processes.txt`;
- `activities.txt`;
- `services.txt`;
- `meminfo.txt`;
- `instance-directories.txt`;
- `diagnostic-files.txt`;
- `runtime-diagnostics.jsonl.txt`;
- `logcat.txt`;
- `m3-gate.json`.

## Known limitation of this gate

Passing the Fixture gate proves only the listed, versioned Fixture scenario. It does not prove broad third-party App compatibility. API-level and App-version matrices must be added before any percentage claim.
