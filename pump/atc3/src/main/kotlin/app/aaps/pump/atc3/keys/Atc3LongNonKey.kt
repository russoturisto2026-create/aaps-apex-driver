package app.aaps.pump.atc3.keys

import app.aaps.core.keys.interfaces.LongNonPreferenceKey

enum class Atc3LongNonKey(
    override val key: String,
    override val defaultValue: Long,
    override val exportable: Boolean = false
) : LongNonPreferenceKey {

    /**
     * Pump clock of the newest alarm already written into the AAPS history, whole UTC seconds.
     *
     * The pump's alarm history has no identity in it — no id, no sequence number, and an index that
     * shifts as records age out — so the only thing that says whether a record has been seen before
     * is its timestamp. Keeping the newest one on disk is what stops the same occlusion being
     * written again on the next connection, and again after the app restarts.
     *
     * Whole seconds through a UTC calendar, like every other history watermark in this driver, so
     * that a timezone change or daylight saving does not make old records look new.
     *
     * Not exportable: it describes one pump's history position, and restoring it onto another phone
     * or another pump would silently swallow alarms or repeat them.
     */
    LastAlarmSeconds("atc3_last_alarm_seconds", 0L),

    /** Pump clock of the newest refill already written into the AAPS history, whole UTC seconds; as [LastAlarmSeconds]. */
    LastRefillSeconds("atc3_last_refill_seconds", 0L),
}
