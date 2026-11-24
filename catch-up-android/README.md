# Catch-Up for Android

Native version of [Catch-Up Timer](../catch-up-timer/) that can tick people off automatically.

**Install / update on your phone:** https://github.com/CianDunlevy/claude-playground/releases/latest/download/CatchUp.apk

## What it does

- Same list as the web app: most overdue first, green / amber / red, tap a card to log a catch-up (with Undo).
- **Phone calls log automatically.** Add numbers to a person (or pick from contacts). Calls of at least 1/3/5/10 min
  (your choice) tick them off, and a notification says so with an **Undo** button.
- **Home-screen widget** with the 4 most urgent people. Tap a name to log a catch-up without opening the app.
- **Daily reminder** notification at a time you pick, only when someone is due or overdue.
- Long-press the app icon → **Add person**.
- Backup and import in the same JSON format as the web app (`phones` is an extra optional list).

No internet permission: call history and contacts never leave the phone.

## Next

WhatsApp, Messenger and Instagram calls, by reading those apps' "ongoing call" notifications.

## How it's built

Kotlin + Jetpack Compose, no other libraries apart from AndroidX WorkManager.

| Path | What |
| --- | --- |
| `model/` | People, date maths, phone-number matching, backup format, merge (plain Kotlin, unit tested) |
| `data/Store.kt` | Saves people as JSON in app storage; settings |
| `calls/CallScanner.kt` | Reads the last 30 days of the call log; safe to run repeatedly |
| `work/Work.kt` | Background jobs: scan after each call, hourly backstop, daily reminder |
| `notify/` | Notifications and the Undo / widget-tap actions |
| `widget/` | Home-screen widget |
| `ui/` | Compose screens |

Every push to `catch-up-android/` runs `.github/workflows/catch-up-android.yml`, which runs the tests, builds a
signed APK and publishes it as the latest GitHub release.

The signing key in `app/signing/` is committed on purpose so every build installs as an update over the last one.
It only matters for this personal, sideloaded app; never use it for anything published.

Local build: `./gradlew testDebugUnitTest assembleRelease` (needs the Android SDK; set `sdk.dir` in `local.properties`).
Screens are rendered to `app/build/reports/paparazzi/` by the screenshot tests.
