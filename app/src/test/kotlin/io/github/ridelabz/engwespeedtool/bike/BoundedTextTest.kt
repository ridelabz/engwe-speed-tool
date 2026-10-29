package io.github.ridelabz.engwespeedtool.bike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedTextTest {
    private fun record(n: Int) = "{\"seq\":$n,\"event\":\"status\",\"pad\":\"${"x".repeat(n % 7)}\"}\n"

    @Test
    fun `keeps everything below the limit`() {
        val text = BoundedText(1_000)
        repeat(5) { text.append(record(it)) }
        assertEquals((0 until 5).joinToString("") { record(it) }, text.toString())
    }

    @Test
    fun `once full, it keeps whole records only, the newest`() {
        val limit = 200
        val text = BoundedText(limit)
        repeat(50) { text.append(record(it)) }
        val kept = text.toString()
        assertTrue(kept.length <= limit)
        assertTrue(kept.endsWith(record(49)))
        val lines = kept.removeSuffix("\n").split("\n")
        for (line in lines) assertTrue("a cut record: $line", line.startsWith("{") && line.endsWith("}"))
        val seqs = lines.map { Regex("\"seq\":(\\d+)").find(it)!!.groupValues[1].toInt() }
        assertEquals("the newest, in order, without gaps", (seqs.first()..49).toList(), seqs)
    }

    @Test
    fun `a record longer than the limit leaves nothing`() {
        val text = BoundedText(20)
        text.append(record(1))
        text.append("{\"event\":\"${"y".repeat(40)}\"}\n")
        assertEquals("", text.toString())
    }

    @Test
    fun `a record that overflows before its line end leaves no part of it, not even the line end`() {
        val text = BoundedText(30)
        text.append("{\"seq\":1}\n")
        // As the session log writes: the record, then its line end.
        text.append("{\"event\":\"${"y".repeat(40)}\"}")
        text.append('\n')
        text.append("{\"seq\":3}\n")
        assertEquals("{\"seq\":3}\n", text.toString())
    }

    @Test
    fun `appending by parts and characters counts the same`() {
        val text = BoundedText(60)
        repeat(10) { n ->
            val r = record(n)
            text.append(r, 0, 5)
            text.append(r.substring(5, r.length - 1))
            text.append('\n')
        }
        val kept = text.toString()
        assertTrue(kept.length <= 60)
        assertTrue(kept.endsWith(record(9)))
        for (line in kept.removeSuffix("\n").split("\n")) {
            assertTrue("a cut record: $line", line.startsWith("{") && line.endsWith("}"))
        }
    }

    @Test
    fun `the first line can stay, within the limit, and the newest records after it`() {
        val text = BoundedText(120, keepFirstLine = true)
        text.append("{\"event\":\"session_start\"}")
        text.append('\n')
        repeat(30) { text.append(record(it)) }
        val kept = text.toString()
        assertTrue(kept.length <= 120)
        assertTrue(kept.startsWith("{\"event\":\"session_start\"}\n"))
        assertTrue(kept.endsWith(record(29)))
        val lines = kept.removeSuffix("\n").split("\n")
        val seqs = lines.drop(1).map { Regex("\"seq\":(\\d+)").find(it)!!.groupValues[1].toInt() }
        assertEquals((seqs.first()..29).toList(), seqs)
    }

    @Test
    fun `a first line longer than the limit isn't kept`() {
        val text = BoundedText(20, keepFirstLine = true)
        text.append("{\"event\":\"${"y".repeat(40)}\"}")
        text.append('\n')
        text.append("{\"seq\":3}\n")
        assertEquals("{\"seq\":3}\n", text.toString())
    }

    @Test
    fun `starting again keeps only the first line`() {
        val text = BoundedText(1_000, keepFirstLine = true)
        text.append("{\"event\":\"session_start\"}\n")
        repeat(3) { text.append(record(it)) }
        text.restart()
        assertEquals("{\"event\":\"session_start\"}\n", text.toString())
        text.append(record(9))
        assertEquals("{\"event\":\"session_start\"}\n" + record(9), text.toString())
    }

    @Test
    fun `a record cut by a restart doesn't leave its end behind`() {
        val text = BoundedText(1_000, keepFirstLine = true)
        text.append("{\"event\":\"session_start\"}\n")
        text.append("{\"seq\":1}")
        text.restart()
        text.append('\n')
        text.append(record(2))
        assertEquals("{\"event\":\"session_start\"}\n" + record(2), text.toString())
    }
}
