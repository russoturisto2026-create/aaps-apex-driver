package app.aaps.pump.atc3.basal

import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.atc3.Atc3Const
import app.aaps.pump.atc3.history.ActiveTbr
import app.aaps.pump.atc3.history.Atc3PumpId
import app.aaps.pump.atc3.protocol.Atc3Protocol
import kotlin.math.abs

/** What is to be done about the temporary basal the pump is reporting. */
sealed interface Atc3TbrAction {

    data class Start(
        val timestamp: Long,
        val rate: Double,
        val durationMs: Long,
        val pumpId: Long,
        val type: PumpSync.TemporaryBasalType
    ) : Atc3TbrAction

    data class Extend(
        val timestamp: Long,
        val rate: Double,
        val durationMs: Long,
        val pumpId: Long,
        val type: PumpSync.TemporaryBasalType
    ) : Atc3TbrAction

    data class Stop(val timestamp: Long, val endPumpId: Long) : Atc3TbrAction

    data object None : Atc3TbrAction
}

/** The running temporary basal as the pump records it. */
data class PumpTbr(
    /** Its start on the pump's clock. */
    val atMs: Long,
    /** The same start as a UTC-calendar key, or null when the pump left it empty. */
    val utcSeconds: Long?,
    /** How long it was started for, or null when not reported. */
    val durationMs: Long?,
    /** Its rate in raw steps, or null for a percentage or when unknown. */
    val rawRate: Int? = null
)

/**
 * Keeps AAPS's temporary basals the same as the pump's with as few reads as that takes. Status V1,
 * read every tick, is compared with what the ledger has open; only a disagreement costs a read: the
 * running temporary basal's record for a start, the last finished one for a real end.
 *
 * - One that ran its course ends at its start plus its duration; one the driver cancelled at the
 *   acknowledgement of the cancel.
 * - Ours begins at the acknowledgement of the command; the pump's stamp, up to a minute earlier, is
 *   kept as its identity only. Every other time written is the pump's.
 * - A stopped pump is a temporary basal of zero, [PumpSync.TemporaryBasalType.PUMP_SUSPEND], pushed on
 *   every tick: the only way a stop reaches insulin on board.
 */
object Atc3TbrTracker {

    /**
     * Whether the running temporary basal's record is to be read: one runs and the ledger does not have
     * it as it is. Another's at the loop's own rate and length shows by the minutes the pump says it has run.
     *
     * @param durationMs the duration Status V1 reports, or null
     * @param elapsedMinutes the minutes Status V1 says it has run, or null
     */
    fun needsStartRead(
        active: ActiveTbr?,
        suspended: Boolean,
        tbrRunning: Boolean,
        rate: Double,
        durationMs: Long?,
        phoneNow: Long,
        elapsedMinutes: Int? = null
    ): Boolean {
        if (suspended || !tbrRunning) return false
        if (active == null || active.suspension) return true
        if (active.ours && active.pumpStartUtcSeconds == null) return true
        if (abs(rate - active.rate) > Atc3Protocol.DOSE_SCALE / 2) return true
        if (durationMs != null && otherDuration(active, durationMs)) return true
        if (elapsedMinutes != null && youngerThanOurs(active, elapsedMinutes, phoneNow)) return true
        return phoneNow >= active.startedAtMs + active.ownDurationMs + EXPIRY_GRACE_MS
    }

    /** Whether the pump's temporary basal has run fewer minutes than ours, beyond what whole minutes and two clocks explain. */
    fun youngerThanOurs(active: ActiveTbr, elapsedMinutes: Int, phoneNow: Long): Boolean {
        val oursMinutes = (phoneNow - active.startedAtMs) / 60_000L
        return oursMinutes - elapsedMinutes > ELAPSED_SLACK_MINUTES
    }

    /** Whether the last finished temporary basal is to be read: the one open ended before its time, by another hand. */
    fun needsEndRead(active: ActiveTbr?, suspended: Boolean, tbrRunning: Boolean, phoneNow: Long): Boolean =
        !suspended && !tbrRunning && active != null && !active.suspension &&
            phoneNow < active.startedAtMs + active.ownDurationMs

    /**
     * @param suspended the pump is stopped, basal included
     * @param durationMs the duration Status V1 reports, or null
     * @param pumpStart the running temporary basal's record, when read this tick
     * @param endedAtMs when the one gone really ended, from the last finished one, when read and tied to it
     * @param pausedAtMs when the pump says it stopped, or null: the stop is then recorded from the tick
     * @param resumedAtMs when the pump says it started again, or null
     */
    fun step(
        active: ActiveTbr?,
        suspended: Boolean,
        tbrRunning: Boolean,
        rate: Double,
        durationMs: Long?,
        pumpStart: PumpTbr?,
        phoneNow: Long,
        endedAtMs: Long? = null,
        pausedAtMs: Long? = null,
        resumedAtMs: Long? = null
    ): Pair<List<Atc3TbrAction>, ActiveTbr?> {
        if (!suspended && !tbrRunning) {
            if (active == null) return emptyList<Atc3TbrAction>() to null
            // Its real end: the pump's when it said, start plus duration when over, the tick otherwise.
            val expectedEnd = active.startedAtMs + active.ownDurationMs
            val end = when {
                active.suspension       -> resumedAtMs?.coerceAtLeast(active.startedAtMs) ?: phoneNow
                endedAtMs != null       -> endedAtMs
                phoneNow >= expectedEnd -> expectedEnd
                else                    -> phoneNow
            }
            return listOf(Atc3TbrAction.Stop(end, endIdOf(active))) to null
        }

        if (suspended) {
            if (active != null && active.suspension) {
                // Pushed out every tick: a stop must never lapse by itself while the pump is stopped.
                return listOf(
                    Atc3TbrAction.Extend(
                        active.startedAtMs, 0.0, phoneNow - active.startedAtMs + Atc3Const.SUSPEND_HORIZON_MS,
                        active.pumpId, PumpSync.TemporaryBasalType.PUMP_SUSPEND
                    )
                ) to active
            }
            // From the pump's stop, and not before the temporary basal it interrupted began.
            val pausedAt = (pausedAtMs ?: phoneNow).let { if (active != null) maxOf(it, active.startedAtMs) else it }
            val stop = active?.let { Atc3TbrAction.Stop(pausedAt, endIdOf(it)) }
            val (start, entry) = start(pausedAt, 0.0, Atc3Const.SUSPEND_HORIZON_MS, ours = false, suspension = true, previous = active)
            return listOfNotNull(stop, start) to entry
        }

        val runsFor = durationMs ?: Atc3Const.TBR_HORIZON_MS
        if (active == null) {
            val (start, entry) = start(phoneNow, rate, runsFor, ours = false, begin = pumpStart)
            return listOf(start) to entry
        }

        if (active.suspension) {
            // Resumed into a running temporary basal: the interrupted one, carried on under its old start, gets
            // a new row from the resume for what is left; one begun after the resume is recorded like any other.
            val begin = pumpStart?.takeIf { it.utcSeconds != null }
            // A temporary basal begun after the stop shows the pump ran by then.
            val resumedAt = (resumedAtMs ?: phoneNow).coerceAtLeast(active.startedAtMs).let { at ->
                if (resumedAtMs == null && begin != null && begin.atMs > active.startedAtMs) minOf(at, begin.atMs) else at
            }
            val stop = Atc3TbrAction.Stop(resumedAt, endIdOf(active))
            if (begin != null && begin.atMs > resumedAt) {
                val (start, entry) = start(begin.atMs, rate, runsFor, ours = false, begin = begin, previous = active)
                return listOf(stop, start) to entry
            }
            val remaining = begin?.durationMs?.let { begin.atMs + it - resumedAt }?.takeIf { it > 0L }
            val (start, entry) = start(
                resumedAt, rate, remaining ?: runsFor, ours = false, previous = active,
                identityUtcSeconds = begin?.utcSeconds, pumpStartMs = begin?.atMs
            )
            return listOf(stop, start) to entry
        }

        val otherRate = abs(rate - active.rate) > Atc3Protocol.DOSE_SCALE / 2
        val otherDuration = durationMs != null && otherDuration(active, durationMs)
        if (!otherRate && !otherDuration && pumpStart != null && isOurCommand(active, pumpStart)) {
            // The row stays at the acknowledgement; the stamp is its identity, kept in the ledger only.
            val known = active.copy(pumpStartUtcSeconds = pumpStart.utcSeconds!!, pumpStartMs = pumpStart.atMs)
            return listOf(Atc3TbrAction.None) to known
        }
        val expired = !active.suspension &&
            phoneNow >= active.startedAtMs + active.ownDurationMs + EXPIRY_GRACE_MS
        // A later start than the ledger's is another temporary basal, whatever its rate.
        val otherStart = !active.suspension && pumpStart?.utcSeconds != null && active.pumpStartUtcSeconds != null &&
            pumpStart.utcSeconds > active.pumpStartUtcSeconds + SAME_START_SECONDS
        // Status V1 against the ledger is the whole test.
        if (!active.suspension && !otherRate && !otherDuration && !expired && !otherStart) return listOf(Atc3TbrAction.None) to active
        // Past its time at the same rate, duration and start: the same one, a little over its end.
        if (expired && !otherRate && !otherDuration && pumpStart?.utcSeconds != null && sameStart(active, pumpStart)) {
            return listOf(Atc3TbrAction.None) to active
        }

        // The new one began where the pump says, and the one before ended there; a start before the one
        // closed is not the new one's.
        val anchored = pumpStart?.takeIf { it.utcSeconds != null && describesRunning(it, active, rate) }
        val ranOut = if (active.suspension) Long.MAX_VALUE else active.startedAtMs + active.ownDurationMs
        // Not before the row closed began; the one before is then cut to nothing, as the journal has both.
        val newAt = (anchored?.atMs ?: phoneNow).coerceAtLeast(active.startedAtMs)
        val stop = Atc3TbrAction.Stop(minOf(newAt, ranOut), endIdOf(active))
        val (start, entry) = start(phoneNow, rate, runsFor, ours = false, begin = anchored, previous = active, rowAtMs = newAt)
        return listOf(stop, start) to entry
    }

    /**
     * The temporary basal AAPS asked for, from the acknowledgement, when it began.
     *
     * @param pumpStart the pump's record of it read right after the command: its stamp becomes the identity, see [ActiveTbr.pumpStartMs]
     * @param previous the temporary basal it replaces
     */
    fun startedByAaps(
        ackAtMs: Long,
        rate: Double,
        durationMs: Long,
        pumpStart: PumpTbr? = null,
        previous: ActiveTbr? = null,
        type: PumpSync.TemporaryBasalType = PumpSync.TemporaryBasalType.NORMAL
    ): Pair<Atc3TbrAction, ActiveTbr> =
        start(ackAtMs, rate, durationMs, ours = true, begin = pumpStart, previous = previous, rowAtMs = ackAtMs, type = type)

    /**
     * @param identityUtcSeconds the pump's own start when the row begins elsewhere: a continuation begins at the resume
     * @param pumpStartMs that start on the phone's time, for telling a later start from it
     * @param rowAtMs where the row begins: the pump's start for another's, the acknowledgement for ours
     */
    private fun start(
        atMs: Long,
        rate: Double,
        durationMs: Long,
        ours: Boolean,
        begin: PumpTbr? = null,
        suspension: Boolean = false,
        previous: ActiveTbr? = null,
        identityUtcSeconds: Long? = begin?.utcSeconds,
        pumpStartMs: Long? = begin?.atMs,
        rowAtMs: Long = begin?.atMs ?: atMs,
        type: PumpSync.TemporaryBasalType = PumpSync.TemporaryBasalType.NORMAL
    ): Pair<Atc3TbrAction, ActiveTbr> {
        val runsFor = begin?.durationMs ?: durationMs
        val pumpId = Atc3PumpId.tbrStartAfter(rowAtMs, previous?.pumpId)
        val rowType = if (suspension) PumpSync.TemporaryBasalType.PUMP_SUSPEND else type
        val action = Atc3TbrAction.Start(rowAtMs, rate, runsFor, pumpId, rowType)
        return action to ActiveTbr(pumpId, rowAtMs, rate, ours, runsFor, identityUtcSeconds, suspension, pumpStartMs = pumpStartMs)
    }

    /** Whether the pump's record is the temporary basal the ledger has: by the pump's start when known, else by the acknowledgement. */
    private fun sameStart(active: ActiveTbr, begin: PumpTbr): Boolean {
        val own = active.pumpStartUtcSeconds
        val theirs = begin.utcSeconds
        return if (own != null && theirs != null) abs(own - theirs) <= SAME_START_SECONDS
        else abs(active.startedAtMs - begin.atMs) <= UNVERIFIED_START_MS
    }

    /** Whether the pump's record is the one running now: a later start, or the same start at Status V1's rate. */
    private fun describesRunning(begin: PumpTbr, active: ActiveTbr, rate: Double): Boolean =
        begin.atMs > active.anchorMs ||
            begin.atMs == active.anchorMs && begin.rawRate != null &&
            begin.rawRate == Math.round(rate / Atc3Protocol.DOSE_SCALE).toInt()

    /** Whether the pump's record is our own temporary basal still without the pump's start: same duration, a start near the acknowledgement. */
    private fun isOurCommand(active: ActiveTbr, begin: PumpTbr): Boolean =
        active.ours && active.pumpStartUtcSeconds == null && !active.suspension &&
            begin.utcSeconds != null &&
            begin.durationMs == active.ownDurationMs &&
            abs(active.startedAtMs - begin.atMs) <= UNVERIFIED_START_MS

    /** Whether Status V1's duration is not the ledger's; a continuation is compared by its end. */
    private fun otherDuration(active: ActiveTbr, durationMs: Long): Boolean {
        if (durationMs == active.ownDurationMs) return false
        val pumpStart = active.pumpStartMs ?: return true
        return pumpStart + durationMs != active.startedAtMs + active.ownDurationMs
    }

    private fun endIdOf(active: ActiveTbr): Long = Atc3PumpId.tbrEndOf(active.pumpId)

    /** How long past its end a temporary basal is still the same one when the pump could not be asked. */
    private const val EXPIRY_GRACE_MS = 60_000L

    /** How far two readings of the pump's own start of one temporary basal may sit apart, seconds. */
    private const val SAME_START_SECONDS = 2L

    /** How many minutes the pump's count may fall short of ours before it is another: whole minutes, and the clocks. */
    private const val ELAPSED_SLACK_MINUTES = 2L

    /** How far the acknowledgement may sit from the pump's stamp of the same command: a minute, and the clock. */
    private const val UNVERIFIED_START_MS = 90_000L
}
