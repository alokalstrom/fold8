# Fold Study

An experimental Android home screen for a physical Samsung Galaxy Z Fold 8. The app uses measured hinge movement to keep the cover and inner scenes visually connected while the phone opens or closes.

The current source is **0.18.1-entry**, which hardens external activity entry on top of the user-tested **0.18.0-sweep** prototype. Its app grid stays on the right; local date and calendar cards appear on the left. Separate spatial blur masks soften the outgoing cover and reveal the incoming inner surface. User app selections persist. Cover touch is blocked while folding.

This targets one tested Samsung model and firmware behavior. It uses non-root Shizuku access to a Samsung diagnostic angle getter and undocumented display-state requests. It is not a general-purpose launcher release or a claim of exact iOS animation fidelity.

## Build

Install JDK 17 or newer and Android SDK platform 36. Configure the Android SDK through `ANDROID_HOME` or an untracked `local.properties` file.

On Windows with Android Studio installed:

```powershell
./scripts/build.ps1
```

The script assembles the debug APK, runs unit tests and lint, and writes a versioned APK and SHA-256 file under the ignored `artifacts` directory. For a different JDK location, pass `-JavaHome`.

On other platforms:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The Gradle wrapper downloads its declared distribution and dependencies when necessary. The debug APK is also available under `app/build/outputs/apk/debug/`.

## Use on the target phone

1. Install the locally built debug APK through ADB or Android's package installer.
2. Install and start [Shizuku](https://shizuku.rikka.app/), then authorize Fold Study when prompted.
3. Open Fold Study, enable its automatic folding mode and select it as the default Home app if desired.
4. In Samsung's cover-screen continuation setting, select the option that keeps the app on the cover when closing.
5. Open and close slowly; hold partway and reverse direction to check tracking. Use Controls to return to the regular Home selection if needed.

Without a live angle connection the launcher has a static layout and indicates that folding is paused. It does not invent intermediate hinge measurements.

## Current limits

- Inner-screen app launch can blink when Samsung remaps the displays. The user accepted this limitation for the prototype.
- At full opening the cover is painted black; that is not physical panel power-off.
- The foreground display lease has unit coverage; a physical fault-cleanup test remains outstanding.
- The spatial sweep has build/test coverage and positive user motion feedback, but no controlled intermediate screenshot series yet.
- The date/calendar cards are local read-only content, not event-provider widgets.
- Diagnostic controls include historical experiments. Prefer the normal automatic Home path.

See [architecture](docs/ARCHITECTURE.md), [validation](docs/VALIDATION.md) and [the tracked import/review issue](https://github.com/alokalstrom/fold8/issues/1). No APKs, phone captures or raw device logs are published in this repository.
