package io.github.ridelabz.engwespeedtool.protocol

/**
 * Reads the model identity from the bike's identity reply. The reply also carries a serial number;
 * only the model string before the first NUL is returned, and nothing after it is ever kept.
 * Callers must drop the reply frame as soon as this returns.
 */
public object IdentityParser {
    /** The model field ends within the first 32 bytes on the reference bike (25 characters + NUL). */
    public const val MAX_MODEL_LENGTH: Int = 31
    private const val MAX_DIGIT_RUN = 7
    private val DIGIT_RUN = Regex("[0-9]{${MAX_DIGIT_RUN + 1},}")

    public fun isIdentityReply(frame: Frame): Boolean = frame.command == Opcodes.IDENTITY

    /**
     * Returns the model identity, or null when the reply is unusable: wrong command, bad CRC, no NUL
     * within the first 32 bytes (the model can't be told apart from what follows), empty, not printable
     * ASCII, containing a run of 8 or more digits (looks like a serial number), or looking like an
     * address, a name suffix or an e-mail address ([Sanitiser.looksIdentifying]): the model is kept and
     * logged as it is, so it must not identify anything.
     */
    public fun modelOf(frame: Frame): String? {
        if (!isIdentityReply(frame) || !frame.crcOk) return null
        var end = -1
        for (i in 0 until frame.payloadSize) {
            if (frame.payloadByte(i) == 0) {
                end = i
                break
            }
        }
        if (end < 1 || end > MAX_MODEL_LENGTH) return null
        val chars = CharArray(end)
        for (i in 0 until end) {
            val b = frame.payloadByte(i)
            if (b !in 0x21..0x7E) return null
            chars[i] = b.toChar()
        }
        val model = String(chars)
        if (DIGIT_RUN.containsMatchIn(model) || Sanitiser.looksIdentifying(model)) return null
        return model
    }
}

public enum class Verification { VERIFIED, UNVERIFIED, INCOMPATIBLE }

/** Why a compatible bike isn't verified. */
public enum class UnverifiedReason {
    /** No profile for this model identity. */
    UNKNOWN_MODEL,

    /** A community profile that hasn't been verified yet. */
    PROFILE_NOT_VERIFIED,
}

/** What was learned about a bike after connecting, before anything is written except the identity read. */
public class ProtocolChecks(
    /** Service 1820 with a notify characteristic 1821 and an acknowledged-write characteristic 1822. */
    public val serviceFound: Boolean,
    /** CRC-valid status frames (`2A63`) arrived. */
    public val statusFramesValid: Boolean,
    /**
     * Must be the value returned by [IdentityParser.modelOf], never text taken from the raw reply (which
     * also carries the serial). Null when no usable identity reply arrived.
     */
    public val modelIdentity: String?,
)

/**
 * Result of classification. [layout] is null only for incompatible bikes; [unverifiedReason] is set only
 * for unverified ones. What to show and allow must come from [verification] and [layout], never from the
 * profile's own flags or layout.
 */
public class Classification internal constructor(
    public val verification: Verification,
    public val modelIdentity: String?,
    public val profile: BikeProfile?,
    public val layout: StatusLayout?,
    public val unverifiedReason: UnverifiedReason?,
)

public object BikeClassifier {
    /**
     * Verified only when a verified profile matches the whole model identity exactly; nothing else is
     * compared (the app doesn't read the bike's firmware version). Any other compatible bike is unverified,
     * with every field unverified.
     */
    public fun classify(
        checks: ProtocolChecks,
        registry: ProfileRegistry,
    ): Classification {
        val model = checks.modelIdentity
        if (!checks.serviceFound || !checks.statusFramesValid || model == null) {
            return Classification(Verification.INCOMPATIBLE, model, null, null, null)
        }
        val profile = registry.find(model)

        fun unverified(reason: UnverifiedReason) =
            Classification(
                Verification.UNVERIFIED,
                model,
                profile,
                profile?.statusLayout?.asUnverified() ?: registry.unverifiedLayout,
                reason,
            )
        return when {
            profile == null -> unverified(UnverifiedReason.UNKNOWN_MODEL)
            !profile.verified -> unverified(UnverifiedReason.PROFILE_NOT_VERIFIED)
            else -> Classification(Verification.VERIFIED, model, profile, profile.statusLayout, null)
        }
    }
}
