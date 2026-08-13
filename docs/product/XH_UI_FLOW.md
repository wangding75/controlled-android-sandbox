# XH user flow

```text
Launcher
  -> WelcomeActivity
  -> MainActivity / Apps pager
  -> Apps list
  -> Add App
      -> Installed App list (ListActivity)
          -> selected host package
          -> Package Import / Trust / Prepare / Package Record
      -> APK file import
  -> Package card / exact virtual user
      -> Launch | Stop | Add instance | Settings | Clear | Delete | Shortcut
  -> ShortcutActivity
      -> exact package + exact virtual instance launch
```

The two add paths have different product meaning: installed-app selection clones the physical host
artifact into the sandbox, while an already imported package creates another virtual instance.
Both paths end in the same package authority and catalog; the UI does not maintain a second clone
list.

F2-F5 are opened from the selected instance and are scoped by `(packageName, virtualUserId)`.
Location uses the existing profile editor and leaves map selection `NOT_IMPLEMENTED` until a map
SDK decision exists. Camera1/Camera2 are runtime details and are not user-facing mode choices.
