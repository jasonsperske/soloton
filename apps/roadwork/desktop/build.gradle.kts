plugins {
    id("soloton.gdx.desktop")
}

dependencies {
    implementation(project(":apps:roadwork:core"))
}

application {
    mainClass.set("dev.soloton.roadwork.desktop.DesktopLauncherKt")
}

// A git-ignored .env at the repo root may name a local music folder (see .env.example). `run` hands
// it to the demo music controller so the picker and HUD show real names and counts. A value already
// in the environment wins. Only the `run` task sees it: screenshots.sh always runs without it.
val dotenvMusicDir = providers.fileContents(rootProject.layout.projectDirectory.file(".env")).asText.map { text ->
    text.lineSequence()
        .map { it.trim().removePrefix("export ").trim() }
        .filter { it.startsWith("SOLOTON_MUSIC_DIR=") }
        .map { it.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'") }
        .lastOrNull().orEmpty()
}
tasks.named<JavaExec>("run") {
    val fromEnvironment = providers.environmentVariable("SOLOTON_MUSIC_DIR").orNull
    val dir = fromEnvironment?.takeIf { it.isNotBlank() } ?: dotenvMusicDir.orNull
    if (!dir.isNullOrBlank()) environment("SOLOTON_MUSIC_DIR", dir)
}
