package dev.soloton.music

import java.io.File
import java.io.IOException

/** Outcome of scanning a music folder. Pure JVM; no Android imports. */
sealed interface ScanResult {
    /** At least one playable file, sorted by path. */
    data class Found(val files: List<File>) : ScanResult
    data object Missing : ScanResult
    data object NotADirectory : ScanResult
    /** The folder exists but cannot be listed (usually missing storage permission). */
    data object Unreadable : ScanResult
    /** The folder (and its subfolders) contain no file with a supported extension. */
    data object Empty : ScanResult
}

/** Recursively finds audio files under a folder. Hidden files and folders (leading dot) are skipped. */
object MusicScanner {
    val EXTENSIONS: Set<String> = setOf("mp3", "ogg", "flac", "m4a", "aac", "wav", "opus")

    /** Guards against pathological trees; real music libraries are a few levels deep. */
    private const val MAX_DEPTH = 32

    fun isAudioFile(file: File): Boolean =
        !file.name.startsWith(".") && file.extension.lowercase() in EXTENSIONS

    /** Blocking; call off the main thread. Never throws. */
    fun scan(root: File): ScanResult {
        return try {
            if (!root.exists()) return ScanResult.Missing
            if (!root.isDirectory) return ScanResult.NotADirectory
            val top = root.listFiles() ?: return ScanResult.Unreadable
            val found = ArrayList<File>()
            val visited = HashSet<String>()
            visited += canonical(root)
            walk(top, found, visited, depth = 0)
            if (found.isEmpty()) ScanResult.Empty else ScanResult.Found(found.sortedBy { it.path })
        } catch (e: SecurityException) {
            ScanResult.Unreadable
        }
    }

    /** What is directly in a folder: its audio files and its sub-folders, each by name. Hidden entries are skipped. */
    data class Listing(val songs: List<File>, val subfolders: List<File>)

    /** Lists [dir] without descending. Blocking. @return null if it is not a folder or cannot be read */
    fun list(dir: File): Listing? {
        val entries = try {
            dir.listFiles()
        } catch (e: SecurityException) {
            null
        } ?: return null
        val visible = entries.filter { !it.name.startsWith(".") }
        return Listing(
            songs = visible.filter { it.isFile && isAudioFile(it) }.sortedWith(BY_NAME),
            subfolders = visible.filter { it.isDirectory }.sortedWith(BY_NAME),
        )
    }

    /** Case-insensitive by name, so "02 - b" follows "01 - A"; ties broken by the exact name. */
    private val BY_NAME: Comparator<File> = compareBy<File> { it.name.lowercase() }.thenBy { it.name }

    private fun walk(entries: Array<File>, out: MutableList<File>, visited: MutableSet<String>, depth: Int) {
        for (f in entries) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) {
                // Canonical paths stop symlink loops from recursing forever.
                if (depth >= MAX_DEPTH || !visited.add(canonical(f))) continue
                val children = f.listFiles() ?: continue
                walk(children, out, visited, depth + 1)
            } else if (f.isFile && isAudioFile(f)) {
                out += f
            }
        }
    }

    private fun canonical(f: File): String = try {
        f.canonicalPath
    } catch (e: IOException) {
        f.absolutePath
    }
}
