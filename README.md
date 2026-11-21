# eFootball AI Companion — Android starter v0.1

This is a native Android Studio starter project for a local-only visual companion.

## What works in this starter
- Standalone Android application shell.
- User-consented MediaProjection screen capture.
- Foreground capture service and persistent notification.
- Downscaled ImageReader frame acquisition.
- Frame-count and approximate capture-FPS telemetry.
- Optional floating companion panel, with Android overlay permission.

## What is deliberately not implemented
- Player/ball recognition.
- Tactical recommendations.
- Touch injection, gameplay automation, or autonomous online play.
- Any network upload. Captured frames are not persisted or transmitted.

## Build
1. Open this folder in Android Studio (recent version with Android Gradle Plugin 8.7.3 support).
2. Allow Gradle sync and install the Android SDK Platform 35.
3. Connect an Android device and run the `app` configuration.
4. Tap **Start screen analysis** and approve Android's screen-capture prompt.
5. Optionally grant **Display over other apps** permission and enable the floating panel.

## Device notes
The project targets Android 8.0+ (minSdk 26) and targetSdk 35. Android 13+ may require notification permission depending on OS behaviour. Screen capture requires explicit user approval each session. Some manufacturers restrict background services; keep the app visible during initial testing and exempt it from aggressive battery optimisation if needed.

## Performance notes
The capture service downscales frames to a maximum width of 720 pixels and uses a two-image buffer. It currently measures capture cadence; it does not yet run computer vision. The next milestone is a measured, quantised on-device detector and lightweight tracker.

## Privacy and fair play
Use the companion for permitted visual analysis and coaching. It does not control the game or automate competitive matches. Follow eFootball's terms and Android's screen-capture rules.
