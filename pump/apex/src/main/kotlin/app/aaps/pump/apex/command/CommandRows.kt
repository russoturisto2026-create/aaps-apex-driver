package app.aaps.pump.apex.command

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.runBlocking

/**
 * The temporary basal rows the driver's own commands make in AAPS. A row is known by the moment
 * the pump began it; a stop is known by the moment the pump ended it. Bolus rows are not made here:
 * they come from the pump's records only, see `BolusJournal`.
 */
@SingleIn(AppScope::class)
@Inject
class CommandRows(
    private val aapsLogger: AAPSLogger,
    private val pumpSync: PumpSync,
    private val trace: ApexTrace
) {

    /** The pump runs a temporary basal of ours from [startAtMs]: a row with the rate and the minutes it took. */
    fun tbrStarted(serial: String, startAtMs: Long, rate: Double, minutes: Int, type: PumpSync.TemporaryBasalType) {
        val created = runBlocking { pumpSync.syncTemporaryBasalWithPumpId(startAtMs, PumpRate(rate), minutes * 60_000L, true, type, startAtMs, PumpType.APEX, serial) }
        aapsLogger.info(LTag.PUMP, "Apex: temporary basal row $rate U/h for $minutes min from $startAtMs ${if (created) "written" else "was there"}")
        trace.event(ApexTraceCat.STATE, "row_tbr_started", "at" to startAtMs, "rate" to rate, "minutes" to minutes, "type" to type.name)
    }

    /** The pump ended its temporary basal at [endAtMs]: the running row is cut there. */
    fun tbrStopped(serial: String, endAtMs: Long) {
        val cut = runBlocking { pumpSync.syncStopTemporaryBasalWithPumpId(endAtMs, endAtMs, PumpType.APEX, serial) }
        aapsLogger.info(LTag.PUMP, "Apex: temporary basal row ${if (cut) "cut" else "not cut, none was running in AAPS"} at $endAtMs")
        trace.event(ApexTraceCat.STATE, "row_tbr_stopped", "at" to endAtMs, "cut" to cut)
    }
}
