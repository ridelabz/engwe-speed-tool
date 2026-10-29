plugins {
    alias(libs.plugins.kotlin.jvm)
    // Contract tests every Bluetooth link must pass; the Android adapter's tests reuse them.
    `java-test-fixtures`
    // The public API is pinned in api/session.api: a new way to reach the bike shows up in review.
    alias(libs.plugins.binary.compatibility.validator)
}

// The tests also decode the protocol module's captured vectors, from the one copy of that file.
sourceSets.test {
    resources.srcDir(rootProject.file("protocol/src/test/resources"))
}

dependencies {
    api(project(":protocol"))
    api(project(":reporting"))
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
}
