package app.aaps.pump.apex.account.bolus

import app.aaps.pump.apex.account.basal.Delivered
import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.protocol.ApexJournalAnswer

/**
 * What the pump gave at the end of a span to tell how much insulin went in as boluses over it.
 * A pump with the daily statistics gives them; a pump without them gives only its count of the day.
 * See `BolusMoveDeep`.
 */
sealed interface BolusSpanEnd {

    /** The daily statistics read after the journal, newest day first. */
    class DayRecord(val days: ApexJournalAnswer<ApexDailyRecord>) : BolusSpanEnd

    /** The pump's count of the day and its reservoir, read at the heartbeat that came at [atMs]. */
    class Count(val atMs: Long, val count: Delivered.Count, val reservoirUnits: Double? = null) : BolusSpanEnd
}
