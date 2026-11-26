package app.aaps.pump.apex.aaps

/**
 * What the account asks the plugin to put into the AAPS database. The account knows nothing of
 * `PumpSync`; it hands these out and the plugin carries them.
 */
sealed class AapsWrite {

    /**
     * One bolus row, final. [type] is the AAPS bolus type by name: NORMAL, SMB or PRIMING. [pumpId] is the
     * number of the pump's record; null for a bolus of ours that has none. [tempId] names the row written
     * when the pump accepted our bolus, see [EarlyBolus]: that row is set to this one; null for a bolus of
     * somebody else.
     */
    data class Bolus(val pumpId: Long?, val timestampMs: Long, val amountUnits: Double, val type: String, val tempId: Long? = null) : AapsWrite()

    /** The row of our bolus written the moment the pump accepted it, at the ordered dose, under the temporary number [tempId]. */
    data class EarlyBolus(val tempId: Long, val timestampMs: Long, val amountUnits: Double, val type: String) : AapsWrite()

    /**
     * One temporary basal row, or a change of one with the same [pumpId]. [rate] is U/h when
     * [isAbsolute], else percent. [type] is the AAPS temporary basal type by name, [NORMAL].
     */
    data class Tbr(val pumpId: Long, val startMs: Long, val rate: Double, val isAbsolute: Boolean, val durationMs: Long, val type: String) : AapsWrite()

    /** The running temporary basal row ends at [endMs]; the moment also names the end, so that it is written once. */
    data class StopTbr(val endMs: Long) : AapsWrite()

    /** Something the user should be told: what it is about, and what the text shows: [units] and the moment [atMs]. */
    data class Notice(val kind: NoticeKind, val units: Double = 0.0, val atMs: Long = 0L, val fromMs: Long = 0L) : AapsWrite()

    /** The reservoir was refilled: it held [fromUnits] at the status kept and holds [toUnits] at [atMs]. */
    data class Refill(val atMs: Long, val fromUnits: Double, val toUnits: Double) : AapsWrite()

    /** The loop opened: the bolus account could not be settled, see `MismatchLine`. */
    data object OpenLoop : AapsWrite()

    companion object {

        /** The AAPS type of an ordinary bolus row and of an ordinary temporary basal row. */
        const val NORMAL = "NORMAL"
    }
}
