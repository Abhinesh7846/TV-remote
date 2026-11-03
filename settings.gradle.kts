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

rootProject.name = "TVRemote"

// core: Android TV Remote v2 protocol in plain Kotlin/JVM (shared by app and cli).
// cli:  laptop test harness that drives a real TV with the same core code.
// app:  the Android phone app.
include(":core", ":cli", ":app")
