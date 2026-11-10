package dev.soloton.music

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import dev.soloton.platform.BikeActivity
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Music folder settings: the current folder and its track count, a typed path, and an in-app folder
 * browser (no system document picker, which the bike may not have). Saving writes [MusicSettings.folder];
 * a running [FolderMusicController] picks the change up and restarts playback.
 *
 * Open it with `startActivity(MusicSettingsActivity.intent(context))`.
 */
class MusicSettingsActivity : BikeActivity() {
    private lateinit var settings: MusicSettings
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "soloton-music-settings").apply { isDaemon = true }
    }
    private var countGeneration = 0

    private lateinit var folderText: TextView
    private lateinit var countText: TextView
    private lateinit var permissionRow: LinearLayout
    private lateinit var pathInput: EditText
    private lateinit var browseText: TextView
    private lateinit var browseMessage: TextView
    private lateinit var upButton: Button
    private lateinit var adapter: ArrayAdapter<String>

    private var browseDir: File = File("/")
    private val subfolders = ArrayList<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = MusicSettings(this)
        setContentView(buildUi())
        browseTo(startingDir(settings.folder))
        showCurrentFolder()
        if (!MusicPermission.isGranted(this)) MusicPermission.request(this)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MusicPermission.REQUEST_CODE) {
            browseTo(browseDir)
            showCurrentFolder()
        }
    }

    private fun useFolder(path: String) {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return
        settings.folder = File(trimmed).absolutePath
        showCurrentFolder()
    }

    private fun showCurrentFolder() {
        val folder = settings.folder
        folderText.text = getString(R.string.soloton_music_current_folder, folder)
        pathInput.setText(folder)
        permissionRow.visibility = if (MusicPermission.isGranted(this)) View.GONE else View.VISIBLE
        countText.text = getString(R.string.soloton_music_scanning)
        val generation = ++countGeneration
        worker.execute {
            val message = when (val result = MusicScanner.scan(File(folder))) {
                is ScanResult.Found -> resources.getQuantityString(
                    R.plurals.soloton_music_tracks_found, result.files.size, result.files.size,
                )
                else -> if (!MusicPermission.isGranted(this)) {
                    MusicMessages.permission(folder).message
                } else {
                    MusicMessages.noMusic(folder, result).message
                }
            }
            runOnUiThread { if (generation == countGeneration && !isDestroyed) countText.text = message }
        }
    }

    private fun browseTo(dir: File) {
        browseDir = dir
        browseText.text = dir.absolutePath
        upButton.isEnabled = dir.parentFile != null
        subfolders.clear()
        val children = try {
            dir.listFiles()
        } catch (e: SecurityException) {
            null
        }
        if (children == null) {
            browseMessage.text = getString(R.string.soloton_music_cannot_read)
        } else {
            children.filter { it.isDirectory && !it.name.startsWith(".") }
                .sortedBy { it.name.lowercase() }
                .let { subfolders.addAll(it) }
            browseMessage.text = if (subfolders.isEmpty()) getString(R.string.soloton_music_no_subfolders) else ""
        }
        browseMessage.visibility = if (browseMessage.text.isEmpty()) View.GONE else View.VISIBLE
        adapter.clear()
        adapter.addAll(subfolders.map { it.name + "/" })
    }

    /** The configured folder if it exists, else its nearest existing parent, else external storage. */
    @Suppress("DEPRECATION")
    private fun startingDir(folder: String): File {
        var f: File? = File(folder)
        while (f != null && !f.isDirectory) f = f.parentFile
        return f?.takeIf { it.path != "/" } ?: Environment.getExternalStorageDirectory()
    }

    // ---- views (plain Android views, sized for a 1920x1080 bike tablet) ----

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BACKGROUND)
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }

        // Scrolls, so nothing is out of reach whatever the screen density turns out to be.
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(text(getString(R.string.soloton_music_title), 34f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(button(getString(R.string.soloton_music_done)) { finish() })
        left.addView(titleRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        folderText = text("", 22f).also { left.addView(it) }
        countText = text("", 22f).also { left.addView(it, margins(top = 8)) }

        permissionRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        permissionRow.addView(text(getString(R.string.soloton_music_permission_needed), 22f).apply { setTextColor(WARNING) })
        permissionRow.addView(button(getString(R.string.soloton_music_allow)) { MusicPermission.request(this) })
        left.addView(permissionRow, margins(top = 12))

        left.addView(text(getString(R.string.soloton_music_type_path), 20f), margins(top = 16))
        pathInput = EditText(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Color.WHITE)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            minHeight = TOUCH
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    useFolder(v.text.toString())
                    browseTo(startingDir(settings.folder))
                    true
                } else {
                    false
                }
            }
        }
        left.addView(pathInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        left.addView(button(getString(R.string.soloton_music_use_typed)) {
            useFolder(pathInput.text.toString())
            browseTo(startingDir(settings.folder))
        })
        val leftScroll = ScrollView(this).apply { addView(left) }

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, 0, 0)
        }
        right.addView(text(getString(R.string.soloton_music_browse), 26f, bold = true))
        browseText = text("", 20f).also { right.addView(it) }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        upButton = button(getString(R.string.soloton_music_up)) { browseDir.parentFile?.let { browseTo(it) } }
        actions.addView(upButton)
        actions.addView(button(getString(R.string.soloton_music_use_this)) { useFolder(browseDir.absolutePath) }, margins(left = 16))
        right.addView(actions)
        browseMessage = text("", 22f).also { right.addView(it, margins(top = 8)) }

        adapter = object : ArrayAdapter<String>(this, 0, ArrayList()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = (convertView as? TextView) ?: text("", 26f).apply {
                    minHeight = TOUCH
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), 0, dp(16), 0)
                }
                tv.text = getItem(position)
                return tv
            }
        }
        val list = ListView(this).apply {
            adapter = this@MusicSettingsActivity.adapter
            onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                subfolders.getOrNull(position)?.let { browseTo(it) }
            }
        }
        right.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(leftScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.3f))
        return root
    }

    private fun text(value: String, sp: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(Color.WHITE)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        isAllCaps = false
        minHeight = TOUCH
        minimumHeight = TOUCH
        minWidth = TOUCH * 2
        minimumWidth = TOUCH * 2
        setOnClickListener { onClick() }
    }

    private fun margins(left: Int = 0, top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { setMargins(dp(left), dp(top), 0, 0) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** Minimum touch target in pixels (plan: 96 px). */
        private const val TOUCH = 96
        private val BACKGROUND = Color.rgb(0x16, 0x1A, 0x22)
        private val WARNING = Color.rgb(0xFF, 0xC1, 0x4D)

        fun intent(context: Context): Intent = Intent(context, MusicSettingsActivity::class.java)
    }
}
