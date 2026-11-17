# Android app

This is the native Android application, built with Kotlin and Jetpack Compose.

## Modules

- `app`: application entry point and top-level navigation.
- `core/model`: shared models (drive settings, coordinates, detections).
- `core/network`: Android side of the API contract (`DetectionApi`), with an HTTP client and an in-memory stub.
- `core/data`: DataStore-backed settings repository, reactive behavior gates, and the navigation/detection logic (`navigation/`).
- `core/ui`: shared theme and bottom navigation.
- `feature/settings`: the Settings destination.

## Local setup

Open this directory as a Gradle project in Android Studio with JDK 17 or later and Android SDK 37 installed. Secrets and machine-specific values belong in the ignored `secrets.properties` and `local.properties` files; do not commit credentials.

The settings switches persist locally with DataStore. The Map placeholder includes an explicit drive-session control; CameraX captures rear-camera frames only while a session is active, road hazard detection is enabled, and the app is in the foreground. No hazard classifier is integrated yet, so frames are discarded locally and never uploaded. Audible alerts and rerouting are also not implemented yet.

## Navigation and detection logic (issue #38)

`core/data/.../navigation/NavigationSession` holds all the Navigation screen's logic, so the UI only renders `NavigationUiState` and calls actions:

| UI element (Figma 10/11) | Read from `state` | Action |
|---|---|---|
| Map route line and "you are here" marker | `route.points`, `position`, `progress` | |
| Turn card | `turn` (instruction, meters to it) | |
| Trip bar | `trip` (remaining meters/seconds), `driving`, `arrived` | `startDrive()`, `endDrive()` |
| Detection chip "Detect pothole" button | | `reportPothole()` |
| "New hazard reported" alert | `alert` (phase, severity, `undoSecondsLeft`) | `undo()` |
| "1 report waiting to send" | `pendingUploads` | |
| Toast | `message.text` | `messageShown()` |

What is mocked for the prototype:

- **Route:** `MockRoutes.CAMPUS_LOOP` is a hardcoded polyline, with no Google Routes API call.
- **GPS:** `SimulatedGps` moves the car along the route at about 30 mph.
- **Detection:** `reportPothole()` stands in for the YOLO model and sends `PlaceholderFrame` as the image.

The calls themselves are real: `reportPothole()` sends `POST /v1/detections` and `undo()` sends `DELETE /v1/detections/{id}` within the 5 s window. Reports that fail to send stay on the phone and are retried with the same `client_detection_id`. Use `StubDetectionApi` to demo without a server, or `HttpDetectionApi("http://10.0.2.2:5000/v1")` to talk to the Flask dev server from the emulator. That server is plain `http://`, so a debug build also needs cleartext traffic allowed (for example `android:usesCleartextTraffic="true"` in a debug manifest).

```kotlin
// In RoadBuddyApp, next to DriveSessionGate:
val scope = rememberCoroutineScope()
val navigationSession = remember { NavigationSession(scope, StubDetectionApi()) }

// In DriveSessionScreen:
val nav by navigationSession.state.collectAsState()
```
