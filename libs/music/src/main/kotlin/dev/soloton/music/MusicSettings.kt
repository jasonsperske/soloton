package dev.soloton.music

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import java.io.File

/**
 * Persistent music settings in `SharedPreferences`. Any [FolderMusicController] in the same process
 * listens for [folder] changes, so writing it here (as [MusicSettingsActivity] does) switches the music.
 */
class MusicSettings(context: Context) {
    internal val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Absolute path of the music folder. Defaults to [defaultFolder]. */
    var folder: String
        get() = prefs.getString(KEY_FOLDER, null)?.takeIf { it.isNotBlank() } ?: defaultFolder()
        set(value) {
            prefs.edit().putString(KEY_FOLDER, value).apply()
        }

    /** Whether the storage permission has been requested once already (first-launch request). */
    var permissionAsked: Boolean
        get() = prefs.getBoolean(KEY_PERMISSION_ASKED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PERMISSION_ASKED, value).apply()
        }

    companion object {
        const val PREFS_NAME = "dev.soloton.music"
        const val KEY_FOLDER = "folder"
        const val KEY_PERMISSION_ASKED = "permission_asked"

        /** `/sdcard/Music/soloton`, resolved through the platform's external storage path. */
        @Suppress("DEPRECATION")
        fun defaultFolder(): String = File(Environment.getExternalStorageDirectory(), "Music/soloton").absolutePath
    }
}
