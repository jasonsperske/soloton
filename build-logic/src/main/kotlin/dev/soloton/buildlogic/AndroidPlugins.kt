package dev.soloton.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

/** `soloton.android.library`: an Android library (namespace set by the module). */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            configureAndroidCommon(this)
        }
    }
}

/**
 * `soloton.android.app`: an installable APK.
 *
 * - versionCode from env SOLOTON_VERSION_CODE (default 1), versionName from SOLOTON_VERSION_NAME
 *   (default 0.1.0-dev).
 * - release is signed from SOLOTON_KEYSTORE_B64 / SOLOTON_KEYSTORE_PASSWORD / SOLOTON_KEY_ALIAS /
 *   SOLOTON_KEY_PASSWORD. If any is missing, release falls back to the debug key with a warning.
 *
 * The module sets `namespace` and `defaultConfig.applicationId` (dev.soloton.<app>).
 */
class AndroidAppConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.android.application")

        fun env(name: String) = providers.environmentVariable(name).map { it.trim() }.filter { it.isNotEmpty() }
        val keystoreB64 = env("SOLOTON_KEYSTORE_B64")
        val storePassword = env("SOLOTON_KEYSTORE_PASSWORD").orNull
        val keyAlias = env("SOLOTON_KEY_ALIAS").orNull
        val keyPassword = env("SOLOTON_KEY_PASSWORD").orNull
        val haveSigning = keystoreB64.isPresent && storePassword != null && keyAlias != null && keyPassword != null
        val keystoreFile = layout.buildDirectory.file("signing/release.jks")

        val decodeKeystore = tasks.register<DecodeKeystoreTask>("decodeReleaseKeystore") {
            description = "Decodes SOLOTON_KEYSTORE_B64 into the build directory for release signing."
            keystoreBase64.set(keystoreB64)
            outputFile.set(keystoreFile)
            onlyIf { keystoreBase64.isPresent }
        }

        extensions.configure<ApplicationExtension> {
            configureAndroidCommon(this)
            defaultConfig {
                targetSdk = libs.version("android-targetSdk").toInt()
                versionCode = env("SOLOTON_VERSION_CODE").map { it.toInt() }.getOrElse(1)
                versionName = env("SOLOTON_VERSION_NAME").getOrElse("0.1.0-dev")
            }
            buildTypes.getByName("release") {
                isMinifyEnabled = false
                signingConfig = if (haveSigning) {
                    signingConfigs.create("release") {
                        this.storeFile = keystoreFile.get().asFile
                        this.storePassword = storePassword
                        this.keyAlias = keyAlias
                        this.keyPassword = keyPassword
                    }
                } else {
                    signingConfigs.getByName("debug")
                }
            }
        }

        if (haveSigning) {
            // Anything that reads the keystore must run after it is decoded.
            tasks.configureEach {
                if (name == "validateSigningRelease" || name == "packageRelease" || name == "signReleaseBundle") {
                    dependsOn(decodeKeystore)
                }
            }
        } else {
            val warning = "Soloton: release signing secrets (SOLOTON_KEYSTORE_B64, SOLOTON_KEYSTORE_PASSWORD, " +
                "SOLOTON_KEY_ALIAS, SOLOTON_KEY_PASSWORD) are not all set; release APK of ${path} " +
                "is signed with the DEBUG key."
            logger.warn("WARNING: $warning")
            tasks.configureEach {
                if (name == "packageRelease") {
                    doFirst { logger.warn("WARNING: $warning") }
                }
            }
        }
    }
}

/**
 * `soloton.gdx.android`: `soloton.android.app` plus the libGDX Android backend and its native
 * libraries for armeabi-v7a, arm64-v8a and x86_64 (emulators), packed into one APK.
 */
class GdxAndroidConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply(AndroidAppConventionPlugin::class.java)
        dependencies.add("implementation", libs.lib("gdx-backend-android"))

        val gdx = libs.lib("gdx-platform")
        val natives = configurations.create("gdxNatives") {
            isCanBeConsumed = false
            isCanBeResolved = true
            isTransitive = false
        }
        GDX_ABIS.forEach { abi ->
            dependencies.add(natives.name, "${gdx.module}:${gdx.versionConstraint.requiredVersion}:natives-$abi")
        }
        val extract = tasks.register<ExtractGdxNativesTask>("extractGdxNatives") {
            description = "Unpacks libGDX .so files into a jniLibs layout."
            nativeJars.from(natives)
            outputDir.set(layout.buildDirectory.dir("generated/gdxNatives"))
        }
        extensions.configure<ApplicationAndroidComponentsExtension> {
            onVariants { variant ->
                variant.sources.jniLibs?.addGeneratedSourceDirectory(extract, ExtractGdxNativesTask::outputDir)
            }
        }
        extensions.configure<ApplicationExtension> {
            // libGDX ships its .so files already stripped; skip AGP's strip step (needs an NDK).
            packaging.jniLibs.keepDebugSymbols += "**/libgdx*.so"
        }
    }

    companion object {
        val GDX_ABIS = listOf("armeabi-v7a", "arm64-v8a", "x86_64")
    }
}
