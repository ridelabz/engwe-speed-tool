package io.github.ridelabz.engwespeedtool.reporting

import java.util.UUID

/**
 * What a compatibility check found, for its session-log record: names defined in code (the outcome and why,
 * each protocol check and each status value with how it went), counts, and the Bluetooth services the bike
 * declared. Never a value the bike sent, and no free text.
 */
public class CheckRecord(
    public val outcome: Enum<*>,
    /** Why a check that didn't pass ended: the failed check, or why it never connected. */
    public val reason: Enum<*>?,
    public val checks: List<CheckLine>,
    public val statusFrames: Int,
    /** The seconds of status read; null when no status was read. */
    public val sampledSeconds: Int?,
    public val unreadableFrames: Boolean,
    public val values: List<ValueLine>,
    public val services: List<DeclaredService>,
)

/** One protocol check and how it went. */
public class CheckLine(
    public val check: Enum<*>,
    public val result: Enum<*>,
)

/** One status value: how plausible it was over the sample, and whether it was read with a meaning not verified for the bike. */
public class ValueLine(
    public val value: Enum<*>,
    public val result: Enum<*>,
    public val unverified: Boolean,
)

/** A service as the bike declared it. Its UUIDs are logged through the sanitiser's UUID rule. */
public class DeclaredService(
    public val uuid: UUID,
    public val characteristics: List<DeclaredCharacteristic>,
)

public class DeclaredCharacteristic(
    public val uuid: UUID,
    public val properties: List<Enum<*>>,
    public val descriptors: List<UUID>,
)
