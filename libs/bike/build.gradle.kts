// SPDX-License-Identifier: GPL-2.0-or-later
// The Android half of the bike library: live cadence, power and resistance from the Peloton Bike
// tablet's own sensor services. BikeSensors binds the services and BikePaceSource hands a game its
// pace and resistance; a diagnostics screen comes with it. What the services say and what it means
// is in libs/bike-core, which this exposes. It depends on nothing else in this repository, so
// another game can take the two as they are. See README.md and docs/BIKE_SENSORS.md.
plugins {
    id("soloton.android.library")
    `maven-publish`
}

group = "dev.soloton"
version = providers.gradleProperty("bikeLibraryVersion").getOrElse("0.1.0")

android {
    namespace = "dev.soloton.bike"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "bike-android"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Soloton bike for Android")
                description.set("Reads cadence, power and resistance from the Peloton Bike tablet's sensor services.")
                url.set("https://github.com/jasonsperske/soloton")
                licenses {
                    license {
                        name.set("GPL-2.0-or-later")
                        url.set("https://www.gnu.org/licenses/old-licenses/gpl-2.0.html")
                    }
                }
            }
        }
    }
}

dependencies {
    api(project(":libs:bike-core"))

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

// What lets this be used outside this repository: the only module here it may depend on is bike-core.
val projectDependencies = provider {
    configurations["releaseRuntimeClasspath"].allDependencies.withType<ProjectDependency>().map { it.path }
}
val checkStandalone = tasks.register("checkStandalone") {
    description = "Fails if this module depends on a module of this repository other than bike-core."
    group = "verification"
    val found = projectDependencies
    doLast {
        val others = found.get() - ":libs:bike-core"
        check(others.isEmpty()) { "the bike library must stand alone, but depends on $others" }
    }
}
tasks.named("check") { dependsOn(checkStandalone) }
