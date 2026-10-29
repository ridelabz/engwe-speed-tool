package io.github.ridelabz.engwespeedtool.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Protocol test vectors captured from a real bike (see test-vectors.json). */
internal object Vectors {
    val root: JsonObject by lazy {
        val text =
            checkNotNull(Vectors::class.java.getResource("/test-vectors.json")) { "test-vectors.json missing" }
                .readText()
        Json.parseToJsonElement(text).jsonObject
    }

    fun list(key: String): List<JsonObject> = (root[key] as JsonArray).map { it.jsonObject }

    fun obj(key: String): JsonObject = root.getValue(key).jsonObject

    fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    fun JsonObject.strings(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    fun commandFrame(name: String): ByteArray =
        Hex.decode(list("commands").single { it.str("name") == name }.str("frame"))
}
