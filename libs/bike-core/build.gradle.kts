// SPDX-License-Identifier: GPL-3.0-only
// The platform-free half of the bike library: what the bike's sensor services say and what it
// means. Readings, the two services' wire formats, the connection state machine, and the mapping
// from cadence and resistance to the PaceSource and ResistanceSource a game reads. Pure Kotlin, no
// Android and no other module of this repository, so any JVM game code can depend on it.
// The Android half, which talks to the services, is libs/bike. See libs/bike/README.md.
plugins {
    id("soloton.kotlin.jvm")
    `java-library`
    `maven-publish`
}

group = "dev.soloton"
version = providers.gradleProperty("bikeLibraryVersion").getOrElse("0.1.0")

java {
    withSourcesJar()
}

// The licence travels with the jar.
tasks.jar {
    from(rootProject.file("LICENSE")) { into("META-INF") }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "bike-core"
            from(components["java"])
            pom {
                name.set("Soloton bike core")
                description.set("Readings, wire formats and pace and resistance mapping for the Peloton Bike's sensor services. Pure Kotlin.")
                url.set("https://github.com/jasonsperske/soloton")
                licenses {
                    license {
                        name.set("GPL-3.0-only")
                        url.set("https://www.gnu.org/licenses/gpl-3.0.html")
                    }
                }
            }
        }
    }
}

// What lets this be used outside this repository: it may not depend on any other module here.
val projectDependencies = provider {
    configurations["runtimeClasspath"].allDependencies.withType<ProjectDependency>().map { it.path }
}
val checkStandalone = tasks.register("checkStandalone") {
    description = "Fails if this module depends on another module of this repository."
    group = "verification"
    val found = projectDependencies
    doLast {
        check(found.get().isEmpty()) { "bike-core must stand alone, but depends on ${found.get()}" }
    }
}
tasks.named("check") { dependsOn(checkStandalone) }
