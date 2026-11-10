package dev.soloton.api

/** Things the shared game code asks the host platform to do. Called from the render thread. */
interface PlatformActions {
    /** Show the music settings screen (folder picker). No-op where unsupported. */
    fun openMusicSettings()
}

object NoPlatformActions : PlatformActions {
    override fun openMusicSettings() {}
}
