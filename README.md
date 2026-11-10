# Email Pool Manager (Android)

WebView UI (assets/index.html) + native foreground service (OverlayService) drawing a draggable
COPY / PASTE overlay via "Display over other apps". Queue is stored in SQLite (QueueStore).
PASTE uses an AccessibilityService (EmailAccessibilityService) — the only Android mechanism for
inserting text into another app's focused field.

## Build
* Android Studio (Hedgehog+): File > Open this folder > Build > Build APK(s)
* CLI (JDK 17 + Android SDK 34, Gradle 8.7): `gradle assembleDebug`
  -> app/build/outputs/apk/debug/app-debug.apk
* GitHub: push this folder; Actions > "Build APK" produces the APK as an artifact.

## First run
1. Tap **Allow** for Display over other apps, and Notifications.
2. Tap **Enable** for Accessibility -> turn on "Email Pool".
   Android 13+ sideloaded apps: App info > ⋮ > "Allow restricted settings" first.
3. Add emails, tap **Start floating tool**, switch to any app.
