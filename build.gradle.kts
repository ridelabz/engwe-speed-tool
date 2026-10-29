import org.gradle.api.attributes.Bundling
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.binary.compatibility.validator) apply false
    alias(libs.plugins.licensee) apply false
}

// Path to the minSdk platform's android.jar for the API check (see the CI workflow); unset in normal builds.
val androidApiJar: String? = providers.gradleProperty("androidApiJar").orNull

subprojects {
    plugins.withId("org.jlleitschuh.gradle.ktlint") {
        extensions.configure<KtlintExtension> { version.set(rootProject.libs.versions.ktlint.cli) }
    }
    plugins.withId("org.jetbrains.kotlin.jvm") {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")

        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        extensions.configure<KotlinJvmProjectExtension> {
            explicitApi()
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                // Compile against the Java 17 API, not the JDK running the build: these jars ship in the app.
                if (androidApiJar == null) freeCompilerArgs.add("-Xjdk-release=17")
                allWarningsAsErrors.set(androidApiJar == null) // the API check warns about the ignored JDK home
            }
        }
        if (androidApiJar != null) {
            // API check: compile main code against the minSdk platform's android.jar instead of the JDK,
            // so any Java API the oldest supported Android lacks fails to compile.
            tasks.named<KotlinCompile>("compileKotlin") { compilerOptions.freeCompilerArgs.add("-no-jdk") }
            dependencies { "compileOnly"(files(androidApiJar)) }
        }
        dependencies {
            "testImplementation"(platform(rootProject.libs.junit.bom))
            "testImplementation"(rootProject.libs.junit.jupiter)
            "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
        }
        tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("failed")
                exceptionFormat = TestExceptionFormat.FULL
            }
        }
    }
}

// Android modules compile with warnings as errors too. Among other things this rejects any suppression
// of compiler errors, which could otherwise reach `internal` code of the protocol module.
for (androidPlugin in listOf("com.android.application", "com.android.library")) {
    subprojects {
        plugins.withId(androidPlugin) {
            tasks.withType<KotlinCompile>().configureEach { compilerOptions.allWarningsAsErrors.set(true) }
        }
    }
}

// The ktlint Gradle plugin doesn't see sources of Android modules that use AGP's built-in Kotlin,
// so those are checked with the ktlint CLI (same version) instead.
val ktlintCli: Configuration =
    configurations.create("ktlintCli") {
        attributes { attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED)) }
    }

dependencies {
    ktlintCli(libs.ktlint.cli)
}

tasks.register<JavaExec>("ktlintAndroidCheck") {
    group = "verification"
    description = "Runs ktlint on the Android modules."
    classpath = ktlintCli
    mainClass.set("com.pinterest.ktlint.Main")
    args("--relative", "app/src/**/*.kt", "ble-android/src/**/*.kt", "*.kts", "app/*.kts", "ble-android/*.kts")
}

tasks.register<JavaExec>("ktlintAndroidFormat") {
    group = "formatting"
    description = "Formats the Android modules with ktlint."
    classpath = ktlintCli
    mainClass.set("com.pinterest.ktlint.Main")
    args(
        "--relative",
        "--format",
        "app/src/**/*.kt",
        "ble-android/src/**/*.kt",
        "*.kts",
        "app/*.kts",
        "ble-android/*.kts",
    )
}
