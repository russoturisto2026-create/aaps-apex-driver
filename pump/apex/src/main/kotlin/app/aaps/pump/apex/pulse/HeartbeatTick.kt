package app.aaps.pump.apex.pulse

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.MidnightTime
import app.aaps.pump.apex.ApexLimits
import app.aaps.pump.apex.ApexPumpClock
import app.aaps.pump.apex.account.store.ApexStore
import app.aaps.pump.apex.account.store.SnapshotKind
import app.aaps.pump.apex.account.time.ApexClock
import app.aaps.pump.apex.command.TbrCommand
import app.aaps.pump.apex.events.EventApexPumpDataChanged
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.protocol.ApexResponseFrame
import app.aaps.pump.apex.state.ApexAbilities
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat
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
 *    them show something new.
 * 3. The reconciliation, see [Reconciliation]: the picture against the status kept at the last one
 *    that succeeded. Then the pump's maximum bolus and basal are made AAPS's, see [ApexLimits].
 * 4. The clock, when it is due, see [ApexPumpClock.writeIfDue]. A write closes a period. So when the
 *    clock is due, step 2 reads every object, the journals too, and the clock is written only when
 *    step 3 succeeded. Else it waits for the next heartbeat.
 *
 * A heartbeat the pump does not answer keeps nothing: the exchange's watch on the link counts the
 * silence, and the next heartbeat runs the procedure again.
 */
@SingleIn(AppScope::class)
@Inject
class HeartbeatTick(
    private val aapsLogger: AAPSLogger,
    private val rxBus: RxBus,
    private val reader: PictureReader,
    private val grid: HeartbeatGrid,
    private val manager: ApexManager,
    private val store: ApexStore,
    private val pumpState: ApexPumpState,
    private val pumpClock: ApexPumpClock,
    private val reconciliation: Reconciliation,
    private val tbrCommand: TbrCommand,
    private val limits: ApexLimits,
    private val trace: ApexTrace
) {

    /** False when the pump did not answer and nothing was kept. */
    fun run(heartbeatAtMs: Long): Boolean {
        grid.onHeartbeat(heartbeatAtMs)
        val serial = pumpState.serialNumber
        val first = reader.readStatus() ?: return false
        // The picture goes on from the last Status V1 the bolus check read.
        val card = if (serial.isEmpty()) first else reconciliation.quietStatus(serial, first, dayRecord(serial)) ?: return true
        if (serial.isEmpty()) return reader.read(heartbeatAtMs, serial, card) != null
        val offsetMs = ApexClock.offsetMs(store, serial, pumpClock.phoneOffsetMs())
        // A clock write closes a period: everything before it is read and settled first.
        val clockDue = pumpClock.dueReason(serial, offsetMs)
        val picture = reader.read(heartbeatAtMs, serial, card, readAll = clockDue != null) ?: return false
        val result = reconciliation.run(serial, picture, offsetMs)
        describe(result.picture)
        // Only a reconciliation that succeeded is shown as a comparison of the day.
        show(serial, result.picture, reconciled = result.succeeded)
        limits.onHeartbeat(serial, result.picture.status)
        if (clockDue != null) {
            if (result.succeeded) writeClockIfDue(serial)
            else aapsLogger.warn(LTag.PUMP, "Apex: the clock is due, $clockDue, but the reconciliation before it did not succeed; it waits for the next heartbeat")
        }
        return true
    }

    /**
     * Before a command of ours and at the link setup: true when a status is kept, or the start
     * reconciliation, run now, succeeded, see [Reconciliation]. False: no command of ours goes before it.
     * The basal period waits for the next heartbeat, so that its bounds stay on the heartbeats.
     */
    fun startIfNeeded(): Boolean {
        val serial = pumpState.serialNumber
        if (serial.isEmpty()) return true
        if (reconciliation.hasStatusKept(serial, dayRecord(serial))) return true
        val first = reader.readStatus() ?: return false
        val card = reconciliation.quietStatus(serial, first, dayRecord(serial)) ?: return false
        val picture = reader.read(System.currentTimeMillis(), serial, card) ?: return false
        val result = reconciliation.run(serial, picture, ApexClock.offsetMs(store, serial, pumpClock.phoneOffsetMs()), withPeriod = false)
        describe(result.picture)
        show(serial, result.picture, reconciled = result.succeeded)
        return result.succeeded
    }


    /** True when the pump gives the daily statistics, see [ApexAbilities]. */
    private fun dayRecord(serial: String): Boolean = ApexAbilities.of(store, serial, pumpState.version?.firmwareText).dayRecord

    /** The few numbers the screen shows of the picture. */
    private fun show(serial: String, picture: PumpPicture, reconciled: Boolean) {
        if (reconciled) manager.pumpReconciled()
        val today = picture.days?.answer?.entries?.firstOrNull()?.record
        val topBolus = topBolus(serial, picture)
        val offsetMs = ApexClock.offsetMs(store, serial, pumpClock.phoneOffsetMs())
        pumpState.lastPicture = PictureSummary(
            heartbeatAtMs = picture.heartbeatAtMs,
            readAtMs = picture.readAtMs,
            counterUnits = picture.status.deliveredTodayUnits,
            dayBolusUnits = today?.bolusUnits, dayBasalUnits = today?.basalUnits, dayTbrUnits = today?.tbrUnits,
            topBolusAtMs = topBolus?.let { ApexClock.momentOf(it.clockSeconds, offsetMs) },
            topBolusUnits = topBolus?.deliveredUnits,
            clockErrorMs = manager.pumpClockErrorMs(),
            brokenJournals = brokenJournals(picture),
            reconciled = reconciled,
            dayFrom = if (!reconciled || picture.days != null && today == null) null else dayWindowStart(serial, picture, today, offsetMs)
        )
        rxBus.send(EventApexPumpDataChanged())
    }

    /**
     * Where the comparison of the day starts, see [DayWindow]. [today] is null only on a pump that gives
     * no daily statistics: then the count of Status V1 stands alone: the window runs on the total of the day, with no parts.
     */
    private fun dayWindowStart(serial: String, picture: PumpPicture, today: ApexDailyRecord?, offsetMs: Long): DayWindow.Count {
        val counter = picture.status.deliveredTodayUnits
        val count = if (today != null) {
            DayWindow.Count(picture.heartbeatAtMs, today.dayClockSeconds, offsetMs, counter, today.bolusUnits, today.basalUnits + today.tbrUnits)
        } else {
            DayWindow.Count.ofTotal(picture.heartbeatAtMs, picture.status.status.clockSeconds, offsetMs, counter)
        }
        return pumpState.dayWindow.move(count, store.clock(serial)?.writtenAtMs, MidnightTime.calc(picture.heartbeatAtMs))
    }

    /** The newest bolus record: of the journal read at this heartbeat, or of the copy kept when it was not read. */
    private fun topBolus(serial: String, picture: PumpPicture): ApexBolusRecord? {
        picture.boluses?.let { return it.answer.entries.firstOrNull()?.record }
        return store.snapshot(serial, SnapshotKind.BOLUS)?.frames?.firstOrNull()?.let { ApexBolusRecord.decode(ApexResponseFrame(it)) }
    }

    private fun brokenJournals(picture: PumpPicture): List<String> = listOfNotNull(
        "bolus".takeIf { picture.boluses?.repeatedBroken == true }, "days".takeIf { picture.days?.repeatedBroken == true }
    )

    private fun describe(picture: PumpPicture) {
        val card = picture.status
        val today = picture.days?.answer?.entries?.firstOrNull()?.record
        val boluses = picture.boluses?.answer
        val topBolus = boluses?.entries?.firstOrNull()?.record
        aapsLogger.info(
            LTag.PUMP,
            "Apex: heartbeat picture in ${picture.readAtMs - picture.heartbeatAtMs} ms: counter ${card.deliveredTodayUnits} U, reservoir ${card.reservoirUnits} U, " +
                (if (card.tbrActive) "temporary basal ${card.tbrRate} U/h for ${card.tbrDurationMinutes} min, ${card.tbrElapsedMinutes} gone" else "no temporary basal") +
                ", alarms ${card.activeAlarmCodes}, suspended ${card.suspended}" +
                (if (boluses == null) "; bolus journal not read, ${picture.bolusGate.reason}" else "; top bolus ${topBolus?.let { "key ${it.clockSeconds} ${it.deliveredUnits} U" } ?: "none"} of ${boluses.recordCount}") +
                (today?.let { "; today bolus ${it.bolusUnits} basal ${it.basalUnits} temporary ${it.tbrUnits} U" } ?: "; the pump gives no daily statistics") +
                (picture.tbr?.let { facts -> facts.end?.let { "; last finished ${it.amountAsked} since key ${it.startClockSeconds}, ${it.resultText} at key ${it.endClockSeconds}" } ?: (if (facts.endAsked) "; nothing finished since a reset" else "") } ?: "; temporary basal part not read, ${picture.tbrGate.reason}") +
                (if (brokenJournals(picture).isNotEmpty()) "; a journal came broken every time" else "")
        )
        trace.event(
            ApexTraceCat.STATE, "picture",
            "ms" to picture.readAtMs - picture.heartbeatAtMs, "counter" to card.deliveredTodayUnits, "reservoir" to card.reservoirUnits,
            "tbr" to if (card.tbrActive) "${card.tbrRate}/${card.tbrDurationMinutes}" else null,
            "alarms" to card.activeAlarmCodes, "suspended" to card.suspended,
            "bolusTop" to topBolus?.clockSeconds, "bolusCount" to boluses?.recordCount,
            "dayBolus" to today?.bolusUnits, "dayBasal" to today?.basalUnits, "dayTbr" to today?.tbrUnits,
            "broken" to brokenJournals(picture).ifEmpty { null }
        )
    }

    /**
     * The clock, last, after a reconciliation that read everything and succeeded. Around a zone change
     * the running temporary basal is ended by the command and ordered again after the write, both as the
     * commands do it for the loop.
     */
    private fun writeClockIfDue(serial: String) {
        val offsetMs = ApexClock.offsetMs(store, serial, pumpClock.phoneOffsetMs())
        pumpClock.writeIfDue(
            serial, offsetMs, System.currentTimeMillis(),
            endTbr = { tbrCommand.cancel(serial).failure },
            restartTbr = { rate, minutes -> tbrCommand.set(serial, rate, minutes, PumpSync.TemporaryBasalType.NORMAL).failure }
        )
    }
}
