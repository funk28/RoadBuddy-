# Android app

This is the native Android application, built with Kotlin and Jetpack Compose.

## Modules

- `app`: application entry point and top-level navigation.
- `core/model`: shared drive settings model and defaults.
- `core/data`: DataStore-backed settings repository and reactive behavior gates.
- `core/ui`: shared theme and bottom navigation.
- `feature/settings`: the Settings destination.

## Local setup

Open this directory as a Gradle project in Android Studio with JDK 17 or later and Android SDK 37 installed. Secrets and machine-specific values belong in the ignored `secrets.properties` and `local.properties` files; do not commit credentials.

The settings switches persist locally with DataStore. Runtime features should collect the corresponding gates from `DriveSettingsRepository`; camera capture, upload, audible alert, and reroute implementations are not part of this starter app yet.
