pluginManagement {
    includeBuild("build-logic")
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

rootProject.name = "soloton"

// Shared libraries
include(":libs:api")
include(":libs:platform")
include(":libs:music")
include(":libs:bike")

// Apps: each app is apps/<name>/{core,android,desktop}
include(":apps:roadwork:core")
include(":apps:roadwork:android")
include(":apps:roadwork:desktop")
