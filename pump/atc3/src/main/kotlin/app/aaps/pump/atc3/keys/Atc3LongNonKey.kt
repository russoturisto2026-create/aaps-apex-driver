package app.aaps.pump.atc3.keys

import app.aaps.core.keys.interfaces.LongNonPreferenceKey

enum class Atc3LongNonKey(
    override val key: String,
    override val defaultValue: Long,
    override val exportable: Boolean = false
) : LongNonPreferenceKey {

    /**
     * The newest alarm already written into AAPS, as a UTC-calendar key: an alarm record has no identity
     * but its time. Not exportable: it is one pump's position in its history.
     */
    LastAlarmSeconds("atc3_last_alarm_seconds", 0L),

    /** Pump clock of the newest refill already written into the AAPS history, whole UTC seconds; as [LastAlarmSeconds]. */
    LastRefillSeconds("atc3_last_refill_seconds", 0L),
}
