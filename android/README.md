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

The settings switches persist locally with DataStore. The Map placeholder includes an explicit drive-session control; CameraX captures rear-camera frames only while a session is active, road hazard detection is enabled, and the app is in the foreground. No hazard classifier is integrated yet, so frames are discarded locally and never uploaded. Audible alerts and rerouting are also not implemented yet.
