# Analyze Position

The **Analyze Position > Setup** page of the gnubg Android app (CBG Pro), rebuilt with the
look and feel of MyRace. Everything else from that app is removed.

You put a position on the board, set who is on roll, the dice, the match score and the cube,
and gnubg analyses it:

* **no dice** → gnubg's cube decision (No double / Double, take / Double, pass), with win,
  gammon and backgammon chances, cubeless and cubeful equity and the equity of each action;
* **dice set** → gnubg's ranked checker plays with their equities;
* **Rollout** → gnubg's own rollout of the cube decision (144 games) or of the top three plays
  (1296 games each, cubeful, variance reduced, seed shown).

All analysis is gnubg's. The app only draws the board and encodes what you set up
(gnubg's own `idsFromState`, then `SetGNUbgID`, which runs gnubg's `CheckPosition`).

## Changing the position (the "easier" part)

| Gesture | Effect |
|---|---|
| Tap a point at the height you want | the stack grows to that height (like MyRace) |
| Tap the top checker of a short stack | removes it |
| Tap the 5th slot of a full stack | adds one more; the point then shows a number |
| Long-press a point | clears it |
| **EDIT** button in the bar (or the *Placing* pill) | switches between placing *You* and *GNU* checkers |
| Tap the upper / lower half of the bar | puts a GNU / You checker on the bar (long-press clears) |
| Tap the cube / long-press the cube | doubles it / passes it between Centred, You and GNU |
| Tap a die / long-press a die | changes it / removes the dice (no dice = cube decision) |
| **Start pos**, **Clear**, **Swap** | opening position, empty board, swap the two sides |
| **ID** | paste a GNU BG ID or XGID, or copy this board's ID |

Match length, score, Crawford, on-roll, cube value and owner are pills in the side panel
with − / + and tap-to-cycle controls. Borne-off checkers are counted for you (15 minus
what is on the board and bar).

## Building

The engine is C (gnubg 1.08 + glib), so a build needs the Android NDK. Two ways:

### 1. GitHub Actions (nothing to install)

Push this folder to a GitHub repository and run **Actions > Build APK > Run workflow**
(it also runs on every push to `main`). When it finishes, download the
`AnalyzePosition-apk` artifact and install the APK.

### 2. On your own computer

Needs Android Studio (SDK platform 36, NDK `28.2.13676358`), JDK 21, `meson`, `ninja`,
`cmake`, `patch`, `curl`.

```
./build_native_android.sh        # builds glib + the gnubg engine into gnubg-app/app/src/main/jniLibs
cd gnubg-app && ./gradlew assembleRelease
```

The APK is `gnubg-app/app/build/outputs/apk/release/app-release.apk`.
It is signed with the debug key, so it installs directly but is not suitable for a store.

## Layout

* `gnubg-app/` — the Android app. The new code is in
  `app/src/main/kotlin/com/clavierhaus/gnubg/analyze/`:
  `MyRaceTheme.kt` (the three MyRace themes), `PositionModel.kt` (editing rules),
  `PositionBoard.kt` (the board), `GnubgSession.kt` (all calls into gnubg),
  `AnalyzeScreen.kt` (rail, board, panels), `Widgets.kt` (pills and buttons).
* `engine-core/`, `jni-bridge/` — gnubg and its Android bridge, **unmodified** from gnubg-android.
* `build_glib_android.sh`, `build_native_android.sh` — unmodified, except that the last
  line of `build_native_android.sh` (a git-based stamp) is removed.

The package name stays `com.clavierhaus.gnubg` because the JNI function names in the
bridge are built from it. The application id is `com.myrace.analyze`.

## Licence

This is a derivative of gnubg-android (GPL-3.0), which is itself based on GNU Backgammon
(GPL-3.0-or-later). This app is therefore GPL-3.0 as well: see `COPYING`, and anyone you
give the APK to is entitled to this source. gnubg's name, the original app's name
("CBG Pro") and its icon are not used here.
