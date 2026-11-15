package app.aaps.pump.apex.account.basal

import app.aaps.pump.apex.protocol.ApexDailyRecord

/**
 * What the pump delivered up to a heartbeat, as far as the pump tells it. A pump with the daily
 * statistics tells it by kind, and the basal is the move of two of its fields. A pump without them
 * tells only the count of all the insulin of the day, and the basal is that count less the boluses.
 */
sealed interface Delivered {

    /** The daily statistics as read, newest day first; [broken] when they came with a broken-looking record every time. */
    class DayRecord(val days: List<ApexDailyRecord>, val broken: Boolean = false) : Delivered

    /** The pump's count of all the insulin delivered today, U, and the pump's calendar day it belongs to. */
    class Count(val units: Double, val pumpDay: Long) : Delivered {

        companion object {

            /** The pump's clock is read as a wall clock in whole seconds; its calendar day is the number of days in it. */
            fun of(units: Double, clockSeconds: Long) = Count(units, Math.floorDiv(clockSeconds, SECONDS_PER_DAY))

            private const val SECONDS_PER_DAY = 86_400L
        }
    }
}
