package io.github.ridelabz.engwespeedtool.reporting

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The app's stored session logs: one file per session (one run of the app), at most [maxSessions] files and
 * [maxBytes] together, each run at most [maxRunBytes] so that one long run doesn't push out all earlier
 * ones, in a private directory ([dir]) that only the app reads. The logs are written already sanitised
 * ([SessionLog]); this only keeps them. File names hold a number and nothing else: no date, no time of day.
 * (The files' modification times do show when they were written: share their content, never the files.)
 *
 * Not thread-safe: one session's writer saves at a time.
 */
public class SessionLogFiles(
    private val dir: File,
    private val maxSessions: Int = DEFAULT_SESSIONS,
    private val maxBytes: Long = DEFAULT_BYTES,
    private val maxRunBytes: Long = DEFAULT_RUN_BYTES,
) {
    init {
        require(maxSessions >= 1 && maxBytes > 0 && maxRunBytes in 1..maxBytes) { "keep at least one session" }
    }

    /**
     * Starts the next session's file, numbered one above the highest kept; older sessions beyond the last
     * [maxSessions] (this one included) go.
     */
    public fun start(): StoredLog {
        dir.mkdirs()
        val log = StoredLog((stored().maxOfOrNull { it.number } ?: 0) + 1)
        file(log).writeText("")
        prune(log)
        return log
    }

    /**
     * Writes [text] as [log]'s whole content, replacing what it had, through a temporary file synced to
     * storage and moved over it in one step, so that a crash or a power loss during the write leaves the
     * last complete version; then keeps to the limits. [text] longer than [maxRunBytes] keeps only its last
     * whole lines within it. Throws if storage fails.
     */
    public fun save(
        log: StoredLog,
        text: String,
    ) {
        dir.mkdirs()
        val kept = lastLines(text.toByteArray(Charsets.UTF_8))
        val temp = File(dir, "${NAME_PREFIX}${log.number}$TEMP_SUFFIX")
        try {
            FileOutputStream(temp).use { out ->
                out.write(kept)
                out.fd.sync()
            }
            Files.move(
                temp.toPath(),
                file(log).toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            // Never the stored version: only what didn't make it.
            temp.delete()
        }
        prune(log)
    }

    /** Deletes every stored session's file; files of other names stay. */
    public fun deleteAll() {
        stored().forEach { file(it).delete() }
    }

    /** The stored sessions, newest first. */
    public fun list(): List<StoredLog> = stored().sortedByDescending { it.number }

    /** [log]'s content; empty if it is gone. */
    public fun read(log: StoredLog): String = file(log).takeIf { it.isFile }?.readText() ?: ""

    /** Its size in bytes; 0 if it is gone. */
    public fun size(log: StoredLog): Long = file(log).takeIf { it.isFile }?.length() ?: 0

    private fun file(log: StoredLog) = File(dir, "${NAME_PREFIX}${log.number}$SUFFIX")

    private fun stored(): List<StoredLog> =
        dir
            .listFiles()
            .orEmpty()
            .filter { it.isFile }
            .mapNotNull { f ->
                // Only this class's own names count; anything else in the directory is left alone.
                NAME
                    .matchEntire(f.name)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
                    ?.let(::StoredLog)
            }

    /** Keeps [current] and the newest others, within [maxSessions] and [maxBytes]; also drops stale temp files. */
    private fun prune(current: StoredLog) {
        dir
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith(NAME_PREFIX) && it.name.endsWith(TEMP_SUFFIX) }
            .forEach { it.delete() }
        val others = stored().filter { it != current }.sortedByDescending { it.number }
        others.drop(maxSessions - 1).forEach { file(it).delete() }
        var total = size(current) + others.take(maxSessions - 1).sumOf(::size)
        for (old in others.take(maxSessions - 1).asReversed()) {
            if (total <= maxBytes) break
            total -= size(old)
            file(old).delete()
        }
    }

    /**
     * At most [maxRunBytes] of [bytes]' end, starting at a line start. A line feed never occurs inside a
     * UTF-8 sequence, so a character cut at the start goes with its partial line.
     */
    private fun lastLines(bytes: ByteArray): ByteArray {
        if (bytes.size <= maxRunBytes) return bytes
        val from = bytes.size - maxRunBytes.toInt()
        if (bytes[from - 1] == LINE_FEED) return bytes.copyOfRange(from, bytes.size)
        val end = (from until bytes.size).firstOrNull { bytes[it] == LINE_FEED } ?: return ByteArray(0)
        return bytes.copyOfRange(end + 1, bytes.size)
    }

    public companion object {
        public const val DEFAULT_SESSIONS: Int = 5
        public const val DEFAULT_BYTES: Long = 2_000_000
        public const val DEFAULT_RUN_BYTES: Long = 1_000_000
        private const val LINE_FEED = '\n'.code.toByte()
        private const val NAME_PREFIX = "session-"
        private const val SUFFIX = ".jsonl"
        private const val TEMP_SUFFIX = ".jsonl.tmp"
        private val NAME = Regex("session-([1-9][0-9]{0,8})\\.jsonl")
    }
}

/** One stored session log, by its number (the sessions are numbered in the order they started). */
public data class StoredLog(
    val number: Int,
)
