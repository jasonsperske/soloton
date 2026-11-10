// Music player library (WP4): shuffled playback of audio files from a folder on the device.
// Pure logic (scan, shuffle bag, failure cap, session state) has no Android imports and is unit tested
// on the JVM; FolderMusicController wraps it around Media3 ExoPlayer.
plugins {
    id("soloton.android.library")
}

android {
    namespace = "dev.soloton.music"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    api(project(":libs:api"))
    implementation(project(":libs:platform"))
    implementation(libs.media3.exoplayer)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
