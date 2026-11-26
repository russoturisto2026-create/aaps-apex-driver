package app.aaps.pump.apex.store

/** What the driver keeps a snapshot of: the status and the days of the daily statistics, see `StatisticsDays`. The bolus journal is kept in cells, see [BolusCell]. */
enum class SnapshotKind { STATUS, DAILY }

/**
 * The last answer of the pump to one question, as it came: the frames newest first for a journal,
 * one frame for the status. The next answer is compared with it and then takes its place.
 */
data class Snapshot(val frames: List<ByteArray>, val readAtMs: Long)

/**
 * The driver's picture of its own bolus, what is known of it before the reconciliation: the moment
 * the pump accepted it, to the second, what was asked, where the journal stood, so that the pump's
 * record of it is found by its place, and what the pump showed of it while it ran: its progress frames,
 * the cancel, the completion. It lives in the cell of the bolus, see [BolusCell].
 */
data class Action(
    /** The id of the cell the picture lives in. */
    val id: Long = 0L,
    /** UTC milliseconds of the pump's acknowledgement. */
    val acceptedAtMs: Long,
    /** Units of the bolus. */
    val amount: Double,
    /** The AAPS type of the row, as the core names it, for example SMB or NORMAL. */
    val type: String,
    /** The count of records the journal had given so far, read just before the command: the action's record comes after it. */
    val journalTopSeq: Long,
    /** Units the pump reported delivered: from its progress frames while the bolus runs, from its completion frame at the end. */
    val seenUnits: Double? = null,
    /** Progress frames of the bolus seen. */
    val frames: Int = 0,
    /** The user asked to stop the bolus. */
    val cancelPressed: Boolean = false,
    /** The pump accepted the cancel. */
    val cancelAccepted: Boolean = false,
    /** The pump's completion frame came. */
    val completed: Boolean = false
) {

    /** The temporary number of the row written when the pump accepted the bolus: the moment it did. */
    val tempId: Long get() = acceptedAtMs

    /** The pump's last word on the bolus was seen: its completion frame, or the accepted cancel after a progress frame. */
    val lastWordSeen: Boolean get() = completed || cancelAccepted && frames > 0

    /** Nothing of the bolus was seen: no progress frame and no completion. */
    val seenNothing: Boolean get() = frames == 0 && !completed
}

/**
 * One cell of the driver's own bolus journal: the pump's journal as the driver corrected it, the step
 * before AAPS. A cell holds a record of the pump, our picture of a bolus of ours, or both once the
 * reconciliation matched them. Our bolus gets its cell when the pump accepts it, before any record of
 * it is read; the record comes at a later read, so a cell has no number until then.
 *
 * The cells keep as many records as the pump's journal holds, see `BolusCells`.
 */
class BolusCell(
    /** The cell's own number, in the order cells were made. */
    val id: Long = 0L,
    /** The number of the pump's record, which only grows; null while no record is in the cell. */
    val seq: Long? = null,
    /** The record as the pump gave it; null while no record is in the cell. */
    val bytes: ByteArray? = null,
    /** The offset of the pump's clock the record was stamped in: pump wall clock minus UTC, milliseconds. */
    val offsetMs: Long? = null,
    /** Our picture of the bolus, before the reconciliation; null for a bolus of somebody else. */
    val action: Action? = null,
    /** The record as corrected: what went in, U. Null before the reconciliation, and for a record of the past. */
    val units: Double? = null,
    /** The record as corrected: when, UTC milliseconds. Null when [units] is. */
    val atMs: Long? = null,
    /** The record did not agree with the pump's statistics and was corrected. */
    val faulty: Boolean = false,
    /** The cell's row is in AAPS: written or corrected there. */
    val inAaps: Boolean = false,
    /** When the reconciliation that filled the cell ran, UTC milliseconds. Null before it. */
    val settledAtMs: Long? = null,
    /** The bolus field of the pump's statistics for the day of the bolus at that reconciliation, U. Null when the statistics had no such day. */
    val dayBolusUnits: Double? = null
) {

    /** The reconciliation has not reached the cell yet. */
    val open: Boolean get() = units == null

    fun copy(
        seq: Long? = this.seq, bytes: ByteArray? = this.bytes, offsetMs: Long? = this.offsetMs, action: Action? = this.action,
        units: Double? = this.units, atMs: Long? = this.atMs, faulty: Boolean = this.faulty, inAaps: Boolean = this.inAaps, id: Long = this.id,
        settledAtMs: Long? = this.settledAtMs, dayBolusUnits: Double? = this.dayBolusUnits
    ) = BolusCell(id, seq, bytes, offsetMs, action, units, atMs, faulty, inAaps, settledAtMs, dayBolusUnits)

    override fun equals(other: Any?): Boolean =
        other is BolusCell && id == other.id && seq == other.seq && (bytes contentEquals other.bytes) && offsetMs == other.offsetMs &&
            action == other.action && units == other.units && atMs == other.atMs && faulty == other.faulty && inAaps == other.inAaps &&
            settledAtMs == other.settledAtMs && dayBolusUnits == other.dayBolusUnits

    override fun hashCode(): Int = id.hashCode() * 31 + (seq?.hashCode() ?: 0)
}

/** The offset the pump's clock was last written in: pump wall clock minus UTC, milliseconds. */
data class ClockOffset(val offsetMs: Long, val writtenAtMs: Long, val reason: String)

/**
 * One clock write: when, from which offset to which, and how many bolus records had been seen by
 * then. A bolus record counted here was stamped before the write.
 */
data class ClockLogEntry(val writtenAtMs: Long, val oldOffsetMs: Long?, val newOffsetMs: Long, val reason: String, val bolusCount: Long)

/**
 * What the driver keeps on disk, all of it under the pump's serial number. The account package
 * speaks only to this interface; the Android side implements it with Room.
 */
interface ApexStore {

    /**
     * Run [block] so that everything it stores lands together at its end, or not at all when it
     * throws. The tick keeps its copies and marks this way: a tick the pump cut short leaves the store
     * as the last whole tick left it, and the next tick sees the same difference again.
     */
    fun <T> transaction(block: () -> T): T

    /** The last answer of this kind, or null before the first. */
    fun snapshot(serial: String, kind: SnapshotKind): Snapshot?

    /** Put [snapshot] in place of the last one. */
    fun saveSnapshot(serial: String, kind: SnapshotKind, snapshot: Snapshot)

    /** Every cell of the bolus journal kept for this pump, in the order they were made. */
    fun cells(serial: String): List<BolusCell>

    /** Store [cell]: a new one when its id is 0, then returned with its id; else in place of the one with its id. */
    fun putCell(serial: String, cell: BolusCell): BolusCell

    /** Drop the cells with these ids. */
    fun dropCells(serial: String, ids: Collection<Long>)

    fun clock(serial: String): ClockOffset?

    /** Store the offset the clock was just written in, and a line in the log with the count of bolus records seen. */
    fun saveClock(serial: String, offset: ClockOffset, bolusCount: Long)
    fun clockLog(serial: String): List<ClockLogEntry>
    fun pruneClockLog(serial: String, olderThanMs: Long)

    /** Drop the lines of the clock log that counted more bolus records than [bolusCount]; how many went. */
    fun dropClockLogAbove(serial: String, bolusCount: Long): Int

    /** A short note about the current state, by key; null when none. */
    fun state(serial: String, key: String): String?

    /** Store a note, or remove it with null. */
    fun putState(serial: String, key: String, value: String?)

    /** Drop everything kept for this pump: the answers, the bolus cells, the clock and its log, the notes. */
    fun forgetPump(serial: String)
}
