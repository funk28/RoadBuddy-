# Android app

This is the native Android application, built with Kotlin and Jetpack Compose.

## Modules

- `app`: application entry point and top-level navigation.
- `core/ui`: shared theme and bottom navigation.
- `feature/settings`: the Settings destination; its content is a placeholder for now.

## Local setup

Open this directory as a Gradle project in Android Studio with JDK 17 or later and Android SDK 37 installed. Secrets and machine-specific values belong in the ignored `secrets.properties` and `local.properties` files; do not commit credentials.

The app currently provides the navigation shell only. The Settings controls, persistence, permission handling, and detection behavior are not implemented yet.
