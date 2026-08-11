# XH network / cell implementation report

## Evidence

**DECOMPILED:** SX `NetworkHook` hooks `WifiInfo.getSSID`, `getBSSID`, and `WifiManager.getScanResults` from a `NetworkProfile`. The recovered dump contains references to a `CellHook` installation from the profile runtime, but no complete `CellHook` implementation was recovered in the extracted class report.

**SOURCE:** the protected XH restore does not provide a complete Wi-Fi/cell implementation. The XH README identifies native hook work as incomplete.

**RUNTIME_OBSERVED:** SX launch initializes `IWifiManagerProxy`, `IWifiScannerProxy`, `ITelephonyManagerProxy`, `IPhoneSubInfoProxy`, `ILocationManagerProxy` and `IConnectivityManagerProxy`. This proves service-hook setup, not the values returned to DingTalk.

## Controlled path

- `VirtualWifiProfileSnapshot` owns current SSID/BSSID/MAC, RSSI, frequency and bounded scan list.
- `VirtualCellInfoSnapshot` owns RAT, MCC/MNC, LAC/TAC, CID, PCI, ARFCN, registration and signal level.
- `VirtualTelephonyProfileSnapshot` persists cell list alongside SIM/operator data.
- Deterministic defaults keep Wi-Fi and LTE cell in one synthetic environment and are isolated by package/user.

## Boundary

Concrete `CellInfo*` object construction varies by API level. The current generic factory projects bounded fields where a platform class exposes them and does not pass through Host cell objects. API-specific identity adapters and physical radio validation remain follow-up work; no DingTalk cell page was reached.

