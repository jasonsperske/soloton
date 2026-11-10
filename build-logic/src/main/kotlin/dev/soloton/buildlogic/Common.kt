package dev.soloton.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/** Bytecode level for every module. The build needs JDK 17 or newer. */
internal val JAVA_VERSION = JavaVersion.VERSION_17

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.lib(alias: String): MinimalExternalModuleDependency =
    findLibrary(alias).orElseThrow { IllegalStateException("No library '$alias' in libs.versions.toml") }.get()

internal fun VersionCatalog.version(alias: String): String =
    findVersion(alias).orElseThrow { IllegalStateException("No version '$alias' in libs.versions.toml") }.requiredVersion

/** Kotlin compiles to the same JVM target as Java. */
internal fun Project.configureKotlinJvmTarget() {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.fromTarget(JAVA_VERSION.toString()))
    }
}

/** JUnit Platform for all JVM unit tests (core modules and Android local unit tests). */
internal fun Project.configureJUnit(configurationPrefix: String = "test") {
    val catalog = libs
    dependencies.add("${configurationPrefix}Implementation", dependencies.platform(catalog.lib("junit-bom")))
    dependencies.add("${configurationPrefix}Implementation", catalog.lib("junit-jupiter"))
    dependencies.add("${configurationPrefix}RuntimeOnly", catalog.lib("junit-platform-launcher"))
    dependencies.add("${configurationPrefix}Implementation", "org.jetbrains.kotlin:kotlin-test-junit5")
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

/** Shared Android settings for apps and libraries. */
internal fun Project.configureAndroidCommon(android: CommonExtension) {
    val catalog = libs
    android.apply {
        compileSdk = catalog.version("android-compileSdk").toInt()
        defaultConfig.minSdk = catalog.version("android-minSdk").toInt()
        compileOptions.sourceCompatibility = JAVA_VERSION
        compileOptions.targetCompatibility = JAVA_VERSION
        lint.apply {
            // Sideloaded apps: Play Store target-SDK deadlines do not apply.
            disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi", "GradleDependency", "AndroidGradlePluginVersion")
            abortOnError = true
            warningsAsErrors = false
        }
    }
    configureKotlinJvmTarget()
    configureJUnit()
}
