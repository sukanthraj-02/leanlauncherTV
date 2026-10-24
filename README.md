# LeanLauncher

A deliberately small Android TV home launcher. Native Views, no feeds, no background
services, no advertising. It exists to be fast and to stay out of RAM's way. The only
optional network feature is a photo wallpaper, off by default.

| | |
|---|---|
| Package | `com.sukanth.leanlauncher` |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 36 |
| Release APK | ~223 KB (unsigned) |
| Permissions | `INTERNET` (opt-in wallpaper and on-device ADB only), no storage, no accessibility |
| Services / receivers | none |

## Build

Requires JDK 17+ (built and verified on JDK 26), the Android SDK, and the checked-in
Gradle wrapper.

```sh
./gradlew :app:assembleDebug      # installable, debug-signed
./gradlew :app:assembleRelease    # minified + resource-shrunk, unsigned
./gradlew :app:testDebugUnitTest  # unit tests
./gradlew :app:lintDebug          # static checks
```

`local.properties` must point at your SDK (`sdk.dir=...`).

## Install on the TV

1. Copy `app/build/outputs/apk/debug/app-debug.apk` to the TV.
2. On the TV: **Settings > Security > Unknown sources** (wording varies by brand), or use
   a downloader app.
3. Open the APK and confirm the install. The installer now offers **Open**, which starts
   the setup screen.

### Opening it afterwards

Pick either route:

- **TV app row** — LeanLauncher appears there via its `LEANBACK_LAUNCHER` entry.
- **ADB** — `adb shell am start -n com.sukanth.leanlauncher/.SetupActivity`

A home-only package gets no installer Open button and no launcher entry, which would
leave no way in. That is why the setup screen also declares `CATEGORY_LAUNCHER` and
`CATEGORY_LEANBACK_LAUNCHER` alongside the separate home activity.

The launcher does not replace the stock home screen until you complete one of the two
paths below.

## Making it the home app

Android does not let an app promote itself. Setup provides three routes because firmware
behaviour differs.

### Path A: one press on the TV (no computer)

Open **LeanLauncher > Setup** and find the **Stock launcher switch** section. Press
**Switch to leanlauncherTV**. The app connects to the TV's own debugging service at
`127.0.0.1:5555`, falling back to the TV's LAN address, disables the stock launchers it
detected, enables LeanLauncher's own HOME component, and makes it the default.

The first action shows an **Allow on-TV ADB?** consent dialog. No terminal is exposed:
the app only runs a fixed sequence of disable, restore, and resolve commands.

Some firmware blocks loopback ADB or refuses the authorization. If that happens, Setup
reports the failure and the copy buttons in **No on-TV ADB? Use a computer** remain
available. Nothing is changed silently.

### Path B: Android's home-role dialog

Open **Make leanlauncherTV the home app**. This calls the Android 10+ role API and the
system shows its own confirmation dialog.

Use **Continue to home** if you want to open the grid before committing.

The app then re-checks the result by resolving `MAIN + HOME` again. It reports
"verified" only when HOME actually resolves to LeanLauncher, because some Android 11 TV
builds accept the request and silently keep the OEM launcher.

### Path C: manual ADB (the universal fallback)

Only needed when the on-TV paths do nothing. Requires a computer on the same network.

On the TV: **Settings > Device Preferences > About > Build**, press Build seven times,
then enable **Network debugging**.

In **LeanLauncher > Setup**, open **No on-TV ADB? Use a computer** and press
**Copy: disable stock launcher** or **Copy: restore stock launcher**. They place a
ready-to-run script on the clipboard and work even after LeanLauncher is already the
verified home app. Paste the script into a terminal on a computer with ADB access to the
TV.

The disable script finds which stock launchers are installed on your exact TV and fills
them in for you; on most Haier sets that means:

```sh
adb connect <TV-IP>:5555
adb shell pm disable-user --user 0 com.google.android.tvlauncher
adb shell pm disable-user --user 0 com.haier.launcher.haiermatrix
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
adb shell cmd package set-home-activity com.sukanth.leanlauncher/com.sukanth.leanlauncher.HomeActivity
adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME
```

Disabling a launcher is reversible and is not an uninstall. The `setupwraith` line only
matters if that package exists on your TV; the in-app script includes exactly the rivals
it detected and nothing else. It deliberately never touches `com.android.*` platform
components such as the TV settings FallbackHome, which is the safety net that opens when
no other home remains.

### Restoring the original launcher

Press **Restore stock launcher** under **Stock launcher switch**, or **Copy: restore stock
launcher** in the manual section. The script re-enables every launcher the disable script
targets and, when the app observed the original stock home component, hands HOME back to
that same app. Setup remembers the packages and component it saw, so restore still works
after a stock launcher has been disabled and dropped out of the normal HOME candidate
list. The copied script looks like this:

```sh
adb connect <TV-IP>:5555
adb shell pm enable com.google.android.tvlauncher
adb shell pm enable com.haier.launcher.haiermatrix
adb shell pm enable com.google.android.tungsten.setupwraith
adb shell cmd package set-home-activity com.google.android.tvlauncher/com.google.android.tvlauncher.MainActivity
adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME
```

If the original component was never observed (for example, LeanLauncher was installed
after the stock launcher had already been disabled by hand), the script re-enables the
packages and asks you to choose the stock launcher under **Settings > Apps > Default
apps > Home app**. You can also re-enable it in **Settings > Apps > Show system apps** and
pick it there.

## Verifying the switch

After a one-press action, Setup re-probes the TV and prints the outcome. A verified switch
means `MAIN + HOME` resolves to `com.sukanth.leanlauncher/...` and the stock packages are
disabled. The same check from a computer is:

```sh
adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell pm list packages -d
```

A system update can re-enable a disabled stock launcher. If HOME returns to Google TV
after an update, re-run the switch. This is a property of Android TV, not a bug in this
app.

## Wallpaper

Setup's **Wallpaper** section supports three sources:

- **Built-in photos** — full-resolution random wallpapers from
  [Wallhaven](https://wallhaven.cc) with `categories=100`, so only its General category is
  used. The People and Anime categories are excluded. SFW only; no account or API key.
- **Local images** — **Choose local images** opens Android's document picker. Up to 40
  images can be selected, and the slideshow cycles through them. The app keeps a
  persistable read grant and reads the files in place; it does not copy them and does not
  request storage permission. If the picker cannot grant persistent access, Setup says the
  images may need choosing again after a restart.
- **Website** — **Website wallpaper (paste URL)** accepts a public page, RSS/Atom feed,
  JSON response, or direct image address. The app finds image links on that page
  (`og:image`, `img`/`srcset`/`data-src`, CSS backgrounds, direct image links, and quoted
  URLs), then rotates them. Pages that build images with JavaScript, require a login, or
  block direct image downloads will not work. No cookies or credentials are sent. Only use
  a site you trust.

The other controls are:

- **On / Off** — off by default; turning it on loads the current source on the next home
  visit.
- **Change every …** — 15 minutes, 30 minutes, 1 hour, 6 hours, or 1 day.
- **Next wallpaper now** — switches the wallpaper on and loads a fresh image the next time
  the home screen resumes.

A new image is loaded only while the home screen is visible; a backgrounded launcher never
touches the network. Remote images are downloaded into a bounded buffer and decoded into
one screen-sized RGB_565 bitmap with power-of-two sampling (target capped at 1920×1080).
They are never
written to storage, so after the launcher process restarts the next visit fetches a fresh
image. There is no `AlarmManager` and no job scheduler: the timer runs on the home
activity's main looper and is removed when the activity stops.

## Remote controls

| Key | Action |
|---|---|
| D-pad | Move focus |
| OK / Centre | Open app |
| Long press | Pin, unpin, reorder, app info, hide from Home |
| Menu | Same menu, for remotes without long press |
| Back | Close the menu |
| HOME | Return to the top of the grid |

Every action is reachable without long press.

## Design notes

- One activity for the grid, one for setup. `stateNotNeeded` and `excludeFromRecents` on
  the home activity cut saved-state and task-record memory.
- Icons load on bind only, into a byte-budgeted `LruCache` (512 KiB) of tile-sized
  bitmaps — ~6 ARGB icons or ~12 RGB_565 icons, which is deliberately smaller than one
  screen of tiles. Opaque icons are decoded as RGB_565, and `onTrimMemory` keeps half the
  budget under moderate pressure and empties it once the grid is off screen. A recycled
  tile can never show another app's icon.
- App discovery runs on a single background thread, only when the launcher becomes
  visible. There is no polling, no `AlarmManager`, no boot receiver, no wake lock.
- The clock ticks once a minute, aligned to the minute boundary, and only while visible.
- The optional wallpaper follows the same rule: one RGB_565 bitmap in memory, no disk
  copy, and downloads only between onStart and onStop. Remote responses are capped at
  16 MB and decoded with power-of-two sampling for a target capped at 1920×1080, so the
  retained bitmap stays small.
- The app accepts process death as normal. Restoring quickly is cheaper than trying to
  stay resident.
- `androidx.profileinstaller` is excluded because it registers a receiver and a startup
  provider that a launcher does not need.
- The app cannot disable or uninstall another app: Android throws
  `SecurityException` for that, so no code here attempts it.

## License

MIT. See [LICENSE](LICENSE). The bundled ADB client (`com.tananaev:adblib`) is
BSD-3-Clause; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).