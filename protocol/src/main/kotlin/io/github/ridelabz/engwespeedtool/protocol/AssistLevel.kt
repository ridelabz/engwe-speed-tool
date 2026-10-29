package io.github.ridelabz.engwespeedtool.protocol

/**
 * Assist levels the app can set: 0 (no assistance) to 5, the levels of the reference bike's display. A
 * bike's profile says how many levels it has ([BikeProfile.assistLevels]); only bikes whose profile gives
 * it get the control, and never a level above it. There is deliberately no other value.
 */
public enum class AssistLevel(
    public val level: Int,
) {
    LEVEL_0(0),
    LEVEL_1(1),
    LEVEL_2(2),
    LEVEL_3(3),
    LEVEL_4(4),
    LEVEL_5(5),
    ;

    public companion object {
        /** The highest level the app can set. */
        public const val MAX: Int = 5

        public fun of(level: Int): AssistLevel? = entries.firstOrNull { it.level == level }
    }
}
