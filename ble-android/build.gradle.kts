plugins {
    alias(libs.plugins.android.library)
}

// The only module allowed to talk to Bluetooth GATT.
android {
    namespace = "io.github.ridelabz.engwespeedtool.ble"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all { test ->
            // Robolectric reaches into JDK internals (robolectric.org/getting-started).
            test.jvmArgs(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
            )
        }
    }
}

dependencies {
    api(project(":protocol"))
    api(project(":session"))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":session")))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
