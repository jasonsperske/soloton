package dev.soloton.api

/** Things the shared game code asks the host platform to do. Called from the render thread. */
interface PlatformActions {
    /**
     * Show the music settings screen for [playlist]: its folders and which of their songs play.
     * No-op where unsupported.
     */
    fun openMusicSettings(playlist: PlaylistRef)
}

object NoPlatformActions : PlatformActions {
    override fun openMusicSettings(playlist: PlaylistRef) {}
}
