# UI icon resource map

All stable Flash2 feature icons live under `app/src/main/res/drawable-nodpi/`. They are native
Android vector resources so a user can replace the file with a PNG at the same stable resource
name without changing Java code. Runtime-generated bitmaps, emoji, base64 and host absolute paths
are not used as product icon sources.

| UI position | Feature | Resource path | Current source | XH resource found | SX fallback | Replacement contract |
| --- | --- | --- | --- | --- | --- | --- |
| Installed/sandbox app card fallback | App placeholder | `@drawable/ic_app_placeholder` | Flash2 stable vector | XH `ic_empty.xml` found | XH-inspired placeholder | replace resource at same name |
| Home feature card | Location | `@drawable/ic_feature_location` | Flash2 stable vector | XH location feature exists | no fallback needed | replace resource at same name |
| Home feature card | Camera | `@drawable/ic_feature_camera` | Flash2 stable vector | XH page not found | SX camera contract | replace resource at same name |
| Home feature card | Device | `@drawable/ic_feature_device` | Flash2 stable vector | XH page not found | SX device contract | replace resource at same name |
| Home feature card | Network | `@drawable/ic_feature_network` | Flash2 stable vector | XH page not found | SX network/cell contract | replace resource at same name |
| Add App action | Installed App | `@drawable/ic_feature_add_app` | Flash2 stable vector | XH `ic_add` found | none | replace resource at same name |
| Instance card clone action | Clone | `@drawable/ic_feature_clone` | Flash2 stable vector | XH clone menu found | none | replace resource at same name |
| Instance card shortcut action | Shortcut | `@drawable/ic_feature_shortcut` | Flash2 stable vector | XH shortcut menu found | none | replace resource at same name |

Installed application icons use `ApplicationInfo.loadIcon`; sandbox icons use the imported archive
application icon. Both fall back to `ic_app_placeholder` only when parsing/loading fails.
