package app.aaps.pump.apex.pulse

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.MidnightTime
import app.aaps.pump.apex.clock.ApexPumpClock
import app.aaps.pump.apex.bolus.BolusCells
import app.aaps.pump.apex.store.ApexStore
import app.aaps.pump.apex.clock.ApexClock
import app.aaps.pump.apex.events.EventApexPumpDataChanged
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.protocol.ApexResponseFrame
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.state.DayWindow
import app.aaps.pump.apex.state.PictureSummary
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/** What a reconciliation read, told: a line of the log, see [describe], and the few numbers of the screen, see [show]. */
@SingleIn(AppScope::class)
@Inject
class PictureReport(
    private val aapsLogger: AAPSLogger,
    private val rxBus: RxBus,
    private val store: ApexStore,
    private val pumpState: ApexPumpState,
    private val pumpClock: ApexPumpClock,
    private val trace: ApexTrace
) {

    /** The few numbers the screen shows of the picture; [reconciled] false: no comparison of the day. */
    fun show(serial: String, picture: PumpPicture, reconciled: Boolean) {
        val offsetMs = pumpClock.offsetMs(serial)
        val today = picture.days.answer.entries.firstOrNull()?.record?.let { atMark(it, picture.heartbeatAtMs, offsetMs) }
        val topBolus = topBolus(serial, picture)
        pumpState.lastPicture = PictureSummary(
            heartbeatAtMs = picture.heartbeatAtMs,
            dayBolusUnits = today?.bolusUnits, dayBasalUnits = today?.basalUnits, dayTbrUnits = today?.tbrUnits,
            topBolusAtMs = topBolus?.let { ApexClock.momentOf(it.clockSeconds, offsetMs) },
            topBolusUnits = topBolus?.deliveredUnits,
            reconciled = reconciled,
            dayFrom = if (!reconciled || today == null) null else dayWindowStart(serial, picture, today, offsetMs)
        )
        rxBus.send(EventApexPumpDataChanged())
    }

    /** Where the comparison of the day starts, see [DayWindow]. */
    private fun dayWindowStart(serial: String, picture: PumpPicture, today: ApexDailyRecord, offsetMs: Long): DayWindow.Count {
        val count = DayWindow.Count(picture.heartbeatAtMs, today.dayClockSeconds, offsetMs, picture.status.deliveredTodayUnits, today.bolusUnits, today.basalUnits + today.tbrUnits)
        return pumpState.dayWindow.move(count, store.clock(serial)?.writtenAtMs, MidnightTime.calc(picture.heartbeatAtMs))
    }

    /** The newest bolus record: of the journal read at this heartbeat, or of the cells kept when it was not read. */
    private fun topBolus(serial: String, picture: PumpPicture): ApexBolusRecord? {
        picture.boluses?.let { return it.answer.entries.firstOrNull()?.record }
        return BolusCells(store, serial).keptFrames()?.firstOrNull()?.let { ApexBolusRecord.decode(ApexResponseFrame(it)) }
    }

    private fun brokenJournals(picture: PumpPicture): List<String> = listOfNotNull(
        "days".takeIf { picture.days.repeatedBroken }
    )

    /** One line of the log and of the trace: what the picture holds. */
    fun describe(picture: PumpPicture) {
        val card = picture.status
        val today = picture.days.answer.entries.firstOrNull()?.record
        val boluses = picture.boluses?.answer
        val topBolus = boluses?.entries?.firstOrNull()?.record
        aapsLogger.info(
            LTag.PUMP,
            "Apex: heartbeat picture in ${picture.readAtMs - picture.heartbeatAtMs} ms: counter ${card.deliveredTodayUnits} U, reservoir ${card.reservoirUnits} U, " +
                (if (card.tbrActive) "temporary basal ${card.tbrRate} U/h for ${card.tbrDurationMinutes} min, ${card.tbrElapsedMinutes} gone" else "no temporary basal") +
                ", alarms ${card.activeAlarmCodes}, suspended ${card.suspended}" +
                (if (boluses == null) "; bolus journal not read, ${picture.bolusGate.reason}" else "; top bolus ${topBolus?.let { "key ${it.clockSeconds} ${it.deliveredUnits} U" } ?: "none"} of ${boluses.recordCount}") +
                (today?.let { "; today bolus ${it.bolusUnits} basal ${it.basalUnits} temporary ${it.tbrUnits} U" } ?: "; the daily statistics hold no day") +
                (picture.tbr?.let { facts -> facts.end?.let { "; last finished ${it.amountAsked} since key ${it.startClockSeconds}, ${it.resultText} at key ${it.endClockSeconds}" } ?: (if (facts.endAsked) "; nothing finished since a reset" else "") } ?: "; temporary basal part not read, ${picture.tbrGate.reason}") +
                (if (brokenJournals(picture).isNotEmpty()) "; a journal came broken the same at every read" else "")
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

    companion object {

        /**
         * [day] as it stood at the mark of the heartbeat at [heartbeatAtMs]. At the pump's midnight the new day holds
         * nothing at the mark: what the statistics show in it was given in the seconds before they were read.
         */
        fun atMark(day: ApexDailyRecord, heartbeatAtMs: Long, offsetMs: Long): ApexDailyRecord =
            if (ApexClock.momentOf(day.dayClockSeconds, offsetMs) == ApexClock.minuteStart(heartbeatAtMs)) ApexDailyRecord(day.index, 0.0, 0.0, 0.0, day.dayClockSeconds) else day
    }
}
