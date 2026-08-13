# XH UI inventory

Status is page-level evidence, not a claim that the Flash2 runtime is complete.

| ID | Page | Activity/Fragment/Class | Layout/resources | Entry | Exit | User operations | Data source | F1-F5 | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| XH-01 | Launcher | `WelcomeActivity` → `MainActivity` | `activity_main.xml`, `item_viewpager.xml` | launcher | Apps/settings/list | open product surface, choose user | `BlackBoxCore`, `ViewPagerAdapter` | F1 | COMPLETE |
| XH-02 | Apps | `AppsFragment`, `AppsAdapter` | `fragment_apps.xml`, `item_app.xml`, `app_menu.xml` | Home pager | launch, menu, settings | launch, stop, clear, remove, shortcut, reorder | `AppsRepository`, `BlackBoxCore` | F1 | COMPLETE |
| XH-03 | Installed App | `ListActivity`, `ListAdapter` | `activity_list.xml`, list item layout | Add/list action | Apps | select installed application, add to sandbox | `PackageManager`/XH package manager, `InstalledAppBean` | F1 | COMPLETE |
| XH-04 | Shortcut launch | `ShortcutActivity` | no dedicated layout | Launcher shortcut | target app / finish | launch exact package and user | shortcut Intent extras | F1 | COMPLETE |
| XH-05 | Location | `FakeManagerActivity`, `FollowMyLocationOverlay` | activity/list/search resources | feature/settings | Apps | enable, choose coordinate, disable | `BLocationManager`, location bean | F2 | PARTIAL |
| XH-06 | Settings | `SettingActivity`, `SettingFragment` | `activity_setting.xml`, `setting.xml` | Me/menu | Me | update XH settings | `BlackBoxLoader`, preferences | F1/F2-F5 | COMPLETE |
| XH-07 | GMS/user management | `GmsManagerActivity`, `GmsAdapter` | GMS layouts | settings/menu | Settings | install/uninstall GMS per user | `GmsRepository`, `BlackBoxCore` | F1 | PARTIAL |
| XH-08 | Camera | no page found | no matching XH layout found | none | none | none | none | F3 | XH_NOT_FOUND |
| XH-09 | Device identity | no page found | no matching XH layout found | none | none | none | none | F4 | XH_NOT_FOUND |
| XH-10 | Network/cell identity | no page found | no matching XH layout found | none | none | none | none | F5 | XH_NOT_FOUND |

The Flash2 UI must use XH-01 through XH-06 where the source exists. XH-08 through XH-10 are
resolved through the SX fallback contract documented below; the implementation must not label an
SX page as XH.
