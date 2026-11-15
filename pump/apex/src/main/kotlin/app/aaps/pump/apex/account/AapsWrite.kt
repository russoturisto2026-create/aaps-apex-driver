package app.aaps.pump.apex.account

/**
 * What the account asks the plugin to put into the AAPS database. The account knows nothing of
 * `PumpSync`; it hands these out and the plugin carries them.
 */
sealed class AapsWrite {

    /** One bolus row. [type] is the AAPS bolus type by name: NORMAL, SMB or PRIMING. */
    data class Bolus(val pumpId: Long, val timestampMs: Long, val amountUnits: Double, val type: String) : AapsWrite()

    /**
     * One temporary basal row, or a change of one with the same [pumpId]. [rate] is U/h when
     * [isAbsolute], else percent. [type] is the AAPS temporary basal type by name: NORMAL or PUMP_SUSPEND.
     */
    data class Tbr(val pumpId: Long, val startMs: Long, val rate: Double, val isAbsolute: Boolean, val durationMs: Long, val type: String) : AapsWrite()

    /** The running temporary basal row ends at [endMs]; [endPumpId] names the end so that it is written once. */
    data class StopTbr(val endMs: Long, val endPumpId: Long) : AapsWrite()

    /**
     * Something the user should be told: what it is about, and what the text shows: [detail] for
     * the kinds that take a word, [units] and the moment [atMs] for a faulty journal cell.
     */
    data class Notice(val kind: NoticeKind, val detail: String = "", val units: Double = 0.0, val atMs: Long = 0L) : AapsWrite()

    companion object {

        /** The AAPS type of an ordinary bolus row and of an ordinary temporary basal row. */
        const val NORMAL = "NORMAL"

        /** The AAPS type of the temporary basal row that stands for the pump's stop. */
        const val PUMP_SUSPEND = "PUMP_SUSPEND"
    }
}
