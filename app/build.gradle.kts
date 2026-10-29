plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.licensee)
}

// Pull-request test builds carry their PR number (0 when built locally).
val prNumber: Int =
    providers.gradleProperty("prNumber").orNull?.let { it.toIntOrNull() ?: error("prNumber must be an integer") } ?: 0

// `-Pscreenshots` makes the unit tests record screenshots (build/outputs/roborazzi). Recording needs
// Robolectric's native graphics, which has no Linux arm64 build; without it the screenshot tests still
// compose every screen, but capture nothing.
val recordScreenshots: Boolean = providers.gradleProperty("screenshots").map { it != "false" }.getOrElse(false)

android {
    namespace = "io.github.ridelabz.engwespeedtool"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.ridelabz.engwespeedtool"
        minSdk = 31
        targetSdk = 37
        // MAJOR.MINOR.PATCH; the version code follows from it (1.2.3 -> 10203), so it only ever grows.
        versionName = "1.0.0"
        versionCode =
            checkNotNull(versionName).let { version ->
                require(
                    Regex("""\d+\.\d+\.\d+""").matches(version),
                ) { "versionName must be MAJOR.MINOR.PATCH: $version" }
                val (major, minor, patch) = version.split('.').map(String::toInt)
                require(minor < 100 && patch < 100) { "MINOR and PATCH must stay below 100: $version" }
                major * 10_000 + minor * 100 + patch
            }
        buildConfigField("boolean", "IS_TEST_BUILD", "false")
        buildConfigField("int", "TEST_BUILD_PR", "0")
    }

    // Deliberately public: test builds use their own application ID and can never update a release install.
    signingConfigs {
        create("publicTest") {
            storeFile = rootProject.file("signing/public-test.keystore")
            storePassword = "publictest"
            keyAlias = "publictest"
            keyPassword = "publictest"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // The licences screen's test compares its copy with the repository's licence.
                test.inputs.file(rootProject.file("LICENSE")).withPropertyName("licence")
                // A failure says why in the build's output (CI keeps no test reports), as in the other modules.
                test.testLogging {
                    events("failed")
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                }
                // Robolectric reaches into JDK internals (robolectric.org/getting-started).
                test.jvmArgs(
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.net=ALL-UNNAMED",
                    "--add-opens=java.base/java.security=ALL-UNNAMED",
                    "--add-opens=java.base/java.text=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                )
                test.systemProperty("screenshots.record", recordScreenshots.toString())
                test.systemProperty("robolectric.graphicsMode", if (recordScreenshots) "NATIVE" else "LEGACY")
                // The app has no database; Robolectric's native SQLite would load the native runtime too.
                test.systemProperty("robolectric.sqliteMode", "LEGACY")
                if (recordScreenshots) {
                    test.systemProperty("roborazzi.test.record", "true")
                    // Declared, so that a test result taken from the build cache brings its screenshots along.
                    test.outputs.dir(layout.buildDirectory.dir("outputs/roborazzi")).withPropertyName("screenshots")
                }
            }
        }
    }

    lint {
        // Compose text is checked by scripts/check_ui_strings.py; this covers XML.
        error += "HardcodedText"
    }

    buildTypes {
        debug {
            // Pseudo-locales (en-XA: long accented text; ar-XB: right to left) for the screen tests.
            isPseudoLocalesEnabled = true
        }
        release {
            isMinifyEnabled = true
            // Resources no code or manifest uses are left out of the APK.
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
        create("prTest") {
            initWith(getByName("release"))
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
            signingConfig = signingConfigs.getByName("publicTest")
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "IS_TEST_BUILD", "true")
            buildConfigField("int", "TEST_BUILD_PR", prNumber.toString())
        }
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":ble-android"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    // Newer than Compose's test libraries ask for: older Espresso fails on the current Android API.
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.espresso.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
}

// Everything shipped in the app is under the Apache License 2.0, whose text the licences screen shows. Another
// (GPL-3.0-compatible) licence fails the build until that screen shows its notice and text too.
licensee {
    allow("Apache-2.0")
}
