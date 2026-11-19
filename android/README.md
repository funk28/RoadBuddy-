# Android app

This is the native Android application, built with Kotlin and Jetpack Compose.

## Modules

- `app`: application entry point, top-level navigation, and the Map tab's drive session.
- `core/model`: shared models (drive settings, coordinates, detections).
- `core/network`: Android side of the API contract (`DetectionApi`), with an HTTP client and an in-memory stub.
- `core/data`: DataStore-backed settings repository, reactive behavior gates, and the navigation/detection logic (`navigation/`).
- `core/ui`: shared theme and bottom navigation.
- `feature/hazards`: the Hazards destination and its sample/HTTP repositories.
- `feature/reports`: the Reports list and detail screens, currently backed by contract-shaped stub JSON.
- `feature/settings`: the Settings destination.

## Local setup

Open this directory as a Gradle project in Android Studio with JDK 17 or later and Android SDK 37 installed. Secrets and machine-specific values belong in the ignored `secrets.properties` and `local.properties` files; do not commit credentials.

The settings switches persist locally with DataStore. Audible alerts and rerouting are not implemented yet.

## Map tab: the drive session

"Start drive session" drives the mocked campus route with simulated GPS. While it runs, **Report pothole (mock detection)** stands in for the on-device detector: it uploads a bundled pothole photo, tagged with the car's position, to `POST /v1/detections`. The "New hazard reported" card shows the severity the server returns and an Undo button for 5 s. Reports that can't be sent wait on the phone and are retried. Reaching the end of the route ends the session.

Reports only go out while **Road hazard detection** is on in Settings. The rear-camera preview (CameraX) runs during a session when detection is on and camera access is allowed, but its frames aren't analyzed yet, so they are discarded and never uploaded. Camera access is optional for the mock detection.

By default the app talks to the in-memory `StubDetectionApi`, so this works with no server. To use the Flask backend, see [Running against the Flask backend](#running-against-the-flask-backend).

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
- **Detection:** `reportPothole()` stands in for the YOLO model. It uploads one of the bundled mock pothole images (`core/data/src/main/resources/com/roadbuddy/core/data/navigation/mock_pothole_*.jpg`), and `GpsExif` stamps EXIF GPS into it for the car's current position, heading and capture time, matching the `meta` JSON. To use real photos, replace those files.

The calls themselves are real: `reportPothole()` sends `POST /v1/detections` and `undo()` sends `DELETE /v1/detections/{id}` within the 5 s window. Reports that fail to send stay on the phone and are retried with the same `client_detection_id`. Use `StubDetectionApi` to demo without a server, or `HttpDetectionApi` to talk to the Flask backend (see below).

`RoadBuddyApp` creates one `NavigationSession` for the whole app, using `createDetectionApi(BuildConfig.API_BASE_URL, BuildConfig.DEV_AUTH_TOKEN)`, so a drive keeps going while another tab is open. `DriveSessionScreen` renders its state.

### Running against the Flask backend

1. Start the backend as described in `backend/README.md`, with `DEV_AUTH=true`. Every `/v1` request needs `Authorization: Bearer ...`; until Google sign-in exists, dev mode accepts any `dev:<name>` token.
2. Build the app pointed at it:

   ```sh
   adb reverse tcp:5056 tcp:5056
   ./gradlew :app:installDebug -PapiBaseUrl=http://127.0.0.1:5056/v1
   ```

   `adb reverse` makes the emulator's `127.0.0.1:5056` reach port 5056 on your computer. `http://10.0.2.2:<port>/v1` also reaches the host from the emulator, but it is often blocked by a firewall. In Android Studio, put `apiBaseUrl=http://127.0.0.1:5056/v1` in `~/.gradle/gradle.properties` instead of passing `-P`.
3. Debug builds send `Bearer dev:demo`. Pass `-PdevAuthToken=dev:<name>` to report as someone else. Release builds send no dev token.

Debug builds allow plain `http://` to `10.0.2.2`, `127.0.0.1` and `localhost` only (`app/src/debug/res/xml/network_security_config.xml`). Release builds allow no cleartext.

Without a token every call fails with `401 unauthorized`, and the app shows "Couldn't report hazard". The backend refuses Undo after 5 s with `409 undo_expired`, which the app shows as "Couldn't undo, remove it from My reports".

## Hazards API

The Hazards destination includes a map, filters, editable hazard cards, and the
shared bottom navigation. It uses contract-shaped sample data by default. To
connect it to the API, use the same `apiBaseUrl` Gradle property as the rest of
the app:

```powershell
.\gradlew.bat :app:assembleDebug -PapiBaseUrl=https://your-api.example/v1
```

The configured root must end in `/v1`; the app calls `GET /hazards` and
`PATCH /hazards/{id}`. The sample data is used when this property is omitted.
Debug builds send the configured `DEV_AUTH_TOKEN` as a bearer token. As with
detection reporting, production authentication still needs Google sign-in.
Hazards queries currently use the Purdue coordinates from the API contract
until location integration is available.
