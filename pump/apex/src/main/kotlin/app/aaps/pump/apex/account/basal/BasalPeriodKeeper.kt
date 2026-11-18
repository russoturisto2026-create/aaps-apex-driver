package app.aaps.pump.apex.account.basal

import app.aaps.core.data.model.TB
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.apex.account.AapsCarrier
import app.aaps.pump.apex.account.AapsToday
import app.aaps.pump.apex.account.store.ApexStore
import app.aaps.pump.apex.account.time.ApexClock
import app.aaps.pump.apex.deep.BasalPeriodDeep
import app.aaps.pump.apex.keys.ApexStringKey
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.pulse.PumpPicture
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.runBlocking

/**
 * The exact basal record, see [BasalPeriod]: an overlay that writes the temporary basal rows over
 * again at each heartbeat. The period under way is kept in the store, so that a restart of AAPS
 * does not lose it. Its bounds are whole minutes: the heartbeat of minute M brings the pump up to
 * M:00, and M:00 is the mark that ends one period and begins the next. At the mark the rows are
 * replaced by the plan of [BasalPeriodRows]: one row of what went in before the mark, and after it
 * only the temporary basal the status shows running. Asked only at a heartbeat whose reconciliation
 * succeeded, after the bolus journal is settled: boluses first, then the basal. No bolus ran then,
 * and the records explain the pump's numbers, see `Reconciliation`; so nothing here asks the pump.
 * With the mode off nothing is written, and the period, if one was under way, is dropped.
 */
@SingleIn(AppScope::class)
@Inject
class BasalPeriodKeeper(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val store: ApexStore,
    private val pumpSync: PumpSync,
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val aapsToday: AapsToday,
    private val carrier: AapsCarrier,
    private val trace: ApexTrace
) {

    private val deep = BasalPeriodDeep(aapsLogger, trace)

    /** The mode as the switch on the pump screen holds it. */
    fun mode(): ExactBasalMode = ExactBasalMode.of(preferences.get(ApexStringKey.ExactBasal))

    /** The heartbeat of [picture], whose reconciliation succeeded: the period under way is closed when due, and the next one begun. */
    fun onHeartbeat(serial: String, picture: PumpPicture) {
        val start = BasalPeriod.Mark.decode(store.state(serial, STATE_PERIOD))
        if (mode() == ExactBasalMode.OFF) {
            PeriodBoluses(store, serial).clear()
            if (start != null) {
                store.putState(serial, STATE_PERIOD, null)
                aapsLogger.info(LTag.PUMP, "Apex: the exact basal record is off; the period begun at ${start.atMs} is dropped, its rows stay as ordered")
                trace.event(ApexTraceCat.STATE, "basal_period", "state" to "dropped", "why" to "mode_off", "from" to start.atMs)
            }
            return
        }
        val now = picture.delivered()
        val atMs = ApexClock.minuteStart(picture.heartbeatAtMs)
        if (start == null) {
            PeriodBoluses(store, serial).clear()
            begin(serial, atMs, now, "mode_on")
            return
        }
        val boluses = if (start.delivered is Delivered.Count && BasalPeriod.isDue(start.atMs, atMs)) PeriodBoluses(store, serial).sum() else 0.0
        when (val step = BasalPeriod.step(start, atMs, now, boluses)) {
            BasalPeriod.Step.Wait        -> Unit

            is BasalPeriod.Step.Postpone -> postponed(start.atMs, step.why)

            is BasalPeriod.Step.Restart  -> {
                PeriodBoluses(store, serial).clear()
                begin(serial, atMs, now, step.why)
            }

            is BasalPeriod.Step.Close    -> {
                val ordered = runBlocking { aapsToday.basalBetween(start.atMs, atMs) }
                // Basal by the count is never more than the pump's maximum basal gives, see BasalPeriodDeep.
                val capped = if (now is Delivered.Count) deep.capAtMaxBasal(start.atMs, atMs, step.units, maxBasalPerHour(picture)) else null
                write(serial, start.atMs, atMs, capped?.basalUnits ?: step.units, ordered, if (now is Delivered.Count) "count" else "day record", picture)
                capped?.writes?.forEach { carrier.carry(it, serial) }
                deep.bolusesOverCount(start.atMs, atMs, step.overUnits)
                // The boluses of the closed period go with its mark in one step: apart, a restart between them
                // would close the period again without its boluses, and they would count as basal too.
                store.transaction {
                    PeriodBoluses(store, serial).clear()
                    begin(serial, atMs, now, "closed")
                }
            }
        }
    }

    /** The period is put off to the next heartbeat, for [why]. */
    private fun postponed(fromMs: Long?, why: String) {
        aapsLogger.info(LTag.PUMP, "Apex: the basal period${fromMs?.let { " from $it" } ?: ""} is not closed or begun at this heartbeat, $why; the next one asks again")
        trace.event(ApexTraceCat.STATE, "basal_period", "state" to "postponed", "why" to why, "from" to fromMs)
    }

    /** The period begins at this heartbeat; the one before it, when it was not closed, leaves its rows as ordered. */
    private fun begin(serial: String, atMs: Long, now: Delivered, why: String) {
        when (now) {
            is Delivered.DayRecord -> {
                val today = now.days.firstOrNull()
                if (today == null) {
                    aapsLogger.warn(LTag.PUMP, "Apex: no basal period begins at $atMs, the daily statistics came with no day")
                    return
                }
                store.putState(serial, STATE_PERIOD, BasalPeriod.Mark(atMs, today).encode())
                if (why != "closed") aapsLogger.info(LTag.PUMP, "Apex: a basal period begins at $atMs, $why; day basal ${today.basalUnits} temporary ${today.tbrUnits} U")
                trace.event(ApexTraceCat.STATE, "basal_period", "state" to "begun", "why" to why, "at" to atMs, "basal" to today.basalUnits, "tbr" to today.tbrUnits)
            }

            is Delivered.Count     -> {
                store.putState(serial, STATE_PERIOD, BasalPeriod.Mark(atMs, now).encode())
                if (why != "closed") aapsLogger.info(LTag.PUMP, "Apex: a basal period begins at $atMs, $why; the pump's count ${now.units} U")
                trace.event(ApexTraceCat.STATE, "basal_period", "state" to "begun", "why" to why, "at" to atMs, "count" to now.units)
            }
        }
    }

    /**
     * The rows of the period closed at the mark [endMs], see [BasalPeriodRows]: one row of the basal
     * that went in, and after the mark the temporary basal the status of [picture] shows running.
     * [ordered] is what the rows ordered over the period, for the log. [source] says what the pump's
     * number came from, for the log.
     */
    private fun write(serial: String, startMs: Long, endMs: Long, units: Double, ordered: Double?, source: String, picture: PumpPicture) {
        val rows = runBlocking {
            persistenceLayer.getTemporaryBasalsActiveAt(startMs) + persistenceLayer.getTemporaryBasalsStartingFromTimeToTime(startMs, picture.readAtMs + ROWS_AHEAD_MS, true)
        }
            .distinctBy { it.id }
            .filter { it.ids.pumpType == PumpType.APEX && it.ids.pumpSerial == serial && it.ids.pumpId != null }
            .map { BasalPeriodRows.Row(it.ids.pumpId!!, it.timestamp, it.duration, pumpRateOf(it), it.isAbsolute, it.type == TB.Type.PUMP_SUSPEND) }
        val status = picture.status.status
        val running = if (status.tbrActive) BasalPeriodRows.Running(status.tbrRate, (status.tbrDurationMinutes - status.tbrElapsedMinutes) * ApexClock.MINUTE_MS) else null
        val plan = BasalPeriodRows.plan(rows, startMs, endMs, units, running)
        val ops = plan.ops
        var removed = 0
        for (op in ops) when (op) {
            is BasalPeriodRows.Op.Place  -> runBlocking {
                val type = if (op.suspend) PumpSync.TemporaryBasalType.PUMP_SUSPEND else PumpSync.TemporaryBasalType.NORMAL
                pumpSync.syncTemporaryBasalWithPumpId(op.startMs, PumpRate(op.rate), op.durationMs, op.isAbsolute, type, op.pumpId, PumpType.APEX, serial)
            }

            is BasalPeriodRows.Op.Remove -> {
                runBlocking { pumpSync.invalidateTemporaryBasalWithPumpId(op.pumpId, PumpType.APEX, serial) }
                removed++
            }
        }
        val after = plan.running
        val fact = plan.fact
        val placed = ops.size - removed - 1 - (if (after != null) 1 else 0)
        aapsLogger.info(
            LTag.PUMP,
            "Apex: basal period from $startMs to $endMs, ${(endMs - startMs) / 1000} s: $units U by the pump's $source" +
                (ordered?.let { ", $it U ordered" } ?: ", the order is not known") +
                "; row ${fact.pumpId} at ${fact.rate} U/h; ${rows.size} row(s) touched the period, $placed placed again, $removed taken out" +
                (after?.let { "; after the mark row ${it.pumpId} at ${it.rate} U/h for ${it.durationMs / 1000} s" } ?: "; after the mark no temporary basal runs")
        )
        trace.event(
            ApexTraceCat.STATE, "basal_period", "state" to "closed",
            "from" to startMs, "to" to endMs, "s" to (endMs - startMs) / 1000, "units" to units, "rate" to fact.rate,
            "ordered" to ordered, "diff" to ordered?.let { units - it }, "rows" to rows.size, "placed" to placed, "removed" to removed
        )
    }

    /** The pump's maximum basal, U/h: from its settings of the status, else the one AAPS holds, which the driver keeps the same, see `ApexLimits`. */
    private fun maxBasalPerHour(picture: PumpPicture): Double? =
        picture.status.settings?.maxBasalRaw?.takeIf { it > 0 }?.let { it * ApexProtocol.DOSE_SCALE }
            ?: preferences.get(DoubleKey.ApsMaxBasal).takeIf { it > 0.0 }

    /** The row's rate in the pump's units: AAPS keeps an absolute rate multiplied by the insulin concentration. */
    private fun pumpRateOf(row: TB): Double =
        if (row.isAbsolute) row.rate / (runBlocking { profileFunction.getProfile(row.timestamp) }?.insulinConcentration() ?: 1.0) else row.rate

    companion object {

        /** The period under way, see [BasalPeriod.Mark.encode]; absent with the mode off. */
        const val STATE_PERIOD = "basalPeriod"

        /** How far past the read rows are looked for: a row placed in the minute under way lies a little after the mark. */
        private const val ROWS_AHEAD_MS = ApexClock.HOUR_MS
    }
}
