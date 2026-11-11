package dev.soloton.music

import dev.soloton.api.MusicState
import dev.soloton.api.PlaylistRef
import dev.soloton.api.PlaylistSummary
import java.io.File

/**
 * One folder a playlist draws on.
 *
 * @param path the folder, relative to the music root when it is inside the root (so the selection
 *   survives the root being moved; [FolderPaths.ROOT] is the root itself), otherwise absolute
 * @param excluded names of files in the folder that are unticked. Files are included unless listed,
 *   so songs copied in later play without a visit to settings; names of files that have gone are ignored
 */
data class SelectedFolder(val path: String, val excluded: Set<String> = emptySet())

/** The folders and songs chosen for one playlist. Immutable; the editing functions return a new selection. Pure JVM. */
data class MusicSelection(val folders: List<SelectedFolder>) {
    /** Adds the folder at the end. A folder that is already present (as [dir] under [root]) is not added twice. */
    fun add(root: String, dir: File): MusicSelection {
        if (indexOf(root, dir) >= 0) return this
        return MusicSelection(folders + SelectedFolder(FolderPaths.toStored(root, dir)))
    }

    fun remove(root: String, dir: File): MusicSelection {
        val i = indexOf(root, dir)
        return if (i < 0) this else MusicSelection(folders.filterIndexed { index, _ -> index != i })
    }

    /** Ticks ([included] true) or unticks one song of a folder. */
    fun setIncluded(root: String, dir: File, fileName: String, included: Boolean): MusicSelection =
        edit(root, dir) { if (included) it - fileName else it + fileName }

    /** Ticks every song of a folder. */
    fun includeAll(root: String, dir: File): MusicSelection = edit(root, dir) { emptySet() }

    /** Unticks the songs with these file names (for "None": every song now in the folder). */
    fun excludeAll(root: String, dir: File, fileNames: Collection<String>): MusicSelection =
        edit(root, dir) { it + fileNames }

    /** Position of the folder that is [dir] under [root], or -1. */
    fun indexOf(root: String, dir: File): Int {
        val wanted = FolderPaths.normalized(dir)
        return folders.indexOfFirst { FolderPaths.normalized(FolderPaths.resolve(root, it.path)) == wanted }
    }

    private fun edit(root: String, dir: File, change: (Set<String>) -> Set<String>): MusicSelection {
        val i = indexOf(root, dir)
        if (i < 0) return this
        return MusicSelection(folders.mapIndexed { index, f -> if (index == i) f.copy(excluded = change(f.excluded)) else f })
    }

    companion object {
        /** What plays before the user has chosen: the playlist's default folder, every song. */
        fun default(defaultFolder: String?): MusicSelection =
            MusicSelection(listOf(SelectedFolder(Playlist.normalize(defaultFolder) ?: FolderPaths.ROOT)))
    }
}

/** How a selected folder is written down and found again. Names are used as they are, never as patterns. */
object FolderPaths {
    /** The stored path of the music root itself. */
    const val ROOT = "."

    /** Relative to [root] when [dir] is the root or inside it, otherwise absolute. */
    fun toStored(root: String, dir: File): String {
        val r = normalized(File(root))
        val d = normalized(dir)
        if (d == r) return ROOT
        val prefix = if (r.endsWith("/")) r else "$r/"
        return if (d.startsWith(prefix)) d.substring(prefix.length) else d
    }

    fun resolve(root: String, stored: String): File = when {
        stored == ROOT || stored.isEmpty() -> File(root)
        stored.startsWith("/") -> File(stored)
        else -> File(root, stored)
    }

    /**
     * Absolute, without `.`, `..` or doubled and trailing slashes. Does not touch the disk, so symlinks
     * are kept. Done by hand: `java.nio.file` does not exist on the bike's Android 7.0.
     */
    fun normalized(file: File): String {
        val parts = ArrayList<String>()
        for (part in file.absolutePath.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += part
            }
        }
        return parts.joinToString("/", prefix = "/")
    }
}

/** Where selections are kept: one string per playlist id. Android backs it with `SharedPreferences`. */
interface SelectionBacking {
    fun read(id: String): String?

    /** null removes the entry. */
    fun write(id: String, value: String?)
}

/** Selections kept in memory, for tests and platforms without storage. */
class MemorySelectionBacking : SelectionBacking {
    val values = HashMap<String, String>()
    override fun read(id: String): String? = values[id]
    override fun write(id: String, value: String?) {
        if (value == null) values.remove(id) else values[id] = value
    }
}

/** Saved selections, keyed by playlist id (in Roadwork, the environment id). */
class SelectionStore(private val backing: SelectionBacking) {
    /** The saved selection, or null if the user has not chosen (or the entry cannot be read). */
    fun saved(id: String): MusicSelection? = backing.read(id)?.let { SelectionCodec.decode(it) }

    /** The saved selection, or the default folder with every song. */
    fun load(playlist: PlaylistRef): MusicSelection = saved(playlist.id) ?: MusicSelection.default(playlist.defaultFolder)

    fun save(id: String, selection: MusicSelection) = backing.write(id, SelectionCodec.encode(selection))

    /** Forgets the saved selection, so the playlist is its default folder again. */
    fun reset(id: String) = backing.write(id, null)
}

/**
 * A selection as a small JSON document: `{"folders":[{"path":"Some Game","excluded":["02 - Song.mp3"]}]}`.
 * Hand-written so that it runs in plain JVM unit tests (Android's `org.json` does not).
 */
object SelectionCodec {
    fun encode(selection: MusicSelection): String = buildString {
        append("{\"folders\":[")
        selection.folders.forEachIndexed { i, f ->
            if (i > 0) append(',')
            append("{\"path\":")
            string(f.path)
            append(",\"excluded\":[")
            f.excluded.sorted().forEachIndexed { j, name ->
                if (j > 0) append(',')
                string(name)
            }
            append("]}")
        }
        append("]}")
    }

    /** null if [text] is not a selection this version wrote. */
    fun decode(text: String): MusicSelection? = try {
        val root = Parser(text).document() as? Map<*, *>
        val folders = root?.get("folders") as? List<*>
        folders?.map { entry ->
            val o = entry as Map<*, *>
            val excluded = (o["excluded"] as? List<*> ?: emptyList<Any?>()).map { it as String }
            SelectedFolder(o["path"] as String, excluded.toSet())
        }?.let { MusicSelection(it) }
    } catch (e: RuntimeException) {
        null
    }

    private fun StringBuilder.string(value: String) {
        append('"')
        for (c in value) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> append(c)
            }
        }
        append('"')
    }

    /** Objects, arrays and strings: all a selection needs. */
    private class Parser(private val s: String) {
        private var i = 0

        fun document(): Any {
            val v = value()
            skipSpace()
            require(i == s.length) { "trailing text" }
            return v
        }

        private fun value(): Any {
            skipSpace()
            return when (peek()) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                else -> throw IllegalArgumentException("unexpected '${peek()}' at $i")
            }
        }

        private fun obj(): Map<String, Any> {
            val out = LinkedHashMap<String, Any>()
            expect('{')
            skipSpace()
            if (peek() == '}') { i++; return out }
            while (true) {
                skipSpace()
                val key = string()
                skipSpace()
                expect(':')
                out[key] = value()
                skipSpace()
                if (peek() == ',') { i++; continue }
                expect('}')
                return out
            }
        }

        private fun array(): List<Any> {
            val out = ArrayList<Any>()
            expect('[')
            skipSpace()
            if (peek() == ']') { i++; return out }
            while (true) {
                out += value()
                skipSpace()
                if (peek() == ',') { i++; continue }
                expect(']')
                return out
            }
        }

        private fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = next()
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = next()) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e) // quote, backslash, slash
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun peek(): Char = if (i < s.length) s[i] else throw IllegalArgumentException("unexpected end")
        private fun next(): Char = peek().also { i++ }
        private fun expect(c: Char) { require(next() == c) { "expected '$c' at ${i - 1}" } }
        private fun skipSpace() { while (i < s.length && s[i].isWhitespace()) i++ }
    }
}

/** A selected folder as found on disk. */
data class ResolvedFolder(
    val folder: SelectedFolder,
    val dir: File,
    val status: Status,
    /** Every audio file directly in the folder, by name. */
    val songs: List<File>,
    /** The ticked ones. */
    val included: List<File>,
    /** Sub-folders, offered in settings when the folder holds no songs itself. */
    val subfolders: List<File>,
) {
    enum class Status { OK, MISSING, UNREADABLE }

    val name: String get() = dir.name.ifEmpty { dir.path }
    val missing: Boolean get() = status != Status.OK
}

/** A selection as found on disk: what would play. */
data class ResolvedSelection(val root: String, val folders: List<ResolvedFolder>) {
    /** The songs that play. A folder listed twice (possible after the root moves) contributes once. */
    val files: List<File> = folders.flatMap { it.included }.distinctBy { it.path }
    val totalCount: Int = folders.flatMap { it.songs }.distinctBy { it.path }.size
    val selectedCount: Int get() = files.size

    /** The folder a problem is reported against: the first one that exists, else the first, else the root. */
    val folderLabel: String
        get() = (folders.firstOrNull { !it.missing } ?: folders.firstOrNull())?.dir?.path ?: root

    /** Why nothing would play, or null if something would. */
    fun problem(title: String, permissionGranted: Boolean = true): MusicState.Problem? {
        if (files.isNotEmpty()) return null
        val label = folderLabel
        return when {
            folders.isEmpty() ->
                MusicState.Problem("No folders chosen for $title. Open music settings and add a folder.", label)
            !permissionGranted -> MusicMessages.permission(label)
            folders.all { it.missing } -> {
                val first = folders.first()
                val result = if (first.status == ResolvedFolder.Status.UNREADABLE) ScanResult.Unreadable else ScanResult.Missing
                if (folders.size == 1) MusicMessages.noMusic(label, result)
                else MusicState.Problem(
                    "None of the ${folders.size} folders chosen for $title can be found. Copy them over or choose others in music settings.",
                    label,
                )
            }
            totalCount == 0 ->
                if (folders.size == 1) MusicMessages.noMusic(label, ScanResult.Empty)
                else MusicState.Problem(
                    "No music found in the folders chosen for $title. Copy audio files there or choose others in music settings.",
                    label,
                )
            else -> MusicState.Problem("No songs selected for $title. Open music settings and tick some songs.", label)
        }
    }

    /** Counts for a menu, with a short problem that names no folder or song. */
    fun summary(permissionGranted: Boolean = true): PlaylistSummary {
        val problem = when {
            files.isNotEmpty() -> null
            folders.isEmpty() -> "No folders chosen"
            !permissionGranted -> "Allow storage access to play music"
            folders.all { it.missing } -> if (folders.size == 1) "Music folder not found" else "Music folders not found"
            totalCount == 0 -> if (folders.size == 1) "No songs in the chosen folder" else "No songs in the chosen folders"
            else -> "No songs selected"
        }
        return PlaylistSummary(folders.size, selectedCount, totalCount, problem)
    }
}

/** Reads the disk for a selection. Blocking; call off the main thread. Never throws. */
object SelectionResolver {
    fun resolve(root: String, selection: MusicSelection): ResolvedSelection =
        ResolvedSelection(root, selection.folders.map { resolve(root, it) })

    fun resolve(root: String, folder: SelectedFolder): ResolvedFolder {
        val dir = FolderPaths.resolve(root, folder.path)
        val listing = MusicScanner.list(dir)
        val status = when {
            listing != null -> ResolvedFolder.Status.OK
            isDirectory(dir) -> ResolvedFolder.Status.UNREADABLE
            else -> ResolvedFolder.Status.MISSING
        }
        val songs = listing?.songs ?: emptyList()
        return ResolvedFolder(
            folder = folder,
            dir = dir,
            status = status,
            songs = songs,
            included = songs.filter { it.name !in folder.excluded },
            subfolders = listing?.subfolders ?: emptyList(),
        )
    }

    /**
     * The selection in force for [playlist]: the saved one, else its default folder. A playlist with
     * no default folder starts as everything under the root: the root and each of its sub-folders
     * that holds songs.
     */
    fun effective(store: SelectionStore, root: String, playlist: PlaylistRef): MusicSelection {
        store.saved(playlist.id)?.let { return it }
        if (Playlist.normalize(playlist.defaultFolder) != null) return MusicSelection.default(playlist.defaultFolder)
        val listing = MusicScanner.list(File(root)) ?: return MusicSelection.default(null)
        val withSongs = listing.subfolders.filter { MusicScanner.list(it)?.songs?.isNotEmpty() == true }
        return MusicSelection(listOf(SelectedFolder(FolderPaths.ROOT)) + withSongs.map { SelectedFolder(it.name) })
    }

    private fun isDirectory(dir: File): Boolean = try {
        dir.isDirectory
    } catch (e: SecurityException) {
        true
    }
}
