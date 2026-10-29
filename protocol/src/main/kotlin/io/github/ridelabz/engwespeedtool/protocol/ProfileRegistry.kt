package io.github.ridelabz.engwespeedtool.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Bike profiles bundled with the app, looked up by exact model identity. [referenceModel] names the
 * verified profile whose layout, with every field unverified, is used for bikes without a profile.
 */
public class ProfileRegistry(
    profiles: List<BikeProfile>,
    public val referenceModel: String = REFERENCE_MODEL,
) {
    public val profiles: List<BikeProfile> = profiles.toList()

    /** Layout used for bikes without a profile: the reference layout, every field unverified. */
    public val unverifiedLayout: StatusLayout

    init {
        val duplicates =
            this.profiles
                .groupBy { it.modelIdentity }
                .filterValues { it.size > 1 }
                .keys
        if (duplicates.isNotEmpty()) throw ProfileException("duplicate model identities: $duplicates")
        val reference =
            this.profiles.firstOrNull { it.modelIdentity == referenceModel && it.verified }
                ?: throw ProfileException("reference profile $referenceModel missing or not verified")
        unverifiedLayout = reference.statusLayout.asUnverified()
    }

    /** Exact, case-sensitive match. */
    public fun find(modelIdentity: String): BikeProfile? = profiles.firstOrNull { it.modelIdentity == modelIdentity }

    public companion object {
        /** The project's own reference bike, an E26 3.0 Pro (tested with IoT firmware 218CAN). */
        public const val REFERENCE_MODEL: String = "ENGWE_Omni_ebike1100EU317"
        private const val SCHEMA = 5
        private const val DIR = "/profiles/"
        private val json = Json // strict: unknown keys are errors

        /** Loads the profiles listed in `profiles/index.txt` on the classpath. */
        public fun bundled(): ProfileRegistry {
            val index = resource("index.txt")
            val files = index.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            return ProfileRegistry(files.map { parse(resource(it), source = it) })
        }

        /** Parses and validates one profile document. */
        public fun parse(
            text: String,
            source: String = "profile",
        ): BikeProfile =
            try {
                json.decodeFromString(ProfileDto.serializer(), text).toProfile()
            } catch (e: SerializationException) {
                throw ProfileException("$source: ${e.message}", e)
            } catch (e: IllegalArgumentException) {
                throw ProfileException("$source: ${e.message}", e)
            }

        private fun resource(name: String): String =
            ProfileRegistry::class.java.getResource(DIR + name)?.readText()
                ?: throw ProfileException("missing profile resource $name")

        private fun ProfileDto.toProfile(): BikeProfile {
            require(schema == SCHEMA) { "unsupported schema $schema" }
            val specs =
                statusLayout.fields.map { f ->
                    val field = requireNotNull(StatusField.ofKey(f.name)) { "unknown field ${f.name}" }
                    FieldSpec(field, f.offset, f.size, f.scale, f.verified)
                }
            return BikeProfile(
                modelIdentity,
                displayName,
                verified,
                StatusLayout(statusLayout.payloadLength, specs),
                evidence,
                testedFirmware,
                assistLevels,
                lockControl,
            )
        }
    }
}

@Serializable
private class ProfileDto(
    val schema: Int,
    @SerialName("model_identity") val modelIdentity: String,
    @SerialName("display_name") val displayName: String,
    val verified: Boolean,
    @SerialName("status_layout") val statusLayout: LayoutDto,
    val evidence: List<String>,
    @SerialName("tested_firmware") val testedFirmware: List<String> = emptyList(),
    @SerialName("assist_levels") val assistLevels: Int? = null,
    @SerialName("lock_control") val lockControl: Boolean = false,
)

@Serializable
private class LayoutDto(
    @SerialName("payload_length") val payloadLength: Int,
    val fields: List<FieldDto>,
)

@Serializable
private class FieldDto(
    val name: String,
    val offset: Int,
    val size: Int,
    val scale: Double,
    val verified: Boolean,
)
