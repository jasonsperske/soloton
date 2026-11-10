plugins {
    id("soloton.gdx.desktop")
}

dependencies {
    implementation(project(":apps:roadwork:core"))
}

application {
    mainClass.set("dev.soloton.roadwork.desktop.DesktopLauncherKt")
}
