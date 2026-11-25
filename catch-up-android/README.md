# Catch-Up for Android

Native version of [Catch-Up Timer](../catch-up-timer/) that can tick people off automatically.

**Install / update on your phone:** https://github.com/CianDunlevy/claude-playground/releases/latest/download/CatchUp.apk

## What it does

- Same list as the web app: most overdue first, green / amber / red, tap a card to log a catch-up (with Undo).
- **Calls log automatically, silently.** Phone calls with a saved number, and WhatsApp / Messenger / Instagram / Google Chat calls
  matched by name (plus any "other names" you add), tick the person off once they last at least 1/3/5/10 min.
- **Edits save as you go.** No Save button when changing someone.
- **Home-screen widget** with the 4 most urgent people. Tap a name to log a catch-up without opening the app.
- **One notification, only while someone is overdue.** It buzzes at most once a day (at a time you pick), only when
  someone new is overdue, and clears itself when you catch up. So the app icon's dot means "someone's overdue".
- Long-press the app icon → **Add person**.
- Backup and import in the same JSON format as the web app (`phones` and `aliases` are extra optional lists).

No internet permission: call history, contacts and notifications never leave the phone.

## How messaging-app calls are detected

WhatsApp, Messenger, Instagram and Google Chat / Meet don't share call history with other apps, so `calls/CallNotificationListener.kt`
watches for their "call in progress" notification (Android's CallStyle, or older "Ongoing voice call" style). When it
disappears the call has ended; the duration comes from the call timer. The caller's name is matched to a person by name
or "other names" (`Logic.personForCall`, unit tested). Settings shows the last call seen and whether it matched, to help
add the right names. If one of those apps changes its notification, detection may need adjusting.

## How it's built

Kotlin + Jetpack Compose, no other libraries apart from AndroidX WorkManager.

| Path | What |
| --- | --- |
| `model/` | People, date maths, phone-number matching, backup format, merge (plain Kotlin, unit tested) |
| `data/Store.kt` | Saves people as JSON in app storage; settings |
| `calls/CallScanner.kt` | Reads the last 30 days of the call log; safe to run repeatedly |
| `calls/CallNotificationListener.kt` | WhatsApp / Messenger / Instagram / Google Chat call detection |
| `work/Work.kt` | Background jobs: scan after each call, hourly backstop, daily overdue check |
| `notify/` | The overdue notification and widget-tap action |
| `widget/` | Home-screen widget |
| `ui/` | Compose screens |

Every push to `catch-up-android/` runs `.github/workflows/catch-up-android.yml`, which runs the tests, builds a
signed APK and publishes it as the latest GitHub release.

The signing key in `app/signing/` is committed on purpose so every build installs as an update over the last one.
It only matters for this personal, sideloaded app; never use it for anything published.

Local build: `./gradlew testDebugUnitTest assembleRelease` (needs the Android SDK; set `sdk.dir` in `local.properties`).
Screens are rendered to `app/build/reports/paparazzi/` by the screenshot tests.
