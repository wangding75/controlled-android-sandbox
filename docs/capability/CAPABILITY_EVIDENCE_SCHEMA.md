# Capability Campaign Evidence Schema

Machine-checked schema: `tools/capability/evidence_schema.json`.

Every campaign verification result must contain:

| Field | Meaning |
|---|---|
| `campaign_id` | Campaign identifier, for example `T57-R03-01` |
| `capability` | Capability id from `CAPABILITY_REGISTRY.yaml` |
| `branch` | Git branch of the run |
| `commit` | `git rev-parse HEAD` |
| `tree` | `git rev-parse HEAD^{tree}` |
| `timestamp` | UTC timestamp |
| `host_os` | Host operating system |
| `android_environment` | Environment class, for example `MuMu RD测试` |
| `device_name` | `ro.product.model` |
| `adb_serial` | Session-resolved serial; never a hard-coded historical endpoint |
| `api_level` | `ro.build.version.sdk` |
| `abi` | `ro.product.cpu.abilist` |
| `build_result` | Build outcome |
| `static_result` | Static/source-gate outcome |
| `targeted_result` | Targeted campaign-tool outcome |
| `rd_result` | RD测试 dynamic outcome |
| `regression_result` | Broader regression outcome |
| `failures` | Structured failures with classification |
| `known_issues` | Known issue ids observed in this run |
| `evidence_files` | Paths to supporting artifacts |
| `maturity_level` | `RD_BASELINE` / `ANDROID_MATRIX` / `OEM_COMMERCIAL_MATRIX` |

Recommended extra fields stored by the runner: `boot_id`, `android_id`, `instance_name`, `va_pro_equivalent`.

## Maturity

- `RD_BASELINE`: MuMu instance `RD测试` only. A PASS here may update `rd_api32_status` and nothing else.
- `ANDROID_MATRIX`: API 33-36 evidence exists.
- `OEM_COMMERCIAL_MATRIX`: OEM and commercial-app evidence exists.

`RD_BASELINE` evidence must keep `va_pro_equivalent: NOT_PROVEN`.

## Failure classification

| Class | Meaning |
|---|---|
| `PASS` | Required check succeeded |
| `KNOWN_ISSUE` | Failed, already recorded in `KNOWN_ISSUES.yaml` |
| `EXPECTED_WARNING` | Expected architectural warning such as CXX5202 |
| `NEW_REGRESSION` | Failed and not previously recorded |
| `UNVERIFIED` | Not executed in this run |
| `FAIL` | Failed without a more specific class |
| `ENVIRONMENT_BLOCKED` | Environment could not be uniquely resolved |

Validate a payload with `python tools/capability/validate_campaign_infra.py`.
