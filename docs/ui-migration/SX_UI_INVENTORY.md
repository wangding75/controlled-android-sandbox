# SX UI inventory and Flash 2 migration baseline

This inventory records the user-visible SX product surface used as the visual and interaction
reference for Flash 2. It is a reference document only: no SX Activity, Fragment, XML, Runtime,
Hook, or BlackBox implementation is copied into the current sandbox.

| Page | Legacy source | User purpose | Primary actions | Flash 2 target | Status |
| --- | --- | --- | --- | --- | --- |
| Home | `app/src/main/java/com/sx/app/ui/home/HomeFragment.java`, `fragment_home.xml` | Product entry point and feature discovery | Open sandbox apps, location, camera, device, network | `MainActivity` home tab | Rebuilt |
| Apps | `AppListFragment.java`, `fragment_app_list.xml` | View imported applications and instances | Add/import, launch, clone, remove, open settings | `MainActivity` apps tab + `PackageAdapter` | Rebuilt |
| App picker | `AppPickerActivity.java`, `activity_app_picker.xml` | Select an installed APK | Select APK and import | Android document picker in `MainActivity` | Rebuilt |
| App detail / instance settings | `AppDetailActivity.java`, `activity_app_detail.xml` | Operate on one package/user scope | Launch, clone, clear, remove, open independent settings | `InstanceSettingsActivity` | Rebuilt |
| Location | `LocationSettingsActivity.java`, `activity_location_settings.xml` | Configure virtual location | Enable, coordinates, accuracy, interval, trajectory, save/reset | Instance settings / Location | Rebuilt |
| Location picker | `LocationPickerActivity.java`, `activity_location_picker.xml` | Pick a location | Search/select a built-in point | Profile-only coordinate editor until a map SDK is adopted | Explicitly not implemented |
| Camera | `VirtualCameraActivity.java`, `activity_camera.xml` | Configure capture substitution | Enable, IMAGE/VIDEO, choose media, preview metadata, clear | Instance settings / Camera | Rebuilt |
| Device | `DeviceSettingsActivity.java`, `activity_device_settings.xml` | Configure one virtual identity | Android ID, IMEI/MEID, brand, model, manufacturer, serial, SIM/operator, save/reset | Instance settings / Device | Rebuilt |
| Network / cell | `NetworkSettingsActivity.java`, `activity_network_settings.xml` | Configure Wi-Fi and cell identity | SSID/BSSID/MAC, MCC/MNC/LAC/CID, scan profile, save/reset | Instance settings / Network | Rebuilt |
| Me | `MeFragment.java`, `fragment_me.xml` | Product information and support | Version, authorization, diagnostics | `MainActivity` Me tab | Rebuilt |
| Diagnostics | `SpoofProbeActivity.java`, `activity_spoof_probe.xml` and existing debug status | Engineering verification | Runtime status, component probes, profile versions, evidence | `DeveloperDiagnosticsActivity` | Isolated |

## Visual and interaction reference

- Three bottom destinations: `首页`, `应用`, `我的`.
- Home is a calm product surface with status and feature cards; internal Runtime vocabulary is
  not shown there.
- App cards show icon, name, package, instance identity, and a short status. Common actions are
  visible without a long-press menu.
- Settings pages use one vertical form that remains usable at 320dp and 360dp widths. Long text
  wraps, and actions are full-width or arranged in evenly weighted rows.
- Camera configuration exposes only media kind and source; Camera1/Camera2 are Runtime
  compatibility details and are not user-facing choices.
- Location, device, Wi-Fi, and cell edits are written through the scoped profile APIs. There is no
  second UI-owned identity store.
- Unsupported map selection is labelled `NOT_IMPLEMENTED`; it is never represented by a fake
  successful action.

## Architecture boundary

```text
Activity / screen
    -> SandboxUiState + SandboxUiAction
    -> SandboxViewModel
    -> SandboxApplicationLayer
    -> SxSandboxAdapter / PackageServiceClient
    -> Sandbox SDK, contracts, and profile stores
```

The UI does not reference Binder internals, native hooks, guest filesystem classes, runtime
service implementations, `SandboxEngine`, or BlackBox code.
