package app.aaps.pump.apex.pulse

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.pump.apex.aaps.ApexLimits
import app.aaps.pump.apex.clock.ApexPumpClock
import app.aaps.pump.apex.basal.TbrCommand
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.state.ApexPumpState
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn

/** The pump's heartbeat, in its turn on the AAPS queue: the reconciliation runs when this command does. */
class ApexHeartbeatTick(val heartbeatAtMs: Long) : CustomCommand {

    override val statusDescription: String = "Apex HEARTBEAT"
}

/** What a new link needs once: the heartbeat period set, the firmware version known. Nothing is read of the pump's state. */
class ApexLinkSetup : CustomCommand {

    override val statusDescription: String = "Apex LINK SETUP"
}

/**
 * The driver's one procedure with the pump, run in answer to its heartbeat. A heartbeat that came
 * during our bolus is answered when the bolus is settled. A beat off its grid is first moved onto it,
 * see [HeartbeatGrid].
 *
 * 1. Status V1, and at once: does a bolus run, see [Reconciliation.quietStatus]. If one runs, nothing
 *    more is read.
 * 2. The rest of the picture, see [PictureReader]. The journals are read only when the objects before
 *    them show something new, and always at the first reconciliation since AAPS started.
 * 3. The reconciliation, see [Reconciliation]: the picture against the status kept at the last one
 *    that succeeded. Then the pump's maximum bolus and basal are made AAPS's, see [ApexLimits].
 * 4. The clock, when it is due, see [ApexPumpClock.writeIfDue]. A write closes a period. So when the
 *    clock is due, step 2 reads every object, the journals too, and the clock is written only when
 *    step 3 succeeded. Else it waits for the next heartbeat. Nothing of this runs before the first
 *    reconciliation since AAPS started has succeeded: the clock waits for the heartbeat after it.
 *
 * A reconciliation that breaks on an error of the driver's code keeps nothing either, see [CodeFault].
 *
 * A heartbeat the pump does not answer keeps nothing: the exchange's watch on the link counts the
 * silence, and the next heartbeat runs the procedure again.
 */
@SingleIn(AppScope::class)
@Inject
class HeartbeatTick(
    private val aapsLogger: AAPSLogger,
    private val reader: PictureReader,
    private val grid: HeartbeatGrid,
    private val manager: ApexManager,
    private val pumpState: ApexPumpState,
    private val pumpClock: ApexPumpClock,
    private val reconciliation: Reconciliation,
    private val tbrCommand: TbrCommand,
    private val limits: ApexLimits,
    private val report: PictureReport,
    private val codeFault: CodeFault,
    private val startTries: StartTries
) {

    /** True once a reconciliation that read every object succeeded since AAPS started, see [startIfNeeded]. */
    @Volatile private var startDone = false

    /** The picture of the reconciliation that runs now: a fault of the code reports it, see [CodeFault]. */
    @Volatile private var picture: PumpPicture? = null

    /** False when the pump did not answer and nothing was kept. */
    fun run(heartbeatAtMs: Long): Boolean {
        grid.onHeartbeat(heartbeatAtMs)
        // A pump the driver does not work with is not reconciled, see ApexPumpState.unsupported. Nor one whose
        // version is not read yet: its records cannot be read rightly without the protocol, see ApexStamp.
        if (pumpState.unsupported || pumpState.version == null) return true
        val serial = pumpState.serialNumber
        val first = reader.readStatus() ?: return false.also { if (!startDone) startTries.failed() }
        // The picture goes on from the last Status V1 the bolus check read.
        val card = if (serial.isEmpty()) first else reconciliation.quietStatus(serial, first) ?: return true
        if (serial.isEmpty()) return reader.read(heartbeatAtMs, serial, card) != null
        val offsetMs = pumpClock.offsetMs(serial)
        // Until the first reconciliation since AAPS started succeeds nothing else is done: the clock waits for the heartbeat after it.
        // A clock write closes a period: everything before it is read and settled first.
        val clockDue = if (startDone) pumpClock.dueReason(serial, offsetMs) else null
        val readAll = when {
            !startDone         -> PictureReader.ReadAll.START
            codeFault.standing -> PictureReader.ReadAll.AGAIN
            clockDue != null   -> PictureReader.ReadAll.CLOCK
            else               -> null
        }
        // A reconciliation broken on an error of the code keeps nothing, and the pump answered.
        return codeFault.guard(true, { picture }) {
            val result = reconcile(heartbeatAtMs, serial, card, readAll, offsetMs, heartbeat = true) ?: return@guard false
            limits.onHeartbeat(serial, result.picture.status)
            if (clockDue != null) {
                if (result.succeeded) writeClockIfDue(serial)
                else aapsLogger.warn(LTag.PUMP, "Apex: the clock is due, $clockDue, but the reconciliation before it did not succeed; it waits for the next heartbeat")
            }
            true
        }
    }

    /**
     * Before a command of ours and at the link setup: true when the first reconciliation since AAPS started
     * is done and a status is kept, or the reconciliation, run now, succeeded, see [Reconciliation]. The first one
     * reads every object, the bolus journal too: the cells are made or laid over the journal and AAPS gets
     * what it has not, whatever the statistics show. False: no command of ours goes before it. The basal
     * period waits for the next heartbeat, so that its bounds stay on the heartbeats. False too while a
     * reconciliation broke on an error of the code, see [CodeFault]: the heartbeat tries it again. Tries that do
     * not pass are counted, see [StartTries].
     */
    fun startIfNeeded(): Boolean {
        if (pumpState.unsupported || pumpState.version == null || codeFault.standing) return false
        val serial = pumpState.serialNumber
        if (serial.isEmpty()) return true
        if (startDone && reconciliation.hasStatusKept(serial)) return true
        val first = reader.readStatus() ?: return false.also { startTries.failed() }
        val card = reconciliation.quietStatus(serial, first) ?: return false
        val offsetMs = pumpClock.offsetMs(serial)
        return codeFault.guard(false, { picture }) {
            reconcile(System.currentTimeMillis(), serial, card, PictureReader.ReadAll.START, offsetMs, heartbeat = false)?.succeeded == true
        }
    }

    /**
     * The one step of the heartbeat and of the start: the rest of the picture after [card], read as [readAll]
     * says, reconciled, logged and shown. Null when the picture was not read. Off the heartbeats ([heartbeat]
     * false) the basal period waits, and the comparison of the day is not shown: it starts at a heartbeat, see
     * `DayWindow`.
     */
    private fun reconcile(atMs: Long, serial: String, card: ApexPumpState.StatusCard, readAll: PictureReader.ReadAll?, offsetMs: Long, heartbeat: Boolean): Reconciliation.Result? {
        picture = null
        val read = reader.read(atMs, serial, card, readAll)?.also { picture = it } ?: return null.also { if (!startDone) startTries.failed() }
        val result = reconciliation.run(serial, read, offsetMs, withPeriod = heartbeat)
        if (result.succeeded) codeFault.clear()
        if (!startDone) if (result.succeeded) startTries.done() else startTries.failed()
        // Until the first one since AAPS started succeeds, every object is read.
        if (result.succeeded) startDone = true
        report.describe(result.picture)
        if (result.succeeded) manager.pumpReconciled()
        report.show(serial, result.picture, reconciled = heartbeat && result.succeeded)
        return result
    }

    /**
     * The clock, last, after a reconciliation that read everything and succeeded. Around a zone change
     * the running temporary basal is ended by the command and ordered again after the write, both as the
     * commands do it for the loop.
     */
    private fun writeClockIfDue(serial: String) {
        val offsetMs = pumpClock.offsetMs(serial)
        pumpClock.writeIfDue(
            serial, offsetMs,
            endTbr = { tbrCommand.cancel(serial).failure },
            restartTbr = { rate, minutes -> tbrCommand.set(serial, rate, minutes, PumpSync.TemporaryBasalType.NORMAL).failure }
        )
    }
}
