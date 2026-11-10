plugins {
    id("soloton.gdx.android")
}

android {
    namespace = "dev.soloton.roadwork.android"
    defaultConfig {
        applicationId = "dev.soloton.roadwork"
    }
}

dependencies {
    implementation(project(":apps:roadwork:core"))
    implementation(project(":libs:platform"))
}
