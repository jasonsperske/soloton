// SPDX-License-Identifier: GPL-3.0-only
// Bike sensor library (WP6): live cadence, power and resistance from the Peloton Bike tablet's own
// sensor service, plus a cadence-driven PaceSource. The protocol decoding, pace mapping and the
// connection state machine have no Android imports and are unit tested on the JVM; BikeSensors
// wraps them around bindService. See README.md and docs/BIKE_SENSORS.md.
plugins {
    id("soloton.android.library")
}

android {
    namespace = "dev.soloton.bike"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    api(project(":libs:api"))
    implementation(project(":libs:platform"))

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
