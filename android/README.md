# Android app

The native Android app uses Kotlin and Jetpack Compose. The Hazards destination
is the launch screen and includes the map, filters, editable hazard cards, and
the shared bottom navigation.

## Hazards API

The Hazards screen uses contract-shaped sample JSON by default. To connect it to
the API, pass the API root as a Gradle property when building:

```powershell
.\gradlew.bat :app:assembleDebug -PhazardsApiBaseUrl=https://your-api.example/v1
```

The configured root must end in `/v1`; the app calls `GET /hazards` and
`PATCH /hazards/{id}`. The sample data is used only when this property is
omitted. Until location integration is available, API queries use the Purdue
coordinates from the API contract as their center. The app scaffold does not
yet provide the Google ID-token flow required by authenticated deployments.
