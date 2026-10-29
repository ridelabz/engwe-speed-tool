package io.github.ridelabz.engwespeedtool.reporting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLDecoder

class IssueUrlTest {
    private val repo = "https://github.com/owner/repo"

    private fun query(url: String): Map<String, String> =
        URI(url).rawQuery.split('&').associate {
            val (k, v) = it.split('=', limit = 2)
            k to URLDecoder.decode(v, Charsets.UTF_8)
        }

    @Test
    fun `the form's fields by priority, encoded`() {
        val link =
            IssueUrl.of(
                repo,
                IssueForm.COMPATIBILITY,
                mapOf(
                    IssueField.APP_VERSION to "0.3.0",
                    IssueField.MODEL_IDENTITY to "ENGWE_Test model+1&x=y",
                    IssueField.BUILD_TESTED to "PR #57",
                    IssueField.ERRORS to "not in this form",
                ),
            )
        val url = link.url
        assertTrue(url.startsWith("$repo/issues/new?template=compatibility_report.yml&"))
        assertFalse(url.contains(' ') || url.contains('+') || url.contains('#'), url)
        val q = query(url)
        assertEquals("ENGWE_Test model+1&x=y", q["model_identity"])
        assertEquals("PR #57", q["build_tested"])
        assertEquals("0.3.0", q["app_version"])
        assertFalse("errors" in q, "only the form's fields")
        assertEquals(listOf("template", "model_identity", "app_version", "build_tested"), q.keys.toList())
        assertEquals(emptyList<IssueField>(), link.omitted)
    }

    @Test
    fun `values come back as they went in`() {
        val values =
            listOf(
                "line one\nline two",
                "100% = all & more #1",
                "Ünïcödé · 25\u00A0km/h",
                "emoji \uD83D\uDEB2 bike",
                "a~b-c.d_e",
            )
        for (value in values) {
            val link = IssueUrl.of(repo, IssueForm.PROBLEM, mapOf(IssueField.ERRORS to value))
            assertEquals(value, query(link.url)["errors"], value)
            assertTrue(link.url.all { it.code in 0x21..0x7E }, link.url)
        }
    }

    @Test
    fun `a single-line field is one line, whole or left out`() {
        val joined = IssueUrl.of(repo, IssueForm.PROBLEM, mapOf(IssueField.APP_VERSION to "0.3.0\n\n  test"))
        assertEquals("0.3.0 test", query(joined.url)["app_version"])
        val long = IssueUrl.of(repo, IssueForm.PROBLEM, mapOf(IssueField.MODEL_IDENTITY to "x".repeat(301)))
        assertFalse("model_identity" in query(long.url), "never cut into another value")
        assertEquals(listOf(IssueField.MODEL_IDENTITY), long.omitted)
    }

    @Test
    fun `a long multi-line field is cut at a line break, and says so`() {
        val lines = (1..100).joinToString("\n") { "error $it \uD83D\uDEB2" }
        val link = IssueUrl.of(repo, IssueForm.PROBLEM, mapOf(IssueField.ERRORS to lines))
        val errors = checkNotNull(query(link.url)["errors"])
        assertTrue(errors.length <= IssueUrl.MAX_VALUE)
        assertTrue(errors.endsWith("\n…"))
        assertTrue(errors.removeSuffix("\n…").lines().all { it.matches(Regex("error [0-9]+ \uD83D\uDEB2")) })
        // A line break where the ellipsis would go over the limit.
        val edge =
            IssueUrl.of(
                repo,
                IssueForm.PROBLEM,
                mapOf(IssueField.ERRORS to "a".repeat(299) + "\n" + "b".repeat(50)),
            )
        assertTrue(edge.omitted.isNotEmpty() || checkNotNull(query(edge.url)["errors"]).length <= IssueUrl.MAX_VALUE)
        val oneLine = IssueUrl.of(repo, IssueForm.PROBLEM, mapOf(IssueField.ERRORS to "y".repeat(500)))
        assertEquals(listOf(IssueField.ERRORS), oneLine.omitted, "no line break to cut at")
    }

    @Test
    fun `what doesn't fit is left out, the rest still tried, and listed`() {
        val tight =
            IssueUrl.of(
                repo,
                IssueForm.PROBLEM,
                mapOf(
                    IssueField.APP_VERSION to "0.3.0",
                    IssueField.APP_STATE to "y".repeat(200),
                    IssueField.ERRORS to "2",
                ),
                maxLength = 120,
            )
        assertTrue(tight.url.length <= 120)
        assertEquals("0.3.0", query(tight.url)["app_version"])
        assertEquals("2", query(tight.url)["errors"])
        assertEquals(listOf(IssueField.APP_STATE), tight.omitted)
        // However long the values, the link stays within the budget.
        val all = IssueForm.PROBLEM.fields.associateWith { "é\n".repeat(500) }
        assertTrue(IssueUrl.of(repo, IssueForm.PROBLEM, all).url.length <= IssueUrl.MAX_LENGTH)
    }

    @Test
    fun `blank values are left out`() {
        val link = IssueUrl.of(repo, IssueForm.IDEA, mapOf(IssueField.APP_VERSION to "  "))
        assertEquals("$repo/issues/new?template=feature_request.yml", link.url)
        assertEquals(emptyList<IssueField>(), link.omitted)
    }
}
