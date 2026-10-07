package app.aaps.pump.atc3.basal

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.pump.atc3.Atc3Const
import app.aaps.pump.atc3.basal.Atc3BasalPeriod
import app.aaps.pump.atc3.history.ActiveTbr
import app.aaps.pump.atc3.history.Atc3HistorySync
import app.aaps.pump.atc3.history.Atc3PumpId
import app.aaps.pump.atc3.history.Atc3PumpRegistration
import app.aaps.pump.atc3.protocol.Atc3StatusV1
import app.aaps.pump.atc3.state.Atc3PumpState
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.pump.atc3.trace.Atc3TraceCat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The basal of a passed stretch written as the pump's count gave it, in place of the rows of the
 * commands, see [Atc3BasalPeriod].
 */
@Singleton
class Atc3BasalFact @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val pumpSync: PumpSync,
    private val dateUtil: DateUtil,
    private val pumpState: Atc3PumpState,
    private val trace: Atc3Trace,
    private val registration: Atc3PumpRegistration,
    private val historySync: Atc3HistorySync
) {

    private val serial: String get() = pumpState.serialNumber

    /** A temporary basal row of AAPS, as far as closing a half hour cares. */
    data class JournalRow(
        val pumpId: Long?,
        val timestamp: Long,
        val durationMs: Long,
        val rate: Double,
        val isAbsolute: Boolean,
        /** True for a stop of the pump. */
        val stop: Boolean
    )

    /**
     * Write the basal of a passed period as the pump's count gives it: one row at `units / length` over
     * `[startMs, endMs)` in place of the rows of the commands. The temporary basal still running goes on
     * from [endMs] under its own id; one begun before the period keeps its row up to the period and goes
     * on after it; every other row begun in the period is taken out or moved past its end.
     *
     * @param rows the rows AAPS holds in the period, read before anything is written
     * @param pumpTbrDurationMs the length the running temporary basal was started for, or null when none runs
     * @param write how many times this period was written before: a period closed again takes a new row
     * @return false when the period cannot be closed at this read; the next read asks again
     */
    suspend fun writeBasalFact(
        startMs: Long,
        endMs: Long,
        units: Double,
        rows: List<JournalRow>,
        pumpTbrDurationMs: Long?,
        write: Int = 0
    ): Boolean {
        if (endMs <= startMs) return false
        if (!registration.ensureRegistered()) return false
        val active = historySync.ledgerNow().activeTbr
        var goesOn: ActiveTbr? = null
        if (active != null && active.startedAtMs < endMs) {
            // Our row not yet known under the pump's start is still being identified: left alone until it is.
            if (active.ours && !active.suspension && active.pumpStartUtcSeconds == null) {
                trace.event(Atc3TraceCat.HIST, "fact", "ok" to false, "why" to "open_row_unidentified")
                return false
            }
            val rowEnd = when {
                active.suspension                                         -> endMs + active.ownDurationMs
                active.pumpStartMs != null && pumpTbrDurationMs != null -> active.pumpStartMs + pumpTbrDurationMs
                else                                                      -> active.startedAtMs + active.ownDurationMs
            }
            if (rowEnd - endMs < Atc3HistorySync.MIN_SHAPED_SPAN_MS) {
                // Its time is over; the tick closes it, and the period after that.
                trace.event(Atc3TraceCat.HIST, "fact", "ok" to false, "why" to "open_row_over")
                return false
            }
            goesOn = active.copy(
                pumpId = if (active.startedAtMs < startMs) Atc3PumpId.tbrStartAfter(endMs, active.pumpId) else active.pumpId,
                startedAtMs = endMs,
                ownDurationMs = if (active.suspension) active.ownDurationMs else rowEnd - endMs
            )
        }

        val factId = Atc3PumpId.of(startMs, Atc3PumpId.KIND_BASAL_FACT, write)
        var removed = 0
        var cut = 0
        var moved = 0
        // The period's own row from the time before, when it is being closed again.
        if (write > 0) {
            val before = Atc3PumpId.of(startMs, Atc3PumpId.KIND_BASAL_FACT, write - 1)
            if (before != factId && pumpSync.invalidateTemporaryBasalWithPumpId(before, PumpType.ATC3, serial)) removed++
        }
        for (row in rows) {
            val id = row.pumpId ?: continue
            if (id == factId || id == active?.pumpId) continue
            if (row.timestamp >= endMs) continue
            if (row.timestamp >= startMs) {
                // One closed after the read the period ends at keeps the part past it.
                val beyond = row.timestamp + row.durationMs - endMs
                if (beyond >= Atc3HistorySync.MIN_SHAPED_SPAN_MS) {
                    pumpSync.syncTemporaryBasalWithPumpId(
                        timestamp = endMs, rate = row.rate, duration = beyond, isAbsolute = row.isAbsolute,
                        type = null, pumpId = id, pumpType = PumpType.ATC3, pumpSerial = serial
                    )
                    historySync.storeLedger(historySync.ledgerNow().withOurTbrRow(id, endMs))
                    moved++
                } else if (pumpSync.invalidateTemporaryBasalWithPumpId(id, PumpType.ATC3, serial)) removed++
            } else if (row.timestamp + row.durationMs > startMs) {
                // Type left as it is: null changes nothing of an existing row.
                pumpSync.syncTemporaryBasalWithPumpId(
                    timestamp = row.timestamp, rate = row.rate, duration = startMs - row.timestamp, isAbsolute = row.isAbsolute,
                    type = null, pumpId = id, pumpType = PumpType.ATC3, pumpSerial = serial
                )
                cut++
            }
        }
        if (goesOn != null && active != null && goesOn.pumpId == active.pumpId) {
            pumpSync.syncTemporaryBasalWithPumpId(
                timestamp = goesOn.startedAtMs, rate = goesOn.rate, duration = goesOn.ownDurationMs, isAbsolute = true,
                type = null, pumpId = goesOn.pumpId, pumpType = PumpType.ATC3, pumpSerial = serial
            )
            historySync.storeLedger(historySync.ledgerNow().withActiveTbr(goesOn).withOurTbrRow(goesOn.pumpId, goesOn.startedAtMs))
        } else if (goesOn != null && active != null) {
            // Begun before the period: cut where the period begins, and going on after it as a row of its own.
            pumpSync.syncTemporaryBasalWithPumpId(
                timestamp = active.startedAtMs, rate = active.rate, duration = startMs - active.startedAtMs, isAbsolute = true,
                type = null, pumpId = active.pumpId, pumpType = PumpType.ATC3, pumpSerial = serial
            )
            cut++
            pumpSync.syncTemporaryBasalWithPumpId(
                timestamp = goesOn.startedAtMs, rate = goesOn.rate, duration = goesOn.ownDurationMs, isAbsolute = true,
                type = if (active.suspension) PumpSync.TemporaryBasalType.PUMP_SUSPEND else PumpSync.TemporaryBasalType.NORMAL,
                pumpId = goesOn.pumpId, pumpType = PumpType.ATC3, pumpSerial = serial
            )
            var updated = historySync.ledgerNow().withActiveTbr(goesOn).withOurTbrEnd(active.pumpId, startMs)
            active.pumpStartUtcSeconds?.let { own ->
                val keepFromUtcSeconds = Atc3StatusV1.wallClockUtcSeconds(dateUtil.now() - Atc3Const.RECONCILE_MAX_AGE_MS)
                val startedForMinutes = ((pumpTbrDurationMs ?: active.ownDurationMs) / 60_000L).toInt()
                updated = updated.withOurTbr(
                    own, goesOn.rate, startedForMinutes, keepFromUtcSeconds,
                    goesOn.pumpId, pumpStart = true, rowMs = goesOn.startedAtMs, asOrdered = active.ours
                )
            }
            historySync.storeLedger(updated)
        }
        val rate = units / ((endMs - startMs) / 3_600_000.0)
        // A period the pump stood stopped through and through stays a stop in AAPS's list.
        val inPeriod = rows.filter { it.timestamp < endMs && it.timestamp + it.durationMs > startMs }
        val type = if (units <= 0.0 && inPeriod.isNotEmpty() && inPeriod.all { it.stop }) PumpSync.TemporaryBasalType.PUMP_SUSPEND
        else PumpSync.TemporaryBasalType.NORMAL
        val written = pumpSync.syncTemporaryBasalWithPumpId(
            timestamp = startMs, rate = rate, duration = endMs - startMs, isAbsolute = true,
            type = type, pumpId = factId, pumpType = PumpType.ATC3, pumpSerial = serial
        )
        aapsLogger.debug(
            LTag.PUMP,
            "ATC3: basal from $startMs to $endMs by the pump's count, $units U at $rate U/h, id $factId; " +
                "$removed row(s) taken out, $cut cut, $moved moved to its end, " + (goesOn?.let { "id ${it.pumpId} goes on from $endMs" } ?: "none running")
        )
        trace.event(
            Atc3TraceCat.HIST, "fact",
            "ok" to true, "from" to startMs, "to" to endMs, "s" to (endMs - startMs) / 1000,
            "units" to units, "rate" to rate, "id" to factId, "new" to written,
            "removed" to removed, "cut" to cut, "moved" to moved, "goes_on" to (goesOn?.pumpId ?: 0L)
        )
        return true
    }
}
