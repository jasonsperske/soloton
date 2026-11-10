package dev.soloton.music

import android.media.MediaMetadataRetriever
import java.io.File

/** Reads title, artist and album tags. Blocking; call off the main thread. Never throws. */
internal object TagReader {
    data class Tags(val title: String?, val artist: String?, val album: String?)

    private val NONE = Tags(null, null, null)

    fun read(file: File): Tags {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            Tags(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
            )
        } catch (e: Exception) {
            NONE
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // Nothing useful to do.
            }
        }
    }
}
