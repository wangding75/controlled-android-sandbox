# SX UI fallback inventory

SX is used only when the XH inventory is `XH_NOT_FOUND`, `XH_PLACEHOLDER_ONLY` or
`XH_CONTRACT_ONLY`. Only UI, product interaction and business vocabulary are reused; SX Runtime,
Hook, BlackBox and Binder implementations are not moved back into Flash2.

| XH feature | XH status | SX evidence | SX behavior used by Flash2 | Reason |
| --- | --- | --- | --- | --- |
| F2 Location | PARTIAL / map picker absent | `docs/ui-migration/SX_UI_INVENTORY.md`; current `InstanceSettingsActivity` location section | enable/provider/coordinate/altitude/accuracy/speed/bearing/interval/trajectory/save/reset; picker remains `NOT_IMPLEMENTED` | XH location page is incomplete |
| F3 Camera | XH_NOT_FOUND | current Flash2 camera profile contract and `InstanceSettingsActivity` camera section | enable, image/video source, media selection, metadata, clear; Camera1/Camera2 hidden | no XH user page exists |
| F4 Device | XH_NOT_FOUND | current device profile contract and `InstanceSettingsActivity` device section | Android ID, brand/model/manufacturer, serial, IMEI/MEID/IMSI/ICCID, SIM/operator, generate/random/reset/save | no XH user page exists |
| F5 Network/Cell | XH_NOT_FOUND | current network profile contract and `InstanceSettingsActivity` network section | SSID/BSSID/MAC, scan profile, MCC/MNC/LAC/CID, save/reset | no XH user page exists |
| Profile persistence | XH_CONTRACT_ONLY | `VirtualDeviceServiceStore`, `VirtualNetworkServiceStore`, peripheral stores | all edits pass through package/instance-scoped stores | XH source has no equivalent full contract |

No SX fallback is allowed for the package runtime or Binder boundary. The package and instance
flows stay on the current Package Service and Runtime contracts.
