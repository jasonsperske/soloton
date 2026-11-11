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
    private val appContext: Context = context.applicationContext
    internal val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    internal val selectionPrefs: SharedPreferences
        get() = appContext.getSharedPreferences(SELECTIONS_PREFS_NAME, Context.MODE_PRIVATE)

    /** Absolute path of the music root. Defaults to [defaultFolder]. */
    var folder: String
        get() = prefs.getString(KEY_FOLDER, null)?.takeIf { it.isNotBlank() } ?: defaultFolder()
        set(value) {
            prefs.edit().putString(KEY_FOLDER, value).apply()
        }

    /** Each playlist's chosen folders and songs. A running [FolderMusicController] follows changes. */
    val selections: SelectionStore = SelectionStore(PrefsBacking(selectionPrefs))

    /** Whether the storage permission has been requested once already (first-launch request). */
    var permissionAsked: Boolean
        get() = prefs.getBoolean(KEY_PERMISSION_ASKED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PERMISSION_ASKED, value).apply()
        }

    /** One preference per playlist id, holding [SelectionCodec] text. */
    private class PrefsBacking(private val prefs: SharedPreferences) : SelectionBacking {
        override fun read(id: String): String? = prefs.getString(id, null)
        override fun write(id: String, value: String?) {
            prefs.edit().apply { if (value == null) remove(id) else putString(id, value) }.apply()
        }
    }

    companion object {
        const val PREFS_NAME = "dev.soloton.music"
        /** Separate from [PREFS_NAME] so playlist ids cannot collide with setting names. */
        const val SELECTIONS_PREFS_NAME = "dev.soloton.music.selections"
        const val KEY_FOLDER = "folder"
        const val KEY_PERMISSION_ASKED = "permission_asked"

        /** `/sdcard/Music/soloton`, resolved through the platform's external storage path. */
        @Suppress("DEPRECATION")
        fun defaultFolder(): String = File(Environment.getExternalStorageDirectory(), "Music/soloton").absolutePath
    }
}
