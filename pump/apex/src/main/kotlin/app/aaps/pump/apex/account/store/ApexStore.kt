package app.aaps.pump.apex.account.store

/** What the driver keeps a snapshot of: the bolus journal it accounts from, the status and today's day of the daily statistics. */
enum class SnapshotKind { BOLUS, STATUS, DAILY }

/**
 * The last answer of the pump to one question, as it came: the frames newest first for a journal,
 * one frame for the status. The next answer is compared with it and then takes its place.
 */
data class Snapshot(val frames: List<ByteArray>, val readAtMs: Long)

/**
 * The driver's record of its own bolus: the moment the pump accepted it, to the second, what was
 * asked, and where the journal stood, so that the pump's record of it is found by its place.
 */
data class Action(
    val id: Long = 0L,
    /** UTC milliseconds of the pump's acknowledgement. */
    val acceptedAtMs: Long,
    /** Units of the bolus. */
    val amount: Double,
    /** The AAPS type of the row, as the core names it, for example SMB or NORMAL. */
    val type: String,
    /** The count of records the journal had given so far, read just before the command: the action's record comes after it. */
    val journalTopSeq: Long,
    /** The number of the record found for this action, once found. */
    val recordSeq: Long? = null,
    /** Units the pump reported delivered: from its progress frames while the bolus runs, from its completion frame at the end. */
    val seenUnits: Double? = null,
    /** The pump's record of the action was found, or the action needs none. */
    val done: Boolean = false
)

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

    /** All actions kept for this pump, oldest first. */
    fun actions(serial: String): List<Action>

    /** Store a new action; returns it with its id. */
    fun addAction(serial: String, action: Action): Action
    fun updateAction(serial: String, action: Action)

    /** Drop every action of the pump. */
    fun dropActions(serial: String)

    fun clock(serial: String): ClockOffset?

    /** Store the offset the clock was just written in, and a line in the log with the count of bolus records seen. */
    fun saveClock(serial: String, offset: ClockOffset, bolusCount: Long)
    fun clockLog(serial: String): List<ClockLogEntry>
    fun pruneClockLog(serial: String, olderThanMs: Long)

    /** A short note about the current state, by key; null when none. */
    fun state(serial: String, key: String): String?

    /** Store a note, or remove it with null. */
    fun putState(serial: String, key: String, value: String?)

    /** Drop everything kept for this pump: the answers, the actions, the clock and its log, the notes. */
    fun forgetPump(serial: String)
}
