package dev.soloton.music

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * The storage permission music needs. On the bike (API 24) that is `READ_EXTERNAL_STORAGE`, a runtime
 * permission; on API 33+ it is `READ_MEDIA_AUDIO`.
 */
object MusicPermission {
    /** Request code used for [request]; results arrive in the Activity's `onRequestPermissionsResult`. */
    const val REQUEST_CODE: Int = 0x5017

    val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun isGranted(context: Context): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Shows the system permission dialog. No-op if already granted. */
    fun request(activity: Activity) {
        if (isGranted(activity)) return
        MusicSettings(activity).permissionAsked = true
        activity.requestPermissions(arrayOf(permission), REQUEST_CODE)
    }

    /**
     * Asks once, on the first launch only, so a "deny" is not nagged about on every start. Afterwards the
     * music settings screen asks again. @return true if the dialog was shown.
     */
    fun requestOnFirstLaunch(activity: Activity): Boolean {
        if (isGranted(activity) || MusicSettings(activity).permissionAsked) return false
        request(activity)
        return true
    }
}
