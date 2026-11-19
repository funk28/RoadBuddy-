plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Point the app at a backend with -PapiBaseUrl=http://127.0.0.1:5056/v1 (see README).
// Without it, detections go to the in-memory StubDetectionApi.
val apiBaseUrl = providers.gradleProperty("apiBaseUrl").getOrElse("")
// Sent as the bearer token by debug builds until Google sign-in exists.
val devAuthToken = providers.gradleProperty("devAuthToken").getOrElse("dev:demo")

android {
    namespace = "com.roadbuddy.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.roadbuddy.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
    }

    buildTypes {
        debug {
            buildConfigField("String", "DEV_AUTH_TOKEN", "\"$devAuthToken\"")
        }
        release {
            buildConfigField("String", "DEV_AUTH_TOKEN", "\"\"")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))

    debugImplementation(libs.androidx.compose.ui.tooling)
}
