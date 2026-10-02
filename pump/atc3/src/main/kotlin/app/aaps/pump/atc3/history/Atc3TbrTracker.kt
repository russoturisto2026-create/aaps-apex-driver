package app.aaps.pump.atc3.history

import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.atc3.Atc3Const
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

    /**
     * @param byStamp true when [timestamp] is a stamp of the pump's -- the end object 0x0B gives,
     *   or the start of the temporary basal that replaced this one -- and so the minute of the
     *   pump's last snapshot, up to a minute before the moment itself; false when it is a moment
     *   known as such: an acknowledgement, a tick, the row's own end
     */
    data class Stop(val timestamp: Long, val endPumpId: Long, val byStamp: Boolean = false) : Atc3TbrAction

    /**
     * Our own temporary basal, written from the moment the pump acknowledged the command, now known
     * under the start the pump stamps it with. The row keeps its time; the note takes the identity.
     */
    data class Retime(
        val timestamp: Long,
        val utcSeconds: Long,
        val rate: Double,
        val durationMs: Long,
        val pumpId: Long,
        val type: PumpSync.TemporaryBasalType
    ) : Atc3TbrAction

    data object None : Atc3TbrAction
}

/** What the pump says about the temporary basal it is running, from object `0x0A`. */
data class PumpTbr(
    /** The start on the pump's clock, as the pump gives it, with seconds. */
    val atMs: Long,
    /**
     * The same start as whole seconds on a UTC calendar, used as an identity, null when unknown.
     *
     * Null when the pump did not fill its start clock in: every such record would otherwise carry
     * the same identity and no renewal could ever be told from the record before it.
     */
    val utcSeconds: Long?,
    /** How long it was started for, or null when the pump reported no duration. */
    val durationMs: Long?,
    /** The rate it was started at in the pump's raw steps, or null for a percentage or when unknown. */
    val rawRate: Int? = null
)

/**
 * Keeps AAPS's temporary basals the same as the pump's, with as few reads as that takes.
 *
 * Every tick reads Status V1, and Status V1 says whether a temporary basal is running, at what rate
 * and for how long it was started. That is compared with the temporary basal the ledger believes is
 * running, and nothing else decides whether anything changed: whose temporary basal it is does not
 * matter as long as it is the one expected. Only when the two disagree is anything else read:
 *
 * - **object `0x0A`**, the last command the pump accepted, when a temporary basal runs and the
 *   ledger has none, or one of another rate or duration, or one whose time is over: the new one is
 *   recorded from the pump's own start, and the one before ends exactly there;
 * - **object `0x0B`**, the last temporary basal that ended, when one has gone before its time and
 *   not by the driver's command: it says the moment it really ended.
 *
 * A temporary basal that ran its course ends at its start plus its duration, and one the driver
 * cancelled at the moment the pump acknowledged the cancel; neither needs a read.
 *
 * Our own temporary basal is recorded from the moment the pump acknowledged the command, which is
 * when it began. Object `0x0A`, read right after the command or on a later tick, gives the start
 * the pump stamps it with, and that is kept as its identity only: the pump stamps a temporary basal
 * with the time of its last status snapshot, the whole minute before the command, so the stamp
 * sits up to a minute before the real start. A row begun at the stamp would count up to a minute
 * of insulin the pump had not given, and the comparison with the pump's count would read it as a
 * shortfall.
 *
 * **Every other time written is the pump's**: a stranger's start from `0x0A`, the end from `0x0B`
 * or from the start and duration. The identity built from the pump's stamp is the same whichever
 * way the driver learned of a temporary basal, and nothing has to be matched again later.
 *
 * **A suspension is a temporary basal of zero, typed [PumpSync.TemporaryBasalType.PUMP_SUSPEND].**
 * Without it the suspension would never reach the insulin on board: the suspended running mode of
 * AAPS stops the loop from acting but does not touch the calculation. It has no start or end in the
 * pump's temporary basal objects, so it runs from the tick that saw it and is pushed out every tick.
 *
 * See [app.aaps.pump.atc3.comm.Atc3TbrStatus] and [app.aaps.pump.atc3.comm.Atc3FinishedTbr].
 */
object Atc3TbrTracker {

    /**
     * Whether object `0x0A` has to be read this tick: a temporary basal runs and the ledger does not
     * have it as it is.
     *
     * The rate and the duration are not the whole of it: a stranger's temporary basal at the very
     * rate and length the loop's ran at looks the same in Status V1. What tells it apart is how
     * long the pump says its temporary basal has been going (data 88..89): the loop's began at a
     * known moment, and a pump counting fewer minutes than have passed since is running another
     * one. Without this a stranger's temporary basal at the loop's own rate would go unseen and its
     * insulin would end up inside the loop's row.
     *
     * @param durationMs the duration Status V1 reports, or null when it reports none
     * @param elapsedMinutes how many whole minutes Status V1 says the running temporary basal has
     *   been going, or null when not known
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
        if (abs(rate - active.rate) > Atc3Const.DOSE_SCALE / 2) return true
        if (durationMs != null && otherDuration(active, durationMs)) return true
        if (elapsedMinutes != null && youngerThanOurs(active, elapsedMinutes, phoneNow)) return true
        return phoneNow >= active.startedAtMs + active.ownDurationMs + EXPIRY_GRACE_MS
    }

    /**
     * Whether the pump's temporary basal has been going for fewer minutes than ours has, by more
     * than the pump's whole-minute count and the two clocks can explain.
     */
    fun youngerThanOurs(active: ActiveTbr, elapsedMinutes: Int, phoneNow: Long): Boolean {
        val oursMinutes = (phoneNow - active.startedAtMs) / 60_000L
        return oursMinutes - elapsedMinutes > ELAPSED_SLACK_MINUTES
    }

    /**
     * Whether object `0x0B` has to be read this tick: the temporary basal the ledger has open is no
     * longer running and its time was not over. A cancel by the driver closes the ledger's entry
     * itself, so an entry still open here went by somebody else's hand.
     */
    fun needsEndRead(active: ActiveTbr?, suspended: Boolean, tbrRunning: Boolean, phoneNow: Long): Boolean =
        !suspended && !tbrRunning && active != null && !active.suspension &&
            phoneNow < active.startedAtMs + active.ownDurationMs

    /**
     * @param suspended the pump is stopped and delivering nothing at all, basal included
     * @param durationMs the duration Status V1 reports for the running temporary basal, or null
     * @param pumpStart the last command from object `0x0A`, when it was read this tick
     * @param endedAtMs when the temporary basal that has just gone really ended, from object 0x0B,
     *   when it was read and tied to the one being closed
     * @param pausedAtMs when the pump stopped, on its own account: the snapshot that the stop
     *   itself rebuilt, to the second, or else the minute of the last stop it keeps. Null when the
     *   pump gave no moment, and the stop is recorded from the tick.
     * @param resumedAtMs when the pump started again, on its own account: the snapshot it rebuilt on
     *   resuming, which carries the moment only until the next rebuild a minute later. Null when
     *   the pump gave no moment.
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
            // Where it really ended: the pump's end when it said so, the start plus the duration
            // when its time is over, and the tick only when neither is known. A stop ends where
            // the pump says it resumed.
            val expectedEnd = active.startedAtMs + active.ownDurationMs
            val end = when {
                active.suspension       -> resumedAtMs?.coerceAtLeast(active.startedAtMs) ?: phoneNow
                endedAtMs != null       -> endedAtMs
                phoneNow >= expectedEnd -> expectedEnd
                else                    -> phoneNow
            }
            val byStamp = !active.suspension && endedAtMs != null && end == endedAtMs
            return listOf(Atc3TbrAction.Stop(end, endIdOf(active), byStamp)) to null
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
            // The stop begins where the pump says it stopped, and not before the temporary basal it
            // interrupted began: the pump keeps the stop to the minute, and a temporary basal set
            // in the same minute can have begun after that minute's first second.
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
            // The pump resumed into a running temporary basal. Either it is the one the stop
            // interrupted, which the pump carries on under its old start -- object 0x0A hands that
            // start back, older than the stop -- and AAPS gets a new record of it from the moment
            // of resuming for what is left of its time; or it is one begun after the resume, which
            // is recorded from its own start like any other.
            val begin = pumpStart?.takeIf { it.utcSeconds != null }
            // A temporary basal begun after the stop is proof the pump was running by then: with no
            // moment of resuming from the pump, the resume is no later than that start.
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

        val otherRate = abs(rate - active.rate) > Atc3Const.DOSE_SCALE / 2
        val otherDuration = durationMs != null && otherDuration(active, durationMs)
        if (!otherRate && !otherDuration && pumpStart != null && isOurCommand(active, pumpStart)) {
            // The row stays where it is, at the acknowledgement; what the pump's stamp gives it is
            // its identity in the journal, and the note is what is written.
            val utcSeconds = pumpStart.utcSeconds!!
            val known = active.copy(pumpStartUtcSeconds = utcSeconds, pumpStartMs = pumpStart.atMs)
            return listOf(
                Atc3TbrAction.Retime(active.startedAtMs, utcSeconds, active.rate, active.ownDurationMs, active.pumpId, typeFor(active.rate))
            ) to known
        }
        val expired = !active.suspension &&
            phoneNow >= active.startedAtMs + active.ownDurationMs + EXPIRY_GRACE_MS
        // A start the pump gives that is later than the one the ledger holds is another temporary
        // basal, whatever its rate: object 0x0A was read because the pump's count of minutes said
        // so, and the pump keeps its starts to the whole minute.
        val otherStart = !active.suspension && pumpStart?.utcSeconds != null && active.pumpStartUtcSeconds != null &&
            pumpStart.utcSeconds > active.pumpStartUtcSeconds + SAME_START_SECONDS
        // Status V1 against the ledger is the whole test. The pump's last command only says where a
        // new one began -- or, when the count of minutes sent for it, that a new one began at all.
        if (!active.suspension && !otherRate && !otherDuration && !expired && !otherStart) return listOf(Atc3TbrAction.None) to active
        // Past its time at the same rate and duration, and the pump names the same start: the same
        // one running a little over its end, not a new one.
        if (expired && !otherRate && !otherDuration && pumpStart?.utcSeconds != null && sameStart(active, pumpStart)) {
            return listOf(Atc3TbrAction.None) to active
        }

        // The one running now began where the pump says, and the one before ended right there -- in
        // the same minute as the one before too, since the pump keeps its starts to the whole minute.
        // A start before the one being closed is not the new one's: object `0x0A` keeps the last
        // command, which after a pause can be the interrupted one, hours old. And the one before
        // cannot have run past its own time.
        val anchored = pumpStart?.takeIf { it.utcSeconds != null && describesRunning(it, active, rate) }
        val ranOut = if (active.suspension) Long.MAX_VALUE else active.startedAtMs + active.ownDurationMs
        // Not before the row being closed began: our own row begins at the acknowledgement, and a
        // stranger's set in the same minute carries a stamp before it. The one before is then cut
        // to nothing and the new one begins where it did, which is where the pump's journal puts
        // both -- see Atc3TbrBook.
        val newAt = (anchored?.atMs ?: phoneNow).coerceAtLeast(active.startedAtMs)
        val stop = Atc3TbrAction.Stop(minOf(newAt, ranOut), endIdOf(active), byStamp = anchored != null && newAt < ranOut)
        val (start, entry) = start(phoneNow, rate, runsFor, ours = false, begin = anchored, previous = active, rowAtMs = newAt)
        return listOf(stop, start) to entry
    }

    /**
     * A stop that began and ended between two ticks inside a running temporary basal, worked out
     * from the pump's count of delivered insulin: the row is cut at the stop, a stop of the given
     * length follows, and the temporary basal goes on from the end of it as a continuation under
     * its own identity -- the same three rows a stop seen by the ticks leaves behind.
     *
     * @return the actions, and the continuation the ledger now holds open, or null when the
     *   temporary basal's time was over by the end of the stop
     */
    fun splitByStop(active: ActiveTbr, stopMs: Long, lengthMs: Long): Pair<List<Atc3TbrAction>, ActiveTbr?> {
        require(!active.suspension) { "a stop cannot be cut into a stop" }
        val stopAt = maxOf(stopMs, active.startedAtMs)
        val resumeAt = stopAt + lengthMs
        val actions = ArrayList<Atc3TbrAction>(4)
        actions.add(Atc3TbrAction.Stop(stopAt, endIdOf(active)))
        val (pauseStart, pause) = start(stopAt, 0.0, lengthMs, ours = false, suspension = true, previous = active)
        actions.add(pauseStart)
        actions.add(Atc3TbrAction.Stop(resumeAt, endIdOf(pause)))
        val remaining = active.startedAtMs + active.ownDurationMs - resumeAt
        if (remaining <= 0L) return actions to null
        val (goOn, entry) = start(
            resumeAt, active.rate, remaining, ours = false, previous = pause,
            identityUtcSeconds = active.pumpStartUtcSeconds, pumpStartMs = active.pumpStartMs
        )
        actions.add(goOn)
        return actions to entry
    }

    /**
     * The temporary basal AAPS itself asked for, recorded from the moment the pump acknowledged
     * the command: that is when it began.
     *
     * @param pumpStart the pump's own record of it from object `0x0A`, read right after the
     *   command; when there is one the record carries the pump's stamp as its identity, see
     *   [ActiveTbr.pumpStartMs]
     * @param previous the temporary basal the ledger had open, which this one replaces
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
     * @param identityUtcSeconds the pump's own start of this temporary basal when the record is
     *   written from another moment: the continuation of one interrupted by a stop begins at the
     *   resume, and is still the temporary basal the pump started earlier
     * @param pumpStartMs that start on the phone's time scale, kept beside the row for telling a
     *   later start of the pump's from it
     * @param rowAtMs where the row begins: the pump's start for a stranger's temporary basal, the
     *   acknowledgement for our own
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

    /**
     * Whether the pump's last command is the temporary basal the ledger has.
     *
     * By the pump's own start when the ledger has it, to the second the pump keeps; otherwise by the
     * moment the pump acknowledged the command against the pump's start, which sit a few seconds
     * apart plus whatever the pump's clock is off by.
     */
    private fun sameStart(active: ActiveTbr, begin: PumpTbr): Boolean {
        val own = active.pumpStartUtcSeconds
        val theirs = begin.utcSeconds
        return if (own != null && theirs != null) abs(own - theirs) <= SAME_START_SECONDS
        else abs(active.startedAtMs - begin.atMs) <= UNVERIFIED_START_MS
    }

    /**
     * Whether the pump's last command is the temporary basal running now, not one before it.
     *
     * A later start than the one being closed is. The same start is only when the rate agrees with
     * Status V1: several temporary basals of one minute carry the same start, and after a pause
     * object `0x0A` hands back the interrupted one, whose start is the one being closed and whose
     * rate is its own.
     */
    private fun describesRunning(begin: PumpTbr, active: ActiveTbr, rate: Double): Boolean =
        begin.atMs > active.anchorMs ||
            begin.atMs == active.anchorMs && begin.rawRate != null &&
            begin.rawRate == Math.round(rate / Atc3Const.DOSE_SCALE).toInt()

    /**
     * Whether the pump's last command is our own temporary basal, recorded from the moment of
     * acknowledgement and not yet from the pump's start: the same duration, a start of its own, and
     * that start close to the acknowledgement.
     */
    private fun isOurCommand(active: ActiveTbr, begin: PumpTbr): Boolean =
        active.ours && active.pumpStartUtcSeconds == null && !active.suspension &&
            begin.utcSeconds != null &&
            begin.durationMs == active.ownDurationMs &&
            abs(active.startedAtMs - begin.atMs) <= UNVERIFIED_START_MS

    /**
     * Whether the duration Status V1 reports is not the temporary basal the ledger has.
     *
     * Status V1 carries the length the temporary basal was started for, and that is the ledger's
     * own length for a row begun at the pump's start. The continuation of one a stop interrupted
     * begins at the resume for what is left, so the two lengths differ while the end is the same:
     * compared by the end, such a row is the same temporary basal, and is not replaced by a copy of
     * itself on every tick.
     */
    private fun otherDuration(active: ActiveTbr, durationMs: Long): Boolean {
        if (durationMs == active.ownDurationMs) return false
        val pumpStart = active.pumpStartMs ?: return true
        return pumpStart + durationMs != active.startedAtMs + active.ownDurationMs
    }

    private fun endIdOf(active: ActiveTbr): Long = Atc3PumpId.tbrEndOf(active.pumpId)

    /**
     * How long past its own end a temporary basal is still taken for the same one when the pump
     * could not be asked. The pump runs its own timer and the two clocks are a few seconds apart.
     */
    private const val EXPIRY_GRACE_MS = 60_000L

    /** How far two readings of the pump's own start of one temporary basal may sit apart, seconds. */
    private const val SAME_START_SECONDS = 2L

    /**
     * How many minutes the pump's count of a running temporary basal may fall short of ours before
     * it is another temporary basal: one for the pump counting whole minutes, one for the clocks.
     */
    private const val ELAPSED_SLACK_MINUTES = 2L

    /**
     * How far the moment of acknowledgement may sit from the pump's start of the same command.
     *
     * The start object 0x0A gives is stamped on a whole minute and can stand up to a minute before
     * the acknowledgement; with a few seconds of the pump's clock running behind, a minute is not
     * enough. Rate and duration are compared as well, so the width takes nothing else in.
     */
    private const val UNVERIFIED_START_MS = 90_000L

    /**
     * A temporary basal of any rate, zero included, is a temporary basal: typed as a stop of the
     * pump, the loop's own zero temporary basals would show with the stop flag in AAPS's list of
     * treatments. Only a stop of the pump is [PumpSync.TemporaryBasalType.PUMP_SUSPEND];
     * the user's own disconnect comes with its type from AAPS, see [startedByAaps].
     */
    @Suppress("UNUSED_PARAMETER")
    private fun typeFor(rate: Double): PumpSync.TemporaryBasalType = PumpSync.TemporaryBasalType.NORMAL
}
