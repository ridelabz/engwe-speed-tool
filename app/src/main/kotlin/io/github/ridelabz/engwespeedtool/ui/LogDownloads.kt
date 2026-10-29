package io.github.ridelabz.engwespeedtool.ui

import android.content.ContentResolver
import android.content.ContentValues
import android.provider.MediaStore

/** Saves a log file's text in the phone's Downloads folder, where the rider finds it to attach to a report. */
fun interface LogDownloads {
    /**
     * Saves [text], exactly, as a new file named [name]. Returns the name the file got (Android adds a number
     * when the name is taken), or null when it couldn't be saved: then nothing is left behind. Blocking I/O.
     */
    fun save(
        name: String,
        text: String,
    ): String?
}

/**
 * Downloads through Android's media store: a new file of this app's own, which needs no storage permission
 * from Android 10 on. The file is pending until it is written whole, so no other app sees it half written.
 */
class MediaStoreDownloads(
    private val resolver: ContentResolver,
) : LogDownloads {
    override fun save(
        name: String,
        text: String,
    ): String? {
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                // Not text/plain: Android would add ".txt" to a ".log" name.
                put(MediaStore.Downloads.MIME_TYPE, MIME_TYPE)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        val uri =
            runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull()
                ?: return null
        val saved =
            runCatching {
                val out = checkNotNull(resolver.openOutputStream(uri, "wt"))
                out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                // Still pending, the file would stay hidden from the browser's file chooser and be removed later.
                check(
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                        null,
                        null,
                    ) ==
                        1,
                )
            }.isSuccess
        if (!saved) {
            // A file cut short is no use: it goes.
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        val given =
            runCatching {
                resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()
        return given ?: name
    }

    private companion object {
        const val MIME_TYPE = "application/octet-stream"
    }
}
