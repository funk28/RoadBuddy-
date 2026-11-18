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

The calls themselves are real: `reportPothole()` sends `POST /v1/detections` and `undo()` sends `DELETE /v1/detections/{id}` within the 5 s window. Reports that fail to send stay on the phone and are retried with the same `client_detection_id`. Use `StubDetectionApi` to demo without a server, or `HttpDetectionApi` to talk to the Flask backend (see below).

```kotlin
// In RoadBuddyApp, next to DriveSessionGate:
val scope = rememberCoroutineScope()
val navigationSession = remember { NavigationSession(scope, StubDetectionApi()) }

// In DriveSessionScreen:
val nav by navigationSession.state.collectAsState()
```

### Running against the Flask backend

1. Start the backend as described in `backend/README.md`, with `DEV_AUTH=true` in `backend/.env`. Every `/v1` request needs `Authorization: Bearer ...`. Until Google sign-in exists, the backend accepts any `dev:<name>` token in dev mode.
2. Point the app at it with a dev token:

   ```kotlin
   NavigationSession(scope, HttpDetectionApi("http://10.0.2.2:5000/v1", idTokenProvider = { "dev:aiden" }))
   ```

   `10.0.2.2` is the emulator's address for your computer. If it times out (often a firewall), run `adb reverse tcp:5000 tcp:5000` and use `http://127.0.0.1:5000/v1` instead.
3. Allow plain `http://` in **debug builds only**, limited to those two hosts. `core:network` already adds the `INTERNET` permission. Add these to the `app` module:

   `app/src/debug/res/xml/network_security_config.xml`

   ```xml
   <?xml version="1.0" encoding="utf-8"?>
   <network-security-config>
       <domain-config cleartextTrafficPermitted="true">
           <domain includeSubdomains="false">10.0.2.2</domain>
           <domain includeSubdomains="false">127.0.0.1</domain>
           <domain includeSubdomains="false">localhost</domain>
       </domain-config>
   </network-security-config>
   ```

   `app/src/debug/AndroidManifest.xml`

   ```xml
   <?xml version="1.0" encoding="utf-8"?>
   <manifest xmlns:android="http://schemas.android.com/apk/res/android">
       <application android:networkSecurityConfig="@xml/network_security_config" />
   </manifest>
   ```

Without a token every call fails with `401 unauthorized`, and the app shows "Couldn't report hazard". The backend refuses Undo after 5 s with `409 undo_expired`, which the app shows as "Couldn't undo, remove it from My reports".
