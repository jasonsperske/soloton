package dev.soloton.music

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.Checkable
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import dev.soloton.api.PlaylistRef
import dev.soloton.platform.BikeActivity
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Music settings for one playlist (in Roadwork, one environment): which folders it draws on and which
 * of their songs play, plus the global music root. Everything is in-app (no system document picker,
 * which the bike may not have) and every change is saved as it is made, to [MusicSettings.selections]
 * and [MusicSettings.folder]; a running [FolderMusicController] follows along.
 *
 * ```
 *  Music for <title>                   [Done] |  <folder name>
 *  +--------------------------------------+   |  14 of 20 songs          [All] [None]
 *  | Folder A        14 of 20    [Remove] |   |  [x] Song One
 *  | Folder B        missing     [Remove] |   |  [ ] Song Two
 *  +--------------------------------------+   |  [x] Song Three
 *  [Add folder] [Reset to default]            |  ...
 *  Music root: /sdcard/Music/soloton [Change] |
 * ```
 * The right pane shows the selected folder's songs, or the folder browser while adding a folder or
 * changing the root. Without a playlist ([intent] with null) only the music root can be changed.
 *
 * Open it with `startActivity(MusicSettingsActivity.intent(context, playlist))`.
 */
class MusicSettingsActivity : BikeActivity() {
    private enum class Mode { SONGS, ADD_FOLDER, ROOT }

    /** One line of the right-hand list: a song with a tick box, or a folder to open or add. */
    private class Row(
        val label: String,
        val detail: String? = null,
        val checked: Boolean? = null,
        val onClick: () -> Unit,
    )

    private lateinit var settings: MusicSettings
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "soloton-music-settings").apply { isDaemon = true }
    }
    private var loadGeneration = 0
    private var browseGeneration = 0

    private var playlist: PlaylistRef? = null
    private var selection: MusicSelection? = null
    private var resolved: ResolvedSelection? = null
    /** Song counts of the sub-folders of selected folders that hold no songs themselves, by path. */
    private var subfolderCounts: Map<String, Int> = emptyMap()
    private var selectedPath: String? = null
    private var mode = Mode.SONGS

    private var browseDir: File = File("/")
    private var browseFolders: List<Pair<File, Int>> = emptyList()
    private var browseUnreadable = false

    private lateinit var folderList: LinearLayout
    private lateinit var folderSection: View
    private lateinit var warningText: TextView
    private lateinit var permissionRow: LinearLayout
    private lateinit var rootText: TextView
    private lateinit var rightTitle: TextView
    private lateinit var rightDetail: TextView
    private lateinit var rightMessage: TextView
    private lateinit var pathRow: LinearLayout
    private lateinit var pathInput: EditText
    private lateinit var actionButtons: List<Button>
    private val rows = ArrayList<Row>()
    private lateinit var adapter: BaseAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = MusicSettings(this)
        playlist = intent.getStringExtra(EXTRA_PLAYLIST_ID)?.takeIf { it.isNotBlank() }?.let { id ->
            PlaylistRef(
                id = id,
                title = intent.getStringExtra(EXTRA_PLAYLIST_TITLE)?.trim()?.takeIf { it.isNotEmpty() } ?: id,
                defaultFolder = Playlist.normalize(intent.getStringExtra(EXTRA_DEFAULT_FOLDER)),
            )
        }
        setContentView(buildUi())
        if (playlist == null) {
            mode = Mode.ROOT
            browseTo(startingDir(settings.folder))
        }
        render()
        if (!MusicPermission.isGranted(this)) MusicPermission.request(this)
    }

    override fun onResume() {
        super.onResume()
        reload() // also picks up files copied over while the screen was in the background
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MusicPermission.REQUEST_CODE) {
            reload()
            if (mode != Mode.SONGS) browseTo(browseDir)
        }
    }

    // ---- the selection ----

    /** Reads the saved selection and the disk on the background thread, then redraws. */
    private fun reload(select: File? = null) {
        val ref = playlist
        if (ref == null) {
            render()
            return
        }
        val generation = ++loadGeneration
        val root = settings.folder
        worker.execute {
            val sel = SelectionResolver.effective(settings.selections, root, ref)
            val res = SelectionResolver.resolve(root, sel)
            val counts = HashMap<String, Int>()
            for (f in res.folders) {
                if (f.songs.isEmpty()) for (sub in f.subfolders) counts[sub.path] = MusicScanner.list(sub)?.songs?.size ?: 0
            }
            runOnUiThread {
                if (generation != loadGeneration || isDestroyed) return@runOnUiThread
                selection = sel
                resolved = res
                subfolderCounts = counts
                val wanted = select?.let { FolderPaths.normalized(it) } ?: selectedPath
                selectedPath = res.folders.map { FolderPaths.normalized(it.dir) }.let { paths ->
                    wanted?.takeIf { it in paths } ?: paths.firstOrNull()
                }
                render()
            }
        }
    }

    /** Saves an edited selection at once. [rescan] when the edit needs the disk read again (a folder was added). */
    private fun apply(edited: MusicSelection, rescan: Boolean, select: File? = null) {
        val ref = playlist ?: return
        if (edited != selection || settings.selections.saved(ref.id) == null) settings.selections.save(ref.id, edited)
        selection = edited
        if (rescan) {
            reload(select)
            return
        }
        // Ticks and removals change nothing on disk: rework what is already known.
        val root = settings.folder
        val known = resolved?.folders.orEmpty()
        resolved = ResolvedSelection(root, edited.folders.mapNotNull { f ->
            val dir = FolderPaths.normalized(FolderPaths.resolve(root, f.path))
            known.firstOrNull { FolderPaths.normalized(it.dir) == dir }
                ?.let { it.copy(folder = f, included = it.songs.filter { song -> song.name !in f.excluded }) }
        })
        if (resolved?.folders?.none { FolderPaths.normalized(it.dir) == selectedPath } == true) {
            selectedPath = resolved?.folders?.firstOrNull()?.let { FolderPaths.normalized(it.dir) }
        }
        render()
    }

    private fun addFolder(dir: File) {
        val current = selection ?: return
        mode = Mode.SONGS
        apply(current.add(settings.folder, dir), rescan = true, select = dir)
    }

    private fun resetToDefault() {
        val ref = playlist ?: return
        settings.selections.reset(ref.id)
        mode = Mode.SONGS
        selectedPath = null
        reload()
    }

    private fun useRoot(path: String) {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return
        settings.folder = File(trimmed).absolutePath
        if (playlist != null) mode = Mode.SONGS else browseTo(startingDir(settings.folder))
        reload()
    }

    private fun selectedFolder(): ResolvedFolder? =
        resolved?.folders?.firstOrNull { FolderPaths.normalized(it.dir) == selectedPath }

    // ---- the folder browser ----

    private fun browse(newMode: Mode) {
        mode = newMode
        browseTo(startingDir(settings.folder))
    }

    private fun browseTo(dir: File) {
        browseDir = dir
        browseFolders = emptyList()
        browseUnreadable = false
        val generation = ++browseGeneration
        render()
        worker.execute {
            val listing = MusicScanner.list(dir)
            val folders = listing?.subfolders.orEmpty().map { it to (MusicScanner.list(it)?.songs?.size ?: 0) }
            runOnUiThread {
                if (generation != browseGeneration || isDestroyed) return@runOnUiThread
                browseFolders = folders
                browseUnreadable = listing == null
                render()
            }
        }
    }

    /** The configured folder if it exists, else its nearest existing parent, else external storage. */
    @Suppress("DEPRECATION")
    private fun startingDir(folder: String): File {
        var f: File? = File(folder)
        while (f != null && !f.isDirectory) f = f.parentFile
        return f?.takeIf { it.path != "/" } ?: Environment.getExternalStorageDirectory()
    }

    // ---- drawing the state ----

    private fun render() {
        permissionRow.visibility = if (MusicPermission.isGranted(this)) View.GONE else View.VISIBLE
        rootText.text = getString(R.string.soloton_music_current_folder, settings.folder)
        renderFolders()
        when (mode) {
            Mode.SONGS -> renderSongs()
            Mode.ADD_FOLDER, Mode.ROOT -> renderBrowser()
        }
        adapter.notifyDataSetChanged()
    }

    private fun renderFolders() {
        val ref = playlist
        folderSection.visibility = if (ref == null) View.GONE else View.VISIBLE
        folderList.removeAllViews()
        val res = resolved
        if (ref == null || res == null) {
            warningText.visibility = View.GONE
            return
        }
        for (folder in res.folders) folderList.addView(folderRow(folder), matchWidth())
        val problem = res.problem(ref.title, MusicPermission.isGranted(this))
        warningText.text = problem?.message
        warningText.visibility = if (problem == null) View.GONE else View.VISIBLE
    }

    private fun folderRow(folder: ResolvedFolder): View {
        val selected = FolderPaths.normalized(folder.dir) == selectedPath && mode == Mode.SONGS
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = touch
            setPadding(dp(12), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = px(14f)
                setColor(if (selected) ROW_SELECTED else ROW)
            }
            contentDescription = getString(R.string.soloton_music_folder_row, folder.name)
            setOnClickListener {
                mode = Mode.SONGS
                selectedPath = FolderPaths.normalized(folder.dir)
                render()
            }
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(text(folder.name, 24f, bold = true).apply {
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            if (folder.missing) setTextColor(MISSING)
        })
        labels.addView(text(folderCount(folder), 21f).apply { setTextColor(if (folder.missing) MISSING else DIM) })
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button(getString(R.string.soloton_music_remove)) {
            selection?.let { apply(it.remove(settings.folder, folder.dir), rescan = false) }
        }, margins(left = 8))
        return row
    }

    private fun folderCount(folder: ResolvedFolder): String = when {
        folder.missing -> getString(R.string.soloton_music_folder_missing)
        else -> resources.getQuantityString(R.plurals.soloton_music_songs_of, folder.songs.size, folder.included.size, folder.songs.size)
    }

    private fun renderSongs() {
        pathRow.visibility = View.GONE
        rows.clear()
        val folder = selectedFolder()
        if (folder == null) {
            rightTitle.text = getString(R.string.soloton_music_songs)
            rightDetail.text = ""
            actions()
            message(getString(if (resolved == null) R.string.soloton_music_scanning else R.string.soloton_music_pick_folder))
            return
        }
        val current = selection
        val root = settings.folder
        rightTitle.text = folder.name
        rightDetail.text = folder.dir.path
        when {
            folder.missing -> {
                actions()
                message(getString(R.string.soloton_music_folder_missing_detail))
            }
            folder.songs.isNotEmpty() -> {
                rightDetail.text = resources.getQuantityString(R.plurals.soloton_music_songs_of, folder.songs.size, folder.included.size, folder.songs.size)
                actions(
                    getString(R.string.soloton_music_all) to { current?.let { apply(it.includeAll(root, folder.dir), rescan = false) } },
                    getString(R.string.soloton_music_none) to {
                        current?.let { apply(it.excludeAll(root, folder.dir, folder.songs.map { s -> s.name }), rescan = false) }
                    },
                )
                message(null)
                for (song in folder.songs) {
                    val included = song.name !in folder.folder.excluded
                    rows += Row(songTitle(song), checked = included) {
                        selection?.let { apply(it.setIncluded(settings.folder, folder.dir, song.name, !included), rescan = false) }
                    }
                }
            }
            folder.subfolders.isNotEmpty() -> {
                actions()
                message(getString(R.string.soloton_music_only_subfolders))
                for (sub in folder.subfolders) {
                    rows += Row(sub.name + "/", detail = songCount(subfolderCounts[sub.path] ?: 0)) { addFolder(sub) }
                }
            }
            else -> {
                actions()
                message(getString(R.string.soloton_music_no_songs_here))
            }
        }
    }

    private fun renderBrowser() {
        val root = mode == Mode.ROOT
        rightTitle.text = getString(if (root) R.string.soloton_music_browse else R.string.soloton_music_add_folder_title)
        rightDetail.text = browseDir.absolutePath
        pathRow.visibility = if (root) View.VISIBLE else View.GONE
        if (root && !pathInput.hasFocus()) pathInput.setText(settings.folder)
        val up = getString(R.string.soloton_music_up) to { browseDir.parentFile?.let { browseTo(it) } ?: Unit }
        val cancel = getString(R.string.soloton_music_cancel) to {
            mode = Mode.SONGS
            render()
        }
        if (root) {
            val use = getString(R.string.soloton_music_use_this) to { useRoot(browseDir.absolutePath) }
            if (playlist != null) actions(up, use, cancel) else actions(up, use)
        } else {
            actions(up, getString(R.string.soloton_music_add_this) to { addFolder(browseDir) }, cancel)
        }
        actionButtons[0].isEnabled = browseDir.parentFile != null
        message(
            when {
                browseUnreadable -> getString(R.string.soloton_music_cannot_read)
                browseFolders.isEmpty() -> getString(R.string.soloton_music_no_subfolders)
                else -> null
            },
        )
        rows.clear()
        for ((dir, count) in browseFolders) {
            rows += Row(dir.name + "/", detail = if (count > 0) songCount(count) else null) { browseTo(dir) }
        }
    }

    private fun songTitle(file: File): String = Playlist.titleFromFileName(file.nameWithoutExtension.ifBlank { file.name })

    private fun songCount(count: Int): String = resources.getQuantityString(R.plurals.soloton_music_song_count, count, count)

    private fun message(value: String?) {
        rightMessage.text = value.orEmpty()
        rightMessage.visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /** Labels and handlers for the buttons above the list; unused buttons are hidden. */
    private fun actions(vararg items: Pair<String, () -> Unit>) {
        actionButtons.forEachIndexed { i, b ->
            val item = items.getOrNull(i)
            b.visibility = if (item == null) View.GONE else View.VISIBLE
            b.isEnabled = true
            b.text = item?.first
            b.setOnClickListener { item?.second?.invoke() }
        }
    }

    // ---- views (plain Android views, sized for a 1920x1080 bike tablet) ----

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BACKGROUND)
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = playlist?.let { getString(R.string.soloton_music_title_for, it.title) } ?: getString(R.string.soloton_music_title)
        titleRow.addView(
            text(title, 30f, bold = true).apply {
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        titleRow.addView(button(getString(R.string.soloton_music_done)) { finish() })
        left.addView(titleRow, matchWidth())

        permissionRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        permissionRow.addView(text(getString(R.string.soloton_music_permission_needed), 22f).apply { setTextColor(WARNING) })
        permissionRow.addView(button(getString(R.string.soloton_music_allow)) { MusicPermission.request(this) })
        left.addView(permissionRow, margins(top = 8))

        // The playlist's folders: the list scrolls, the buttons under it stay in reach.
        val folders = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        folders.addView(text(getString(R.string.soloton_music_folders), 21f).apply { setTextColor(DIM) }, margins(top = 8))
        folderList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setSize(1, dp(6)) }
        }
        val listAndWarning = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listAndWarning.addView(folderList, matchWidth())
        warningText = text("", 21f).apply { setTextColor(WARNING) }
        listAndWarning.addView(warningText, margins(top = 8))
        folders.addView(ScrollView(this).apply { addView(listAndWarning) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val folderButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        folderButtons.addView(button(getString(R.string.soloton_music_add_folder)) { browse(Mode.ADD_FOLDER) })
        folderButtons.addView(button(getString(R.string.soloton_music_reset)) { resetToDefault() }, margins(left = 12))
        folders.addView(folderButtons, margins(top = 8))
        folderSection = folders
        left.addView(folders, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // The global music root: needed rarely, so it sits at the bottom in dimmer text.
        val rootRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        rootText = text("", 21f).apply {
            setTextColor(DIM)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.START
        }
        rootRow.addView(rootText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        rootRow.addView(button(getString(R.string.soloton_music_change_root)) { browse(Mode.ROOT) }, margins(left = 12))
        left.addView(rootRow, matchWidth().apply { topMargin = dp(8) })

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, 0, 0)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = touch
        }
        val headerText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rightTitle = text("", 26f, bold = true).apply {
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        rightDetail = text("", 21f).apply {
            setTextColor(DIM)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.START
        }
        headerText.addView(rightTitle)
        headerText.addView(rightDetail)
        header.addView(headerText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionButtons = List(3) { button("") {} }
        actionButtons.forEach { header.addView(it, margins(left = 12)) }
        right.addView(header, matchWidth())

        pathRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        pathInput = EditText(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(22f * TEXT_SCALE))
            setTextColor(Color.WHITE)
            setSingleLine(true)
            hint = getString(R.string.soloton_music_type_path)
            setHintTextColor(MISSING)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            minHeight = touch
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    useRoot(v.text.toString())
                    true
                } else {
                    false
                }
            }
        }
        pathRow.addView(pathInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pathRow.addView(button(getString(R.string.soloton_music_use_typed)) { useRoot(pathInput.text.toString()) }, margins(left = 12))
        right.addView(pathRow, matchWidth())

        rightMessage = text("", 22f).also { right.addView(it, margins(top = 8)) }

        adapter = object : BaseAdapter() {
            override fun getCount(): Int = rows.size
            override fun getItem(position: Int): Any = rows[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = (convertView as? LinearLayout) ?: rowView()
                val row = rows[position]
                val box = view.getChildAt(0) as TickBox
                box.visibility = if (row.checked == null) View.GONE else View.VISIBLE
                box.isChecked = row.checked == true
                (view.getChildAt(1) as TextView).apply {
                    text = row.label
                    setTextColor(if (row.checked == false) DIM else Color.WHITE)
                }
                (view.getChildAt(2) as TextView).apply {
                    text = row.detail.orEmpty()
                    visibility = if (row.detail == null) View.GONE else View.VISIBLE
                }
                view.setOnClickListener { row.onClick() }
                return view
            }
        }
        val list = ListView(this).apply {
            adapter = this@MusicSettingsActivity.adapter
            isFastScrollEnabled = false
        }
        right.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.25f))
        return root
    }

    /** A list row: a large tick box (hidden for folders), the name, and a count on the right. */
    private fun rowView(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = touch
        setPadding(dp(8), 0, dp(12), 0)
        // The row takes the tap; the box only shows the state.
        val boxSize = px(CHECKBOX_UNITS).toInt()
        addView(TickBox(context), LinearLayout.LayoutParams(boxSize, boxSize).apply { leftMargin = dp(8) })
        addView(
            text("", 24f).apply {
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(16), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(text("", 21f).apply { setTextColor(DIM) }, margins(left = 12))
    }

    /**
     * A tick box drawn to the size it is given. The stock CheckBox is a fixed 32 dp, far too small
     * for a screen at arm's length whatever density the tablet reports.
     */
    private class TickBox(context: Context) : View(context), Checkable {
        private var checked = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val tick = Path()

        override fun isChecked(): Boolean = checked
        override fun toggle() { isChecked = !checked }
        override fun setChecked(value: Boolean) {
            if (value == checked) return
            checked = value
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val size = minOf(width, height).toFloat()
            val stroke = size * 0.08f
            rect.set(stroke / 2f, stroke / 2f, size - stroke / 2f, size - stroke / 2f)
            paint.style = if (checked) Paint.Style.FILL else Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.color = if (checked) TICK_FILL else TICK_EMPTY
            canvas.drawRoundRect(rect, size * 0.16f, size * 0.16f, paint)
            if (!checked) return
            tick.rewind()
            tick.moveTo(size * 0.24f, size * 0.52f)
            tick.lineTo(size * 0.43f, size * 0.70f)
            tick.lineTo(size * 0.77f, size * 0.31f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size * 0.11f
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = Color.WHITE
            canvas.drawPath(tick, paint)
        }
    }

    private fun text(value: String, sp: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_PX, px(sp * TEXT_SCALE))
        setTextColor(Color.WHITE)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_PX, px(22f * TEXT_SCALE))
        isAllCaps = false
        minHeight = touch
        minimumHeight = touch
        minWidth = touch * 3 / 2
        minimumWidth = touch * 3 / 2
        setOnClickListener { onClick() }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun margins(left: Int = 0, top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { setMargins(dp(left), dp(top), 0, 0) }

    /**
     * Sizes scale with the screen's pixel height rather than its density, so the layout is
     * the same physical size on the bike whatever density the tablet reports (see docs/DEVICE.md).
     * One unit is one pixel on a 1080-line screen.
     */
    private val unit: Float by lazy {
        val m = resources.displayMetrics
        minOf(m.widthPixels, m.heightPixels) / 1080f
    }
    private val touch: Int by lazy { px(TOUCH_UNITS).toInt() }
    private fun px(units: Float): Float = units * unit
    private fun dp(value: Int): Int = px(value * 1.5f).toInt()

    companion object {
        /** Touch target height in units (pixels on a 1080-line screen); the plan's minimum is 96. */
        private const val TOUCH_UNITS = 112f
        /** Converts the sp-style sizes used above to units: 22 becomes 30 px on a 1080-line screen. */
        private const val TEXT_SCALE = 1.36f
        /** Side of a tick box, in units. */
        private const val CHECKBOX_UNITS = 64f
        private val BACKGROUND = Color.rgb(0x16, 0x1A, 0x22)
        private val ROW = Color.rgb(0x20, 0x26, 0x31)
        private val ROW_SELECTED = Color.rgb(0x2E, 0x45, 0x66)
        private val WARNING = Color.rgb(0xFF, 0xC1, 0x4D)
        private val DIM = Color.rgb(0xB4, 0xBC, 0xC8)
        private val MISSING = Color.rgb(0x7C, 0x84, 0x90)
        private val TICK_FILL = Color.rgb(0x29, 0x85, 0xDB)
        private val TICK_EMPTY = Color.rgb(0x9A, 0xA4, 0xB2)

        /** Extra: id of the playlist to edit ([PlaylistRef.id]). Without it only the music root is shown. */
        const val EXTRA_PLAYLIST_ID = "dev.soloton.music.extra.PLAYLIST_ID"
        /** Extra: what the playlist belongs to (for example an environment's title). */
        const val EXTRA_PLAYLIST_TITLE = "dev.soloton.music.extra.PLAYLIST_TITLE"
        /** Extra: the playlist's default folder under the music root, if it has one. */
        const val EXTRA_DEFAULT_FOLDER = "dev.soloton.music.extra.DEFAULT_FOLDER"

        /** @param playlist the playlist whose folders and songs are edited, or null for the music root only */
        @JvmOverloads
        fun intent(context: Context, playlist: PlaylistRef? = null): Intent =
            Intent(context, MusicSettingsActivity::class.java).apply {
                if (playlist != null) {
                    putExtra(EXTRA_PLAYLIST_ID, playlist.id)
                    putExtra(EXTRA_PLAYLIST_TITLE, playlist.title)
                    playlist.defaultFolder?.let { putExtra(EXTRA_DEFAULT_FOLDER, it) }
                }
            }
    }
}
