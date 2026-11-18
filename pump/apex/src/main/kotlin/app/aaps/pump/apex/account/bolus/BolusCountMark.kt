package app.aaps.pump.apex.account.bolus

import app.aaps.pump.apex.account.basal.BasalPeriod
import app.aaps.pump.apex.account.basal.Delivered
import app.aaps.pump.apex.account.store.ApexStore

/**
 * The heartbeat a pump without the daily statistics counts its boluses from: when it came, the
 * pump's count of the day then, and its reservoir. It moves at each reconciliation that succeeded, see
 * `Reconciliation`, so the span of the next answer runs from it. It has its own place in the store: the
 * basal period has another.
 */
class BolusCountMark(private val store: ApexStore, private val serial: String) {

    /** What was kept at [atMs]: [count] and the pump's [reservoirUnits], null when it was not kept. */
    class Kept(val atMs: Long, val count: Delivered.Count, val reservoirUnits: Double?) {

        /** One line for the store: the count part as the basal period writes it, then the reservoir in thousandths of a unit when known. */
        fun encode(): String = BasalPeriod.Mark(atMs, count).encode() + (reservoirUnits?.let { SEPARATOR + Math.round(it * MILLI) } ?: "")

        companion object {

            private const val SEPARATOR = ";"
            private const val MILLI = 1000.0
            private const val COUNT_PARTS = 4

            /** The mark in [line]; a line with no reservoir gives a mark with the reservoir unknown. */
            fun decode(line: String?): Kept? {
                val parts = line?.split(SEPARATOR) ?: return null
                if (parts.size != COUNT_PARTS && parts.size != COUNT_PARTS + 1) return null
                val mark = BasalPeriod.Mark.decode(parts.take(COUNT_PARTS).joinToString(SEPARATOR)) ?: return null
                val count = mark.delivered as? Delivered.Count ?: return null
                val reservoir = if (parts.size == COUNT_PARTS) null else (parts[COUNT_PARTS].toLongOrNull() ?: return null) / MILLI
                return Kept(mark.atMs, count, reservoir)
            }
        }
    }

    /** The mark kept, or null when there is none yet. */
    fun get(): Kept? = Kept.decode(store.state(serial, STATE_KEY))

    /** The next span runs from [end]. */
    fun move(end: BolusSpanEnd.Count) = store.putState(serial, STATE_KEY, Kept(end.atMs, end.count, end.reservoirUnits).encode())

    companion object {

        const val STATE_KEY = "bolusCountMark"
    }
}
