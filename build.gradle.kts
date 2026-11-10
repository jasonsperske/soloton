// Root project. All module configuration lives in the convention plugins in build-logic/.
// Loading them here (apply false) puts AGP and Kotlin on one shared classloader for every module.
plugins {
    id("soloton.android.app") apply false
    id("soloton.android.library") apply false
    id("soloton.kotlin.jvm") apply false
    id("soloton.gdx.core") apply false
    id("soloton.gdx.desktop") apply false
    id("soloton.gdx.android") apply false
}
