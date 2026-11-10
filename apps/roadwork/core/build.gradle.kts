plugins {
    id("soloton.gdx.core")
}

dependencies {
    // Shared interfaces (MusicController, PaceSource, PlatformActions); exposed to the launchers.
    api(project(":libs:api"))
}
