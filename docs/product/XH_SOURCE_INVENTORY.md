# XH source inventory

T56 baseline: `a9bd10a4548edb33f2b9d05727da0f6a7121ccee`.

## Source roots

| Root | Evidence | Scope |
| --- | --- | --- |
| `ref/upstream/NewBlackbox/app/src/main/java/top/niunaijun/blackboxa` | checked into the repository and covered by `ref/manifests/newblackbox.json` | XH application UI, view models, adapters and product utilities |
| `ref/upstream/NewBlackbox/app/src/main/res` | layouts, menus, XML preferences, drawable/mipmap assets and localized strings | XH visual and interaction resources |
| `ref/upstream/VirtualApp` | covered by `ref/manifests/virtualapp.json` | historical runtime/reference material only; not a product UI authority |
| `docs/ui-migration/SX_UI_INVENTORY.md` | current repository migration record | SX fallback evidence when XH has no equivalent page |

## Scan coverage

The XH root was searched for `Activity`, `Fragment`, `Dialog`, `BottomSheet`, `Adapter`,
`RecyclerView`, `ListView`, `ViewPager`, `setContentView`, `inflate`, `startActivity`, click
listeners, shortcuts and widgets. Resources were separately searched under `layout`, `drawable`,
`mipmap`, `menu`, `xml` and `values*` for app, package, clone, multi-instance, user, import,
install, clear, uninstall, shortcut, location, camera, device, Wi-Fi, cell, network, settings and
home vocabulary.

## Product evidence

| Capability | XH source evidence | Completeness |
| --- | --- | --- |
| Launcher/Home | `view/main/WelcomeActivity.kt`, `view/main/MainActivity.kt`, `view/main/ViewPagerAdapter.kt`, `layout/activity_main.xml` | COMPLETE |
| Sandbox app grid | `view/apps/AppsFragment.kt`, `AppsAdapter.kt`, `AppsViewModel.kt`, `data/AppsRepository.kt`, `layout/fragment_apps.xml`, `layout/item_app.xml` | COMPLETE |
| Installed-app picker | `view/list/ListActivity.kt`, `ListAdapter.kt`, `ListViewModel.kt`, `bean/InstalledAppBean.kt`, `data/AppsRepository.kt` | COMPLETE |
| Add/import and per-user launch | `AppsViewModel.install`, `unInstall`, `clearApkData`, `launchApk` | COMPLETE |
| Clone/additional instance | `AppsViewModel`/`AppsRepository` user operations and `AppsFragment` menu | PARTIAL: exact user semantics are delegated to the XH runtime in this reference |
| Shortcut | `util/ShortcutUtil.kt`, `view/main/ShortcutActivity.kt`, `res/menu/app_menu.xml` | COMPLETE |
| Location | `view/fake/FakeManagerActivity.kt`, `FakeLocationViewModel.kt`, `FollowMyLocationOverlay.kt` | PARTIAL: map/picker depends on the XH map implementation |
| Settings | `view/setting/SettingActivity.kt`, `SettingFragment.kt`, `res/xml/setting.xml` | COMPLETE for XH settings present in this source |
| Camera, Device, Network/Cell | no matching XH product page or resource was found in this root | XH_NOT_FOUND |

XH is therefore the authority for Home, Apps, installed-app selection, clone vocabulary,
shortcut lifecycle, and the settings/navigation shape. Missing XH pages are explicitly handed to
the SX inventory instead of being invented from the current Flash2 screen.
