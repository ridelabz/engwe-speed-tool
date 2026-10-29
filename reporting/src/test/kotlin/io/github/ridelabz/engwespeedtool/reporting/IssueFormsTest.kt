package io.github.ridelabz.engwespeedtool.reporting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File

/**
 * The issue forms against what the app fills in: every field the app fills in is in its form, as a field
 * the URL can fill in. Also the forms' own rules, as GitHub reads them (a YAML parser, so that comments and
 * quoting count as they do there): the keys each element type takes, unique IDs of the allowed characters,
 * a label on every field, options that stay text, the required confirmations and log upload accepting the
 * app's `.log` files, the labels, no blank issues, and no form the app doesn't know.
 */
class IssueFormsTest {
    private val dir = File(checkNotNull(System.getProperty("issueForms")) { "the forms' directory" })

    private fun load(file: String): Map<*, *> =
        Yaml(SafeConstructor(LoaderOptions().apply { isAllowDuplicateKeys = false }))
            .load<Any>(File(dir, file).readText()) as Map<*, *>

    private fun body(form: IssueForm): List<Map<*, *>> =
        (load(form.template)["body"] as List<*>).map { it as Map<*, *> }

    private fun Map<*, *>.attributes(): Map<*, *> = this["attributes"] as? Map<*, *> ?: emptyMap<Any, Any>()

    private fun Map<*, *>.validations(): Map<*, *> = this["validations"] as? Map<*, *> ?: emptyMap<Any, Any>()

    /** Every text in [value], however deep. */
    private fun texts(value: Any?): List<String> =
        when (value) {
            is String -> listOf(value)
            is Map<*, *> -> value.values.flatMap(::texts)
            is List<*> -> value.flatMap(::texts)
            else -> emptyList()
        }

    @Test
    fun `every field the app fills in is in its form, as one the URL can fill in`() {
        for (form in IssueForm.entries) {
            val elements = body(form).associateBy { it["id"] }
            for (field in form.fields) {
                val element = elements[field.id]
                assertTrue(element != null, "${form.template} has no ${field.id}")
                assertTrue(
                    element!!["type"] in setOf("input", "textarea"),
                    "${form.template}: ${field.id} is a ${element["type"]}",
                )
                // Line breaks are kept only where the form takes several lines.
                assertEquals(
                    if (field.multiline) "textarea" else "input",
                    element["type"],
                    "${form.template}: ${field.id}",
                )
            }
        }
    }

    @Test
    fun `the test-build bot's link fills in the compatibility form's build field`() {
        assertEquals("compatibility_report.yml", IssueForm.COMPATIBILITY.template)
        assertTrue(IssueField.BUILD_TESTED in IssueForm.COMPATIBILITY.fields)
        assertEquals("build_tested", IssueField.BUILD_TESTED.id)
        val field = body(IssueForm.COMPATIBILITY).single { it["id"] == "build_tested" }
        assertEquals("The release version, or \"PR #n\" for a test build.", field.attributes()["description"])
    }

    @Test
    fun `the forms follow GitHub's rules and the project's`() {
        val labels =
            mapOf(
                IssueForm.COMPATIBILITY to "compatibility",
                IssueForm.PROBLEM to "bug",
                IssueForm.IDEA to "enhancement",
            )
        val attributes =
            mapOf(
                "markdown" to setOf("value"),
                "input" to setOf("label", "description", "placeholder", "value"),
                "textarea" to setOf("label", "description", "placeholder", "value", "render"),
                "dropdown" to setOf("label", "description", "multiple", "options", "default"),
                "checkboxes" to setOf("label", "description", "options"),
                "upload" to setOf("label", "description"),
            )
        val validations =
            mapOf(
                "input" to setOf("required"),
                "textarea" to setOf("required"),
                "dropdown" to setOf("required"),
                "upload" to setOf("required", "accept"),
            )
        for (form in IssueForm.entries) {
            val loaded = load(form.template)
            assertTrue(
                loaded.keys.all {
                    it in
                        setOf("name", "description", "title", "labels", "assignees", "projects", "type", "body")
                },
            )
            assertTrue((loaded["name"] as? String).orEmpty().isNotBlank(), "${form.template}: a name")
            assertTrue((loaded["description"] as? String).orEmpty().isNotBlank(), "${form.template}: a description")
            assertEquals(listOf(labels.getValue(form)), loaded["labels"], form.template)
            val ids = mutableListOf<String>()
            for (element in body(form)) {
                val type = element["type"] as String
                val where = "${form.template}: ${element["id"] ?: type}"
                assertTrue(type in attributes, "$where: type $type")
                assertTrue(element.keys.all { it in setOf("type", "id", "attributes", "validations") }, where)
                assertTrue(
                    element.attributes().keys.all { it in attributes.getValue(type) },
                    "$where: ${element.attributes().keys}",
                )
                assertTrue(
                    element.validations().keys.all {
                        it in validations[type].orEmpty()
                    },
                    "$where: ${element.validations().keys}",
                )
                if (type == "markdown") {
                    assertTrue(element["id"] == null, "$where: markdown has no ID")
                    continue
                }
                val id = element["id"] as? String
                assertTrue(id != null && Regex("[A-Za-z0-9_-]+").matches(id), "$where: ID")
                ids += id!!
                assertTrue((element.attributes()["label"] as? String).orEmpty().isNotBlank(), "$where: a label")
                when (type) {
                    // Options stay text: an unquoted Yes or No would turn into true or false.
                    "dropdown" -> {
                        val options = element.attributes()["options"] as List<*>
                        assertTrue(options.all { it is String }, "$where: options $options")
                        assertEquals(options.size, options.toSet().size, "$where: options repeat")
                    }

                    "checkboxes" -> {
                        val options = element.attributes()["options"] as List<*>
                        assertTrue(options.all { (it as Map<*, *>)["label"] is String }, "$where: options $options")
                    }
                }
            }
            assertEquals(ids.toSet().size, ids.size, "${form.template}: IDs repeat")
            // The forms have no comments: every # in the file is in a text (an unquoted " #" starts a comment).
            val hashes = File(dir, form.template).readText().count { it == '#' }
            assertEquals(
                hashes,
                texts(loaded).sumOf { text ->
                    text.count { it == '#' }
                },
                "${form.template}: a comment",
            )
        }
    }

    @Test
    fun `reports need the session log and the confirmations, and the app's log files are accepted`() {
        for (form in listOf(IssueForm.COMPATIBILITY, IssueForm.PROBLEM)) {
            val elements = body(form)
            val upload = elements.single { it["type"] == "upload" }
            assertEquals("log", upload["id"])
            assertEquals(true, upload.validations()["required"], "${form.template}: the log is required")
            val accepted = (upload.validations()["accept"] as String).split(',').map { it.trim() }
            assertTrue(".log" in accepted, "${form.template}: accepts $accepted")
            val confirmations = elements.single { it["id"] == "confirmations" }.attributes()["options"] as List<*>
            assertTrue(confirmations.all { (it as Map<*, *>)["required"] == true }, "${form.template}: confirmations")
        }
        assertTrue(body(IssueForm.IDEA).none { it["type"] == "upload" })
    }

    @Test
    fun `the compatibility form asks whether setting the assist level worked`() {
        val elements = body(IssueForm.COMPATIBILITY).associateBy { it["id"] }
        val asked = checkNotNull(elements["assist_worked"]) { "no assist question" }
        assertEquals("dropdown", asked["type"])
        assertEquals("Did setting the assist level from the app work?", asked.attributes()["label"])
        assertEquals(listOf("Yes", "No", "Didn't try"), asked.attributes()["options"])
        assertEquals(listOf("Yes", "No", "Didn't try"), elements.getValue("lights_worked").attributes()["options"])
        assertTrue(IssueField.ASSIST_CONFIRMED in IssueForm.COMPATIBILITY.fields)
        // The rider's answer, never filled in by the app.
        assertTrue(IssueField.entries.none { it.id == "assist_worked" })
        val power = checkNotNull(elements["power_worked"]) { "no question about turning the bike off and on" }
        assertEquals(listOf("Yes", "No", "Didn't try"), power.attributes()["options"])
        assertTrue(IssueField.entries.none { it.id == "power_worked" })
    }

    @Test
    fun `there are no blank issues and no forms the app doesn't know`() {
        assertEquals(false, load("config.yml")["blank_issues_enabled"])
        val forms =
            dir
                .list()
                .orEmpty()
                .filter { it != "config.yml" }
                .toSet()
        assertEquals(IssueForm.entries.map { it.template }.toSet(), forms)
    }
}
