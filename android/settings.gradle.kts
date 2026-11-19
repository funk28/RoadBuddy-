pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "RoadBuddy"
include(
    ":app",
    ":core:model",
    ":core:network",
    ":core:data",
    ":core:ui",
    ":feature:settings",
    ":feature:hazards",
)
