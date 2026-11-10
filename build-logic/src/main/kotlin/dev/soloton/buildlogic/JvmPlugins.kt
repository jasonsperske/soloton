package dev.soloton.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaApplication
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.JavaExec
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

/** `soloton.kotlin.jvm`: a plain Kotlin/JVM module (no Android imports) with JUnit tests. */
class KotlinJvmConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JAVA_VERSION
            targetCompatibility = JAVA_VERSION
        }
        configureKotlinJvmTarget()
        configureJUnit()
    }
}

/** `soloton.gdx.core`: platform-free libGDX game code shared by the Android and desktop launchers. */
class GdxCoreConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply(KotlinJvmConventionPlugin::class.java)
        pluginManager.apply("java-library")
        dependencies.add("api", libs.lib("gdx"))
    }
}

/**
 * `soloton.gdx.desktop`: LWJGL3 desktop launcher. The module sets `application.mainClass`.
 * Run with `./gradlew :apps:<app>:desktop:run`, or `installDist` for a standalone start script.
 */
class GdxDesktopConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply(KotlinJvmConventionPlugin::class.java)
        pluginManager.apply("application")
        val gdx = libs.lib("gdx-platform")
        dependencies.add("implementation", libs.lib("gdx-backend-lwjgl3"))
        dependencies.add("implementation", "${gdx.module}:${gdx.versionConstraint.requiredVersion}:natives-desktop")
        extensions.configure<JavaApplication> {
            // Start script and distribution name, e.g. roadwork-desktop.
            applicationName = "${parent?.name ?: name}-desktop"
        }
        tasks.withType<JavaExec>().configureEach {
            // macOS needs GLFW on the main thread.
            if (System.getProperty("os.name").lowercase().contains("mac")) {
                jvmArgs("-XstartOnFirstThread")
            }
        }
    }
}
