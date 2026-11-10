plugins {
    id("soloton.gdx.android")
}

android {
    namespace = "dev.soloton.roadwork.android"
    defaultConfig {
        applicationId = "dev.soloton.roadwork"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    implementation(project(":apps:roadwork:core"))
    implementation(project(":libs:platform"))
    implementation(project(":libs:music"))

    // Smoke test: ./gradlew :apps:roadwork:android:connectedDebugAndroidTest
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
