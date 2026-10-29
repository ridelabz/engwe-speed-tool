package io.github.ridelabz.engwespeedtool.reporting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SessionLogFilesTest {
    @TempDir
    lateinit var dir: File

    private fun names() = dir.list().orEmpty().sorted()

    @Test
    fun `each run gets the next number, and only the last five stay`() {
        val files = SessionLogFiles(dir)
        val numbers = (1..7).map { files.start().also { log -> files.save(log, "run ${log.number}\n") }.number }
        assertEquals((1..7).toList(), numbers)
        assertEquals(listOf(7, 6, 5, 4, 3), files.list().map { it.number })
        assertEquals("run 5\n", files.read(StoredLog(5)))
        assertEquals(
            listOf("session-3.jsonl", "session-4.jsonl", "session-5.jsonl", "session-6.jsonl", "session-7.jsonl"),
            names(),
        )
    }

    @Test
    fun `numbers go on after the app starts again`() {
        SessionLogFiles(dir).start()
        SessionLogFiles(dir).start()
        assertEquals(3, SessionLogFiles(dir).start().number)
    }

    @Test
    fun `older runs go first once all of them are more than the limit, the current one stays`() {
        val files = SessionLogFiles(dir, maxBytes = 100, maxRunBytes = 60)
        val old = (1..3).map { files.start().also { log -> files.save(log, "x".repeat(29) + "\n") } }
        assertEquals(3, files.list().size, "90 bytes: all kept")
        val current = files.start()
        files.save(current, "y".repeat(59) + "\n")
        assertEquals(listOf(current, old[2]), files.list(), "60 + 30 bytes")
        assertTrue(files.list().sumOf(files::size) <= 100)
    }

    @Test
    fun `a run at the run limit with a full store keeps the newest earlier runs that fit`() {
        val files = SessionLogFiles(dir, maxBytes = 100, maxRunBytes = 50)
        val old = (1..4).map { files.start().also { log -> files.save(log, "x".repeat(24) + "\n") } }
        val current = files.start()
        files.save(current, "y".repeat(200) + "\n" + "z".repeat(49) + "\n")
        assertEquals(50, files.size(current))
        assertEquals(listOf(current, old[3], old[2]), files.list(), "50 + 25 + 25 bytes")
    }

    @Test
    fun `a cut never splits a line or a character`() {
        val files = SessionLogFiles(dir, maxBytes = 100, maxRunBytes = 9)
        val log = files.start()
        // "ü" takes two bytes: 9 bytes from the end start inside it.
        files.save(log, "aaaaaaaaaa\nbbbbü\ncccccc\n")
        assertEquals("cccccc\n", files.read(log))
        val files12 = SessionLogFiles(dir, maxBytes = 100, maxRunBytes = 12)
        // A cut right at a line start keeps that line.
        files12.save(log, "a\nbbbb\ncccccc\n")
        assertEquals("bbbb\ncccccc\n", files12.read(log))
    }

    @Test
    fun `a run longer than the limit keeps its last whole lines`() {
        val files = SessionLogFiles(dir, maxBytes = 25, maxRunBytes = 25)
        val log = files.start()
        files.save(log, "first line\nsecond line\nthird line\n")
        assertEquals("second line\nthird line\n", files.read(log))
        files.save(log, "one very long line without an end that is longer than the limit")
        assertEquals("", files.read(log), "no whole line fits")
    }

    @Test
    fun `saving replaces the content, leaves no temporary file, and leaves foreign files alone`() {
        File(dir, "notes.txt").writeText("keep me")
        File(dir, "session-x.jsonl").writeText("not ours")
        File(dir, "session-9.jsonl.tmp").writeText("left over from a crash")
        val files = SessionLogFiles(dir)
        val log = files.start()
        assertEquals(1, log.number, "only well-formed names count")
        files.save(log, "a\n")
        files.save(log, "b\n")
        assertEquals("b\n", files.read(log))
        assertEquals(listOf("notes.txt", "session-1.jsonl", "session-x.jsonl"), names())
    }

    @Test
    fun `file names hold a number and nothing else`() {
        val files = SessionLogFiles(dir)
        repeat(3) { files.start() }
        assertEquals(listOf("session-1.jsonl", "session-2.jsonl", "session-3.jsonl"), names())
    }

    @Test
    fun `deleting removes every stored run and nothing else`() {
        val files = SessionLogFiles(dir)
        repeat(3) { files.start() }
        File(dir, "notes.txt").writeText("not ours")
        files.deleteAll()
        assertEquals(listOf("notes.txt"), names())
        assertEquals(1, files.start().number, "numbers count on from what is left")
    }
}
