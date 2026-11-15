package app.aaps.pump.apex.pulse

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.MidnightTime
import app.aaps.pump.apex.ApexLimits
import app.aaps.pump.apex.ApexPumpClock
import app.aaps.pump.apex.account.basal.BasalPeriodKeeper
import app.aaps.pump.apex.account.basal.Delivered
import app.aaps.pump.apex.account.bolus.BolusAccount
import app.aaps.pump.apex.account.bolus.BolusCountMark
import app.aaps.pump.apex.account.bolus.BolusJournal
import app.aaps.pump.apex.account.bolus.BolusSpanEnd
import app.aaps.pump.apex.account.tbr.TbrJournal
import app.aaps.pump.apex.account.store.ApexStore
import app.aaps.pump.apex.account.store.SnapshotKind
import app.aaps.pump.apex.account.time.ApexClock
import app.aaps.pump.apex.command.TbrCommand
import app.aaps.pump.apex.events.EventApexPumpDataChanged
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.protocol.ApexResponseFrame
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
 * The driver's one procedure with the pump, run in answer to its heartbeat and at no other time.
 * First the heartbeat itself: a beat off the half-hour grid is moved onto it, see [HeartbeatGrid].
 * Step 1, the picture: the objects read by [PictureReader], the journals among them only when the
 * objects before them show something new. The bolus journal, when read, is settled against the
 * copy kept from the last answer, see [BolusJournal]: that is where every bolus row comes from,
 * ours and others'. The temporary basal part, read while a temporary basal runs or a row is held
 * open, is settled by [TbrJournal] against the open row: another's temporary basals get their
 * rows, the one held open that ended has its row closed. Then the basal
 * period, when the exact basal record is on, see [BasalPeriodKeeper]: boluses first, then the
 * basal. Step 3, the clock: written at the end, by the error the
 * heartbeat showed, when that is outside the corridor, see [ApexPumpClock.writeIfDue]. Before it the
 * pump's maximum bolus and basal are made AAPS's, see [ApexLimits].
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
    private val bolusJournal: BolusJournal,
    private val tbrJournal: TbrJournal,
    private val periodKeeper: BasalPeriodKeeper,
    private val tbrCommand: TbrCommand,
    private val limits: ApexLimits,
    private val trace: ApexTrace
) {

    /** False when the pump did not answer and nothing was kept. */
    fun run(heartbeatAtMs: Long): Boolean {
        grid.onHeartbeat(heartbeatAtMs)
        val serial = pumpState.serialNumber
        val picture = reader.read(heartbeatAtMs, serial) ?: return false
        if (serial.isEmpty()) return true
        val offsetMs = ApexClock.offsetMs(store, serial, pumpClock.phoneOffsetMs())
        // The boluses are settled first. Only a faulty cell on a pump without the daily statistics asks for the
        // temporary basal rows of this heartbeat before that: its amount is the pump's count less the basal of the AAPS rows.
        var tbrSettled = false
        val settleTbr = {
            if (!tbrSettled) {
                tbrSettled = true
                settleTbrPart(serial, picture, offsetMs)
            }
            true
        }
        val settled = picture.boluses?.let { bolusJournal.settle(serial, it.answer, spanEnd(picture), offsetMs, picture.readAtMs, comparedAtMs = picture.heartbeatAtMs, closeBasal = settleTbr) } ?: true
        settleTbr()
        keep(serial, picture, keepDay = settled)
        periodKeeper.onHeartbeat(serial, picture, settled)
        describe(picture)
        show(serial, picture, duringBolus = false)
        limits.onHeartbeat(picture.status.settings)
        writeClockIfDue(serial)
        return true
    }

    /**
     * The heartbeat that came while our bolus runs, answered between its progress frames. The
     * pump answers while it delivers, so the basal and temporary basal part of the picture is read
     * on time: Status V1, the daily statistics, the last temporary basal command, the temporary
     * basal journal by its gate. The bolus part is not judged: the record is not written until the
     * bolus ends, the count of Status V1 already holds a part of it, and the bolus command settles
     * it by the record when it ends. Nothing is kept of this picture either: the copies the next
     * heartbeat compares with stay those of the last heartbeat before the bolus, so that the bolus,
     * settled by then, is counted once and the deep look is not measured from a count taken half
     * way through it. The basal period is closed by this heartbeat all the same, see
     * [BasalPeriodKeeper]: the basal and temporary basal fields of the day record do not depend on
     * the bolus, and the period's own mark is not one of those copies. The grid and the clock are
     * left to the heartbeats between boluses too.
     */
    fun runDuringBolus(heartbeatAtMs: Long) {
        val serial = pumpState.serialNumber
        aapsLogger.info(LTag.PUMP, "Apex: heartbeat during our bolus, the picture is read between its progress frames; nothing kept, the bolus part waits for the record")
        trace.event(ApexTraceCat.STATE, "heartbeat_during_bolus", "at" to heartbeatAtMs)
        val picture = reader.readDuringBolus(heartbeatAtMs, serial) ?: return
        if (serial.isEmpty()) return
        settleTbrPart(serial, picture, ApexClock.offsetMs(store, serial, pumpClock.phoneOffsetMs()))
        periodKeeper.onHeartbeat(serial, picture, settled = true, duringBolus = true)
        describe(picture)
        show(serial, picture, duringBolus = true)
    }

    /**
     * The temporary basal rows made to stand as the status [card] shows the pump now, outside a
     * heartbeat: asked by the bolus command before the pump's count closes a faulty cell. A
     * reconciliation like any other, see [TbrGate]. False when the pump did not give the facts: the
     * rows are not known to stand.
     */
    fun settleBasalAt(serial: String, card: ApexPumpState.StatusCard): Boolean {
        val gate = TbrGate.decide(tbrJournal.last(serial)?.status, card.status)
        val facts = if (gate.read) reader.tbrFacts(serial) ?: return false else null
        tbrJournal.settle(serial, card, facts, ApexClock.offsetMs(store, serial, pumpClock.phoneOffsetMs()), card.readAtMs)
        return true
    }

    /**
     * The temporary basal part of the heartbeat's [picture] settled: the heartbeat brings the pump up
     * to the start of its minute, see `TbrJournal.settle`.
     */
    private fun settleTbrPart(serial: String, picture: PumpPicture, offsetMs: Long) =
        tbrJournal.settle(serial, picture.status, picture.tbr, offsetMs, ApexClock.minuteStart(picture.heartbeatAtMs))

    /** The screen's newest bolus is [top], the record read when our bolus ended: the rest of the picture stays that of the last heartbeat. */
    fun showBolusEnded(serial: String, top: ApexBolusRecord) {
        val shown = pumpState.lastPicture ?: return
        val offsetMs = ApexClock.offsetMs(store, serial, pumpClock.phoneOffsetMs())
        pumpState.lastPicture = shown.withTopBolus(ApexClock.momentOf(top.clockSeconds, offsetMs), top.deliveredUnits)
        rxBus.send(EventApexPumpDataChanged())
    }

    /** The few numbers the screen shows of the picture. */
    private fun show(serial: String, picture: PumpPicture, duringBolus: Boolean) {
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
            duringBolus = duringBolus,
            dayFrom = if (duringBolus || picture.days != null && today == null) null else dayWindowStart(serial, picture, today, offsetMs)
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

    /**
     * What the pump gave at this heartbeat to tell how much went in as boluses over the span: the
     * daily statistics read after the journal, or, on a pump without them, the count of Status V1.
     */
    private fun spanEnd(picture: PumpPicture): BolusSpanEnd? =
        picture.days?.let { BolusSpanEnd.DayRecord(it.answer) } ?: (picture.delivered() as? Delivered.Count)?.let { BolusSpanEnd.Count(picture.heartbeatAtMs, it, picture.status.reservoirUnits) }

    /**
     * The picture kept for the next heartbeat: the status, and today's day, the start of the next bolus span.
     * [keepDay] false: the bolus journal of the picture was not settled, and the span it closes stays open from the day kept before.
     * A pump without the daily statistics keeps its count the same way, see [BolusCountMark]; when none is kept yet it is kept
     * all the same, or a span that cannot begin would never close.
     */
    private fun keep(serial: String, picture: PumpPicture, keepDay: Boolean) {
        store.transaction {
            val count = spanEnd(picture) as? BolusSpanEnd.Count
            if (keepDay) {
                val days = picture.days
                // With no daily statistics nothing compares the settled boluses with a day, so the list is cleared here.
                if (days != null) BolusGate(store, serial).keep(days, picture.readAtMs) else BolusAccount(store, serial).clearSettledSinceHeartbeat()
                count?.let { BolusCountMark(store, serial).move(it) }
            } else {
                count?.let { BolusCountMark(store, serial).moveIfAbsent(it) }
            }
        }
    }

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
     * The clock, last. Around a zone change the running temporary basal is ended by the command and
     * ordered again after the write, both as the commands do it for the loop. The journals need no
     * settling here: the rows come from the commands, and the records are matched by their order.
     */
    private fun writeClockIfDue(serial: String) {
        val offsetMs = ApexClock.offsetMs(store, serial, pumpClock.phoneOffsetMs())
        pumpClock.writeIfDue(
            serial, offsetMs, System.currentTimeMillis(),
            endTbr = { tbrCommand.cancel(serial).failure },
            settleJournals = { true },
            restartTbr = { rate, minutes -> tbrCommand.set(serial, rate, minutes, PumpSync.TemporaryBasalType.NORMAL).failure }
        )
    }
}
