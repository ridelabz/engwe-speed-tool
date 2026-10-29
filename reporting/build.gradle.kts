plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":protocol"))
    implementation(libs.kotlinx.serialization.json)
    // Test only: reads the issue forms as GitHub does.
    testImplementation(libs.snakeyaml)
}

/** The issue forms' directory for the tests: an input by its content, not by where the checkout is. */
class IssueForms(
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val dir: File,
) : CommandLineArgumentProvider {
    override fun asArguments(): List<String> = listOf("-DissueForms=${dir.absolutePath}")
}

// The tests check the issue forms the app fills in, from their one copy; a change to a form runs them again.
tasks.test {
    jvmArgumentProviders += IssueForms(rootDir.resolve(".github/ISSUE_TEMPLATE"))
}
