package io.github.ridelabz.engwespeedtool.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * A log saved in Downloads through the media store: only its name, type and the pending flag go in, exactly
 * the text is written, the file is released once written whole, and a failed write leaves nothing.
 */
@RunWith(RobolectricTestRunner::class)
class LogDownloadsTest {
    /** The media store, as far as a saved download needs it. */
    class FakeMediaStore : ContentProvider() {
        val inserted = mutableListOf<ContentValues>()
        val updated = mutableListOf<ContentValues>()
        val deleted = mutableListOf<Uri>()

        /** The name the store gives the file (as Android does when the name is taken); null: as asked. */
        var given: String? = null

        /** Rows the release changes: 0 leaves the file pending. */
        var releases = 1

        override fun onCreate() = true

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri {
            inserted += ContentValues(values)
            return Uri.withAppendedPath(uri, inserted.size.toString())
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int {
            updated += ContentValues(values)
            return releases
        }

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int {
            deleted += uri
            return 1
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor =
            MatrixCursor(arrayOf(MediaStore.Downloads.DISPLAY_NAME)).apply {
                addRow(arrayOf(given ?: inserted.last().getAsString(MediaStore.Downloads.DISPLAY_NAME)))
            }

        override fun getType(uri: Uri): String? = null
    }

    private val resolver = RuntimeEnvironment.getApplication().contentResolver
    private val store = Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
    private val first = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "1")
    private val text = "{\"schema\":2,\"seq\":0,\"event\":\"session_start\",\"date\":\"2026-10-16\"}\n"

    @Test
    fun `a log is saved with its name, type and pending flag only, written exactly, then released`() {
        val written = ByteArrayOutputStream()
        shadowOf(resolver).registerOutputStream(first, written)
        val name = "engwe-speed-tool-2026-10-16-1.log"
        assertEquals(name, MediaStoreDownloads(resolver).save(name, text))
        val values = store.inserted.single()
        assertEquals(
            setOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.MIME_TYPE, MediaStore.Downloads.IS_PENDING),
            values.keySet(),
        )
        assertEquals(name, values.getAsString(MediaStore.Downloads.DISPLAY_NAME))
        assertEquals(
            "not text/plain, which would add .txt",
            "application/octet-stream",
            values.getAsString(MediaStore.Downloads.MIME_TYPE),
        )
        assertEquals(1, values.getAsInteger(MediaStore.Downloads.IS_PENDING))
        assertEquals(text, written.toString(Charsets.UTF_8))
        assertEquals(0, store.updated.single().getAsInteger(MediaStore.Downloads.IS_PENDING))
        assertTrue(store.deleted.isEmpty())
    }

    @Test
    fun `the name the store gives is the one returned`() {
        shadowOf(resolver).registerOutputStream(first, ByteArrayOutputStream())
        store.given = "engwe-speed-tool-2026-10-16-1 (1).log"
        assertEquals(store.given, MediaStoreDownloads(resolver).save("engwe-speed-tool-2026-10-16-1.log", text))
    }

    @Test
    fun `a file the store doesn't release counts as not saved and goes`() {
        shadowOf(resolver).registerOutputStream(first, ByteArrayOutputStream())
        store.releases = 0
        assertNull(MediaStoreDownloads(resolver).save("engwe-speed-tool-2026-10-16-1.log", text))
        assertEquals(listOf(first), store.deleted)
    }

    @Test
    fun `a write that fails leaves nothing behind and saves nothing`() {
        shadowOf(resolver).registerOutputStream(
            first,
            object : OutputStream() {
                override fun write(b: Int) = throw IOException("full")
            },
        )
        assertNull(MediaStoreDownloads(resolver).save("engwe-speed-tool-2026-10-16-1.log", text))
        assertEquals(listOf(first), store.deleted)
        assertTrue("never released as a finished file", store.updated.isEmpty())
    }
}
