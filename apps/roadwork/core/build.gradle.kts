plugins {
    id("soloton.gdx.core")
}

dependencies {
    // Shared interfaces (MusicController, PlatformActions, SettingsStore); exposed to the launchers.
    api(project(":libs:api"))
    // PaceSource and ResistanceSource: how the game reads the bike. Pure Kotlin, no Android.
    api(project(":libs:bike-core"))
}
