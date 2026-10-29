plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // The public API is pinned in api/protocol.api: any new way to build or send bytes shows up in review.
    alias(libs.plugins.binary.compatibility.validator)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
}
