package io.github.ridelabz.engwespeedtool.protocol

/** Assistance speed limits the app can set. There is deliberately no free value and no zero. */
public enum class SpeedLimit(
    public val kmh: Int,
) {
    KMH_16(16),
    KMH_25(25),
    KMH_32(32),

    /** Not yet ridden on any bike: the app's warning says so. */
    KMH_45(45),
    ;

    /** True above the 25 km/h limit for e-bikes in the EU. */
    public val isAboveLegalLimit: Boolean get() = kmh > LEGAL_LIMIT_KMH

    public companion object {
        public const val LEGAL_LIMIT_KMH: Int = 25

        /** The bike's own setting after it is switched on. */
        public val BIKE_DEFAULT: SpeedLimit = KMH_25

        public fun ofKmh(kmh: Int): SpeedLimit? = entries.firstOrNull { it.kmh == kmh }
    }
}
