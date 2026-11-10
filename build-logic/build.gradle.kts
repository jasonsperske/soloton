plugins {
    `kotlin-dsl`
}

group = "dev.soloton.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.android.gradlePlugin)
    implementation(libs.kotlin.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("kotlinJvm") {
            id = "soloton.kotlin.jvm"
            implementationClass = "dev.soloton.buildlogic.KotlinJvmConventionPlugin"
        }
        register("gdxCore") {
            id = "soloton.gdx.core"
            implementationClass = "dev.soloton.buildlogic.GdxCoreConventionPlugin"
        }
        register("gdxDesktop") {
            id = "soloton.gdx.desktop"
            implementationClass = "dev.soloton.buildlogic.GdxDesktopConventionPlugin"
        }
        register("androidLibrary") {
            id = "soloton.android.library"
            implementationClass = "dev.soloton.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidApp") {
            id = "soloton.android.app"
            implementationClass = "dev.soloton.buildlogic.AndroidAppConventionPlugin"
        }
        register("gdxAndroid") {
            id = "soloton.gdx.android"
            implementationClass = "dev.soloton.buildlogic.GdxAndroidConventionPlugin"
        }
    }
}
